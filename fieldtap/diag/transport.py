"""Byte transports between the host and the diag port.

Every transport exposes the same tiny interface: open(), close(), read(),
write(). The client above it does not care whether bytes arrive over a
Qualcomm USB serial port, a raw USB bulk endpoint, a TCP relay from a rooted
handset, or a file being replayed.
"""

from __future__ import annotations

import os
import shutil
import socket
import subprocess
import time
from dataclasses import dataclass
from typing import Optional


class TransportError(RuntimeError):
    pass


class Transport:
    name = "transport"
    #: False for replay transports: the client will not try to configure the modem.
    interactive = True

    def open(self) -> None:
        raise NotImplementedError

    def close(self) -> None:
        pass

    def read(self, max_bytes: int = 65536, timeout: float = 0.5) -> bytes:
        """Return up to max_bytes; b"" on timeout. Raise EOFError when finished."""
        raise NotImplementedError

    def write(self, data: bytes) -> None:
        raise NotImplementedError

    def describe(self) -> dict:
        return {"transport": self.name}

    def __enter__(self):
        self.open()
        return self

    def __exit__(self, *exc):
        self.close()


# --- Serial (Qualcomm "HS-USB Diagnostics" COM port, /dev/ttyUSBx) -------------------

QUALCOMM_VID = 0x05C6


@dataclass
class PortInfo:
    device: str
    description: str
    vid: Optional[int]
    pid: Optional[int]
    likely_diag: bool
    serial_number: Optional[str] = None   # USB serial of the (parent) device: usually the adb serial
    location: Optional[str] = None        # bus/port path, stable while the phone stays plugged in
    hwid: str = ""


def list_serial_ports() -> list:
    try:
        from serial.tools import list_ports
    except ImportError:
        return []
    ports = []
    for p in list_ports.comports():
        desc = (p.description or "") + " " + (p.manufacturer or "") + " " + (p.product or "")
        likely = ("diag" in desc.lower()) or (p.vid == QUALCOMM_VID and "9091" in desc) \
            or ("901d" in desc.lower()) or ("9091" in desc.lower())
        ports.append(PortInfo(p.device, desc.strip(), p.vid, p.pid, likely,
                              getattr(p, "serial_number", None) or None,
                              getattr(p, "location", None) or None, p.hwid or ""))
    return ports


class SerialTransport(Transport):
    name = "serial"

    def __init__(self, port: str, baudrate: int = 115200):
        self.port = port
        self.baudrate = baudrate
        self._ser = None

    def open(self) -> None:
        try:
            import serial
        except ImportError as exc:
            raise TransportError("pyserial is not installed: pip install fieldtap[serial]") from exc
        try:
            self._ser = serial.Serial(self.port, self.baudrate, timeout=0.05, write_timeout=2)
        except Exception as exc:
            raise TransportError("cannot open %s: %s" % (self.port, exc)) from exc

    def close(self) -> None:
        if self._ser is not None:
            try:
                self._ser.close()
            finally:
                self._ser = None

    def read(self, max_bytes: int = 65536, timeout: float = 0.5) -> bytes:
        deadline = time.monotonic() + timeout
        try:
            while True:
                waiting = self._ser.in_waiting
                if waiting:
                    return self._ser.read(min(max_bytes, waiting))
                if time.monotonic() >= deadline:
                    return b""
                chunk = self._ser.read(1)
                if chunk:
                    return chunk + self._ser.read(min(max_bytes - 1, self._ser.in_waiting))
        except Exception as exc:   # pyserial raises SerialException / OSError when the port vanishes
            raise TransportError("serial port %s lost: %s" % (self.port, exc)) from exc

    def write(self, data: bytes) -> None:
        try:
            self._ser.write(data)
            self._ser.flush()
        except Exception as exc:
            raise TransportError("serial port %s lost: %s" % (self.port, exc)) from exc

    def describe(self) -> dict:
        return {"transport": "serial", "port": self.port}


# --- Raw USB (libusb / WinUSB) --------------------------------------------------------------

DIAG_INTERFACE_PROTOCOLS = (0x30, 0xFF)


def _iter_diag_interfaces(dev):
    """Yield (interface, ep_in, ep_out) for interfaces that look like diag."""
    import usb.util
    for cfg in dev:
        for intf in cfg:
            if intf.bInterfaceClass != 0xFF or intf.bInterfaceSubClass != 0xFF:
                continue
            if intf.bInterfaceProtocol not in DIAG_INTERFACE_PROTOCOLS:
                continue
            bulk_in = bulk_out = None
            for ep in intf:
                if usb.util.endpoint_type(ep.bmAttributes) != usb.util.ENDPOINT_TYPE_BULK:
                    continue
                if usb.util.endpoint_direction(ep.bEndpointAddress) == usb.util.ENDPOINT_IN:
                    bulk_in = bulk_in or ep
                else:
                    bulk_out = bulk_out or ep
            if bulk_in is not None and bulk_out is not None and intf.bNumEndpoints == 2:
                yield cfg, intf, bulk_in, bulk_out


def list_usb_diag_devices() -> list:
    try:
        import usb.core
    except ImportError:
        return []
    found = []
    try:
        devices = list(usb.core.find(find_all=True))
    except Exception:
        return []
    for dev in devices:
        try:
            for _cfg, intf, ep_in, ep_out in _iter_diag_interfaces(dev):
                try:
                    import usb.util
                    serial = usb.util.get_string(dev, dev.iSerialNumber) if dev.iSerialNumber else None
                except Exception:
                    serial = None
                found.append({
                    "vid": dev.idVendor, "pid": dev.idProduct,
                    "bus": dev.bus, "address": dev.address,
                    "interface": intf.bInterfaceNumber,
                    "ep_in": ep_in.bEndpointAddress, "ep_out": ep_out.bEndpointAddress,
                    "serial": serial,
                })
        except Exception:
            continue
    return found


class UsbTransport(Transport):
    name = "usb"

    def __init__(self, vid: Optional[int] = None, pid: Optional[int] = None,
                 interface: Optional[int] = None):
        self.vid, self.pid, self.interface = vid, pid, interface
        self._dev = None
        self._intf = None
        self._ep_in = None
        self._ep_out = None

    def open(self) -> None:
        try:
            import usb.core
            import usb.util
        except ImportError as exc:
            raise TransportError("pyusb is not installed: pip install fieldtap[usb]") from exc
        kwargs = {}
        if self.vid is not None:
            kwargs["idVendor"] = self.vid
        if self.pid is not None:
            kwargs["idProduct"] = self.pid
        try:
            candidates = list(usb.core.find(find_all=True, **kwargs))
        except usb.core.NoBackendError as exc:
            raise TransportError("no libusb backend found (install libusb / WinUSB via Zadig)") from exc
        for dev in candidates:
            for cfg, intf, ep_in, ep_out in _iter_diag_interfaces(dev):
                if self.interface is not None and intf.bInterfaceNumber != self.interface:
                    continue
                try:
                    if dev.get_active_configuration().bConfigurationValue != cfg.bConfigurationValue:
                        dev.set_configuration(cfg.bConfigurationValue)
                except Exception:
                    pass
                try:
                    if dev.is_kernel_driver_active(intf.bInterfaceNumber):
                        dev.detach_kernel_driver(intf.bInterfaceNumber)
                except (NotImplementedError, Exception):
                    pass
                usb.util.claim_interface(dev, intf.bInterfaceNumber)
                self._dev, self._intf, self._ep_in, self._ep_out = dev, intf, ep_in, ep_out
                return
        raise TransportError("no USB diag interface found (is the phone in diag USB mode?)")

    def close(self) -> None:
        if self._dev is not None:
            try:
                import usb.util
                usb.util.release_interface(self._dev, self._intf.bInterfaceNumber)
                usb.util.dispose_resources(self._dev)
            except Exception:
                pass
            self._dev = None

    def read(self, max_bytes: int = 65536, timeout: float = 0.5) -> bytes:
        import usb.core
        try:
            data = self._ep_in.read(max(self._ep_in.wMaxPacketSize * 64, 16384),
                                    timeout=int(timeout * 1000))
            return bytes(data)
        except usb.core.USBTimeoutError:
            return b""
        except usb.core.USBError as exc:
            if getattr(exc, "errno", None) in (110,) or "timed out" in str(exc).lower():
                return b""
            raise TransportError("USB read failed: %s" % exc) from exc

    def write(self, data: bytes) -> None:
        self._ep_out.write(data, timeout=2000)

    def describe(self) -> dict:
        d = {"transport": "usb"}
        if self._dev is not None:
            d.update(vid="0x%04X" % self._dev.idVendor, pid="0x%04X" % self._dev.idProduct,
                     interface=self._intf.bInterfaceNumber)
        return d


# --- TCP relay -------------------------------------------------------------------------------

class TcpTransport(Transport):
    name = "tcp"

    def __init__(self, host: str, port: int):
        self.host, self.port = host, port
        self._sock = None

    def open(self) -> None:
        try:
            self._sock = socket.create_connection((self.host, self.port), timeout=5)
        except OSError as exc:
            raise TransportError("cannot connect to %s:%d: %s" % (self.host, self.port, exc)) from exc
        self._sock.setblocking(False)

    def close(self) -> None:
        if self._sock is not None:
            try:
                self._sock.close()
            finally:
                self._sock = None

    def read(self, max_bytes: int = 65536, timeout: float = 0.5) -> bytes:
        import select
        ready, _, _ = select.select([self._sock], [], [], timeout)
        if not ready:
            return b""
        data = self._sock.recv(max_bytes)
        if not data:
            raise EOFError("relay closed the connection")
        return data

    def write(self, data: bytes) -> None:
        self._sock.sendall(data)

    def describe(self) -> dict:
        return {"transport": "tcp", "host": self.host, "port": self.port}


# --- adb (rooted handset running the FieldTap device helper) -----------------------------------

ADB_HELPER_REMOTE = "/data/local/tmp/fieldtap-diagd"
ADB_DEFAULT_PORT = 45299


def adb_path() -> Optional[str]:
    override = os.environ.get("FIELDTAP_ADB")
    if override and os.path.isfile(override):
        return override
    found = shutil.which("adb")
    if found:
        return found
    exe = "adb.exe" if os.name == "nt" else "adb"
    repo_tools = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), "tools")
    candidates = [
        os.path.join(os.environ.get("FIELDTAP_TOOLS", repo_tools), "platform-tools", exe),
        os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk", "platform-tools", exe),
        os.path.join(os.path.expanduser("~"), "platform-tools", exe),
        os.path.join("C:\\", "platform-tools", exe),
    ]
    for candidate in candidates:
        if candidate and os.path.isfile(candidate):
            return candidate
    return None


def adb(args: list, serial: Optional[str] = None, timeout: float = 30, check: bool = True) -> str:
    exe = adb_path()
    if exe is None:
        raise TransportError("adb is not on PATH (install Android platform-tools)")
    cmd = [exe]
    if serial:
        cmd += ["-s", serial]
    cmd += args
    proc = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    if check and proc.returncode != 0:
        raise TransportError("adb %s failed: %s" % (" ".join(args), (proc.stderr or proc.stdout).strip()))
    return proc.stdout


def adb_devices() -> list:
    if adb_path() is None:
        return []
    out = adb(["devices", "-l"], check=False)
    devices = []
    for line in out.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] in ("device", "unauthorized", "offline"):
            devices.append({"serial": parts[0], "state": parts[1],
                            "info": " ".join(parts[2:])})
    return devices


def adb_getprops(serial: Optional[str] = None) -> dict:
    """Handset identity for the session sidecar. Best effort; empty on failure."""
    props = {
        "ro.product.manufacturer": "manufacturer",
        "ro.product.model": "model",
        "ro.product.device": "device",
        "ro.build.display.id": "android_build",
        "ro.build.version.release": "android_version",
        "ro.build.version.security_patch": "security_patch",
        "ro.build.characteristics": "characteristics",
        "ro.kernel.qemu": "qemu",
        "gsm.version.baseband": "baseband",
        "ro.baseband": "baseband_type",
        "ro.board.platform": "platform",
        "ro.hardware": "hardware",
        "ro.soc.model": "soc",
        "gsm.operator.numeric": "operator_mccmnc",
        "gsm.operator.alpha": "operator_name",
        "gsm.sim.operator.numeric": "sim_mccmnc",
        "gsm.sim.operator.alpha": "sim_operator_name",
        "gsm.network.type": "network_type",
        "sys.usb.config": "usb_config",
        "sys.usb.state": "usb_state",
    }
    result = {}
    try:
        out = adb(["shell", "getprop"], serial=serial, check=False, timeout=15)
    except Exception:
        return result
    for line in out.splitlines():
        if not line.startswith("["):
            continue
        try:
            key, value = line.split("]: [", 1)
        except ValueError:
            continue
        key = key.strip("[")
        value = value.rstrip("]").strip()
        if key in props and value:
            result[props[key]] = value
    return result


def adb_su(cmd: str, serial: Optional[str] = None, timeout: float = 30) -> str:
    """Run a shell command as root, trying `su -c` and then an adb root shell."""
    exe = adb_path()
    if exe is None:
        raise TransportError("adb is not on PATH")
    base = [exe] + (["-s", serial] if serial else [])
    attempts = (
        base + ["shell", "su", "-c", cmd],
        base + ["shell", "su", "0", cmd],
        base + ["shell", cmd],
    )
    last = ""
    for attempt in attempts:
        proc = subprocess.run(attempt, capture_output=True, text=True, timeout=timeout)
        if proc.returncode == 0:
            return proc.stdout
        last = (proc.stderr or proc.stdout).strip()
    raise TransportError("root shell command failed: %s" % last)


# Ordered most-specific first. The long OnePlus/OPPO composition is what current
# OxygenOS/ColorOS builds expect; a shorter string is silently ignored on some of them.
# See docs/DEVICE-SETUP.md and docs/research/windows-diag-and-device-control.md.
DIAG_USB_CONFIGS = (
    "diag,diag_mdm,qdss,qdss_mdm,serial_cdev,dpl,rmnet,adb",   # OnePlus / OPPO / Realme
    "diag,serial_cdev,rmnet,adb",                              # common Qualcomm reference
    "diag,diag_mdm,adb",
    "diag,adb",
)


def adb_enable_diag_usb(serial: Optional[str] = None) -> str:
    """Ask the handset to expose the diag USB function. Returns the config applied."""
    for config in DIAG_USB_CONFIGS:
        try:
            adb_su("setprop sys.usb.config %s" % config, serial=serial)
        except TransportError:
            continue
        time.sleep(2)
        state = adb(["shell", "getprop", "sys.usb.state"], serial=serial, check=False).strip()
        if "diag" in state:
            return state
    raise TransportError("the handset did not switch to a diag USB configuration")


def adb_disable_diag_usb(serial: Optional[str] = None, config: str = "mtp,adb") -> None:
    adb_su("setprop sys.usb.config %s" % config, serial=serial)


class AdbTransport(Transport):
    """Talks to /dev/diag through the FieldTap helper running on the handset.

    The helper (device/fieldtap-diagd.c) opens /dev/diag as root, switches the
    driver into memory-device logging, and relays raw HDLC bytes over a TCP
    socket on the phone that adb forwards to this host.
    """
    name = "adb"

    def __init__(self, serial: Optional[str] = None, helper: Optional[str] = None,
                 port: int = ADB_DEFAULT_PORT, remote_proc: Optional[str] = None):
        self.serial = serial
        self.helper_local = helper
        self.port = port
        self.remote_proc = remote_proc
        self._tcp: Optional[TcpTransport] = None
        self._proc: Optional[subprocess.Popen] = None

    def open(self) -> None:
        exe = adb_path()
        if exe is None:
            raise TransportError("adb is not on PATH (install Android platform-tools)")
        if self.helper_local:
            if not os.path.exists(self.helper_local):
                raise TransportError("helper binary not found: %s" % self.helper_local)
            adb(["push", self.helper_local, ADB_HELPER_REMOTE], serial=self.serial)
            adb_su("chmod 755 %s" % ADB_HELPER_REMOTE, serial=self.serial)
        cmd = [exe] + (["-s", self.serial] if self.serial else []) + [
            "shell", "su", "-c",
            "%s --tcp %d%s" % (ADB_HELPER_REMOTE, self.port,
                               (" --remote %s" % self.remote_proc) if self.remote_proc else "")]
        self._proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        adb(["forward", "tcp:%d" % self.port, "tcp:%d" % self.port], serial=self.serial)
        deadline = time.monotonic() + 10
        last_exc: Optional[Exception] = None
        while time.monotonic() < deadline:
            if self._proc.poll() is not None:
                output = self._proc.stdout.read() if self._proc.stdout else ""
                raise TransportError("device helper exited: %s" % output.strip())
            try:
                self._tcp = TcpTransport("127.0.0.1", self.port)
                self._tcp.open()
                return
            except TransportError as exc:
                last_exc = exc
                time.sleep(0.5)
        raise TransportError("device helper did not start listening: %s" % last_exc)

    def close(self) -> None:
        if self._tcp is not None:
            self._tcp.close()
            self._tcp = None
        if self._proc is not None:
            try:
                self._proc.terminate()
            except Exception:
                pass
            self._proc = None
        try:
            adb(["forward", "--remove", "tcp:%d" % self.port], serial=self.serial, check=False)
        except Exception:
            pass

    def read(self, max_bytes: int = 65536, timeout: float = 0.5) -> bytes:
        return self._tcp.read(max_bytes, timeout)

    def write(self, data: bytes) -> None:
        self._tcp.write(data)

    def describe(self) -> dict:
        return {"transport": "adb", "serial": self.serial or "", "port": self.port}


# --- File replay ------------------------------------------------------------------------------

class FileTransport(Transport):
    """Replay a raw HDLC stream (.qmdl) as if it were arriving from a modem."""
    name = "file"
    interactive = False

    def __init__(self, path: str, chunk: int = 4096, realtime: bool = False):
        self.path = path
        self.chunk = chunk
        self.realtime = realtime
        self._fh = None

    def open(self) -> None:
        try:
            self._fh = open(self.path, "rb")
        except OSError as exc:
            raise TransportError(str(exc)) from exc

    def close(self) -> None:
        if self._fh is not None:
            self._fh.close()
            self._fh = None

    def read(self, max_bytes: int = 65536, timeout: float = 0.5) -> bytes:
        data = self._fh.read(min(self.chunk, max_bytes))
        if not data:
            raise EOFError("end of file")
        if self.realtime:
            time.sleep(0.01)
        return data

    def write(self, data: bytes) -> None:
        # Nothing to talk to; requests are silently dropped.
        return None

    def describe(self) -> dict:
        return {"transport": "file", "path": self.path}


class LoopbackTransport(Transport):
    """In-memory transport for tests: scripted responses plus a replayed stream."""
    name = "loopback"

    def __init__(self, responder=None, stream: bytes = b""):
        self.responder = responder
        self._inbox = bytearray(stream)
        self.written: list = []

    def open(self) -> None:
        pass

    def read(self, max_bytes: int = 65536, timeout: float = 0.5) -> bytes:
        if not self._inbox:
            if timeout <= 0:
                return b""
            raise EOFError("nothing left")
        data = bytes(self._inbox[:max_bytes])
        del self._inbox[:max_bytes]
        return data

    def write(self, data: bytes) -> None:
        self.written.append(data)
        if self.responder is not None:
            reply = self.responder(data)
            if reply:
                # Responses go to the front so they are seen before queued logs.
                self._inbox[0:0] = reply

    def push(self, data: bytes) -> None:
        self._inbox.extend(data)


# --- Buffered reader: keeps the raw capture complete when decoding falls behind ---------------

class BufferedTransport(Transport):
    """Wrap any transport with a reader thread.

    The thread does nothing but pull bytes off the device and queue them, so
    the OS/USB buffers never overflow while Python is busy unframing and
    decoding. With every log code enabled a modem can push several MB/s in
    bursts; without this, a slow moment in the decoder shows up as CRC errors
    and lost records in the .qmdl.
    """
    name = "buffered"

    def __init__(self, inner: Transport, chunk_timeout: float = 0.2):
        import threading
        self.inner = inner
        self.interactive = inner.interactive
        self.name = inner.name
        self._chunks: list = []
        self._bytes = 0
        self._lock = threading.Lock()
        self._cv = threading.Condition(self._lock)
        self._thread: Optional[threading.Thread] = None
        self._stop = threading.Event()
        self._error: Optional[BaseException] = None
        self._chunk_timeout = chunk_timeout
        self.high_water = 0

    def open(self) -> None:
        import threading
        self.inner.open()
        self._stop.clear()
        self._thread = threading.Thread(target=self._pump, name="fieldtap-reader", daemon=True)
        self._thread.start()

    def _pump(self) -> None:
        while not self._stop.is_set():
            try:
                data = self.inner.read(timeout=self._chunk_timeout)
            except BaseException as exc:      # EOFError, TransportError: hand it to the consumer
                with self._cv:
                    self._error = exc
                    self._cv.notify_all()
                return
            if not data:
                continue
            with self._cv:
                self._chunks.append(data)
                self._bytes += len(data)
                self.high_water = max(self.high_water, self._bytes)
                self._cv.notify_all()

    def read(self, max_bytes: int = 65536, timeout: float = 0.5) -> bytes:
        with self._cv:
            if not self._chunks and self._error is None:
                self._cv.wait(timeout)
            if self._chunks:
                out = bytearray()
                while self._chunks and len(out) + len(self._chunks[0]) <= max_bytes:
                    out += self._chunks.pop(0)
                if not out:                       # a single chunk larger than max_bytes
                    chunk = self._chunks.pop(0)
                    out += chunk[:max_bytes]
                    if len(chunk) > max_bytes:
                        self._chunks.insert(0, chunk[max_bytes:])
                self._bytes -= len(out)
                return bytes(out)
            if self._error is not None:
                raise self._error
            return b""

    def write(self, data: bytes) -> None:
        self.inner.write(data)

    def close(self) -> None:
        self._stop.set()
        self.inner.close()
        if self._thread is not None:
            self._thread.join(timeout=2)
            self._thread = None

    @property
    def queued(self) -> int:
        with self._lock:
            return self._bytes

    def describe(self) -> dict:
        return self.inner.describe()
