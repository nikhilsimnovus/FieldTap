/*
 * fieldtap-diagd: relay /dev/diag on a rooted Qualcomm Android handset to a
 * TCP socket (adb forward) or to stdin/stdout.
 *
 * The Android diag character driver ("diagchar") does not hand the raw HDLC
 * stream to a reader by default. The process has to:
 *   1. open /dev/diag,
 *   2. ioctl DIAG_IOCTL_SWITCH_LOGGING to MEMORY_DEVICE_MODE, whose argument
 *      struct has grown across kernel generations (we try each shape),
 *   3. read() buffers of the form
 *          int32 type (USER_SPACE_DATA_TYPE = 0x20)
 *          int32 count
 *          repeated: int32 len, uint8 data[len]   (data = HDLC frames)
 *      and write() buffers of the form
 *          int32 type (USER_SPACE_DATA_TYPE) [int32 remote token] uint8 hdlc[]
 *
 * Nothing here is derived from another diag tool; it follows the driver's
 * user-space interface as it appears in the public msm kernel sources.
 *
 * Build (Android NDK):   see build.sh
 * Run (as root):         fieldtap-diagd --tcp 45299 [--remote mdm]
 *                        fieldtap-diagd --stdio
 *
 * Status: written against the driver interface; not yet exercised on
 * hardware by the FieldTap project. The USB/serial transports do not need it.
 */

#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/select.h>
#include <sys/socket.h>
#include <unistd.h>

#define DIAG_DEVICE "/dev/diag"

#define DIAG_IOCTL_SWITCH_LOGGING 7
#define DIAG_IOCTL_REMOTE_DEV 32

#define USB_MODE 1
#define MEMORY_DEVICE_MODE 2
#define CALLBACK_MODE 6

#define USER_SPACE_DATA_TYPE 0x00000020
#define DCI_DATA_TYPE 0x00000040
#define USER_SPACE_RAW_DATA_TYPE 0x00000080

/* Remote processor tokens (negative ints) */
#define MDM_TOKEN (-1)
#define MDM2_TOKEN (-2)
#define QSC_TOKEN (-5)

#define READ_BUF (1024 * 1024)

/* Argument shapes for DIAG_IOCTL_SWITCH_LOGGING, newest first. */
struct mode_param_v3 {          /* 4.14+ kernels */
    uint32_t req_mode;
    uint32_t peripheral_mask;
    uint32_t pd_mask;
    uint8_t mode_param;
    uint8_t diag_id;
    uint8_t pd_val;
    uint8_t reserved;
    int32_t peripheral;
    int32_t device_mask;
} __attribute__((packed));

struct mode_param_v2 {          /* 4.4 / 4.9 kernels */
    uint32_t req_mode;
    uint32_t peripheral_mask;
    uint8_t mode_param;
} __attribute__((packed));

struct mode_param_v1 {          /* 3.18 kernels */
    uint32_t req_mode;
    uint32_t peripheral_mask;
} __attribute__((packed));

static volatile int running = 1;
static int verbose = 0;

static void on_signal(int sig) { (void)sig; running = 0; }

static void logmsg(const char *fmt, ...)
{
    va_list ap;
    va_start(ap, fmt);
    fprintf(stderr, "[fieldtap-diagd] ");
    vfprintf(stderr, fmt, ap);
    fprintf(stderr, "\n");
    va_end(ap);
}

static int switch_logging(int fd)
{
    static const uint32_t masks[] = { 0x3FF, 0xFF, 0x7F, 0x1F, 0x3 };
    unsigned i;
    for (i = 0; i < sizeof(masks) / sizeof(masks[0]); i++) {
        struct mode_param_v3 p3;
        memset(&p3, 0, sizeof p3);
        p3.req_mode = MEMORY_DEVICE_MODE;
        p3.peripheral_mask = masks[i];
        p3.pd_mask = 0;
        p3.mode_param = 0;
        p3.peripheral = -1;
        p3.device_mask = 1;   /* local (MSM) */
        if (ioctl(fd, DIAG_IOCTL_SWITCH_LOGGING, &p3) == 0) {
            logmsg("switch logging: v3 struct, mask 0x%X", masks[i]);
            return 0;
        }
    }
    for (i = 0; i < sizeof(masks) / sizeof(masks[0]); i++) {
        struct mode_param_v2 p2;
        memset(&p2, 0, sizeof p2);
        p2.req_mode = MEMORY_DEVICE_MODE;
        p2.peripheral_mask = masks[i];
        p2.mode_param = 0;
        if (ioctl(fd, DIAG_IOCTL_SWITCH_LOGGING, &p2) == 0) {
            logmsg("switch logging: v2 struct, mask 0x%X", masks[i]);
            return 0;
        }
    }
    for (i = 0; i < sizeof(masks) / sizeof(masks[0]); i++) {
        struct mode_param_v1 p1;
        p1.req_mode = MEMORY_DEVICE_MODE;
        p1.peripheral_mask = masks[i];
        if (ioctl(fd, DIAG_IOCTL_SWITCH_LOGGING, &p1) == 0) {
            logmsg("switch logging: v1 struct, mask 0x%X", masks[i]);
            return 0;
        }
    }
    {
        int mode = MEMORY_DEVICE_MODE;
        if (ioctl(fd, DIAG_IOCTL_SWITCH_LOGGING, &mode) == 0) {
            logmsg("switch logging: pointer to int");
            return 0;
        }
        if (ioctl(fd, DIAG_IOCTL_SWITCH_LOGGING, (long)MEMORY_DEVICE_MODE) == 0) {
            logmsg("switch logging: int argument (legacy kernel)");
            return 0;
        }
    }
    logmsg("DIAG_IOCTL_SWITCH_LOGGING failed in every known form: %s", strerror(errno));
    return -1;
}

static int write_all(int fd, const uint8_t *buf, size_t len)
{
    while (len > 0) {
        ssize_t n = write(fd, buf, len);
        if (n < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        buf += n;
        len -= (size_t)n;
    }
    return 0;
}

/* Deliver the payload(s) of one /dev/diag read to the host. */
static int forward_from_diag(const uint8_t *buf, ssize_t n, int out_fd, int remote_token)
{
    int32_t type, count, i;
    size_t off = 0;
    if (n < 8) return 0;
    memcpy(&type, buf, 4);
    if (type != USER_SPACE_DATA_TYPE) {
        if (verbose) logmsg("skipping buffer type 0x%X (%zd bytes)", type, n);
        return 0;
    }
    off = 4;
    if (remote_token != 0) {
        /* Buffers from a remote processor carry the token before the count. */
        int32_t token;
        if ((size_t)n < off + 4) return 0;
        memcpy(&token, buf + off, 4);
        if (token < 0) off += 4;
    }
    if ((size_t)n < off + 4) return 0;
    memcpy(&count, buf + off, 4);
    off += 4;
    for (i = 0; i < count; i++) {
        int32_t len;
        if ((size_t)n < off + 4) break;
        memcpy(&len, buf + off, 4);
        off += 4;
        if (len < 0 || (size_t)n < off + (size_t)len) break;
        if (write_all(out_fd, buf + off, (size_t)len) < 0) return -1;
        off += (size_t)len;
    }
    return 0;
}

/* Deliver host bytes (HDLC frames) to /dev/diag. */
static int forward_to_diag(int diag_fd, const uint8_t *buf, ssize_t n, int remote_token)
{
    uint8_t *pkt = malloc((size_t)n + 8);
    size_t off = 0;
    int32_t type = USER_SPACE_DATA_TYPE;
    int rc;
    if (!pkt) return -1;
    memcpy(pkt, &type, 4);
    off = 4;
    if (remote_token != 0) {
        int32_t token = remote_token;
        memcpy(pkt + off, &token, 4);
        off += 4;
    }
    memcpy(pkt + off, buf, (size_t)n);
    rc = (int)write(diag_fd, pkt, off + (size_t)n);
    free(pkt);
    return rc < 0 ? -1 : 0;
}

static int relay(int diag_fd, int in_fd, int out_fd, int remote_token)
{
    uint8_t *buf = malloc(READ_BUF);
    if (!buf) return -1;
    while (running) {
        fd_set rfds;
        int maxfd = diag_fd > in_fd ? diag_fd : in_fd;
        struct timeval tv = { 1, 0 };
        FD_ZERO(&rfds);
        FD_SET(diag_fd, &rfds);
        FD_SET(in_fd, &rfds);
        if (select(maxfd + 1, &rfds, NULL, NULL, &tv) < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (FD_ISSET(diag_fd, &rfds)) {
            ssize_t n = read(diag_fd, buf, READ_BUF);
            if (n < 0) {
                if (errno == EINTR || errno == EAGAIN) continue;
                logmsg("read /dev/diag: %s", strerror(errno));
                break;
            }
            if (forward_from_diag(buf, n, out_fd, remote_token) < 0) break;
        }
        if (FD_ISSET(in_fd, &rfds)) {
            ssize_t n = read(in_fd, buf, READ_BUF);
            if (n <= 0) break;                    /* host went away */
            if (forward_to_diag(diag_fd, buf, n, remote_token) < 0) {
                logmsg("write /dev/diag: %s", strerror(errno));
            }
        }
    }
    free(buf);
    return 0;
}

static int listen_tcp(int port)
{
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    struct sockaddr_in addr;
    int one = 1;
    if (fd < 0) return -1;
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof one);
    memset(&addr, 0, sizeof addr);
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    addr.sin_port = htons((uint16_t)port);
    if (bind(fd, (struct sockaddr *)&addr, sizeof addr) < 0 || listen(fd, 1) < 0) {
        close(fd);
        return -1;
    }
    return fd;
}

static void usage(void)
{
    fprintf(stderr,
        "usage: fieldtap-diagd (--tcp PORT | --stdio) [--remote mdm|mdm2|qsc] [--verbose]\n"
        "  relays /dev/diag to the host; run as root on the handset\n");
}

int main(int argc, char **argv)
{
    int port = 0, stdio = 0, remote_token = 0, i;
    int diag_fd, in_fd, out_fd, listen_fd = -1;

    for (i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "--tcp") && i + 1 < argc) port = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--stdio")) stdio = 1;
        else if (!strcmp(argv[i], "--verbose")) verbose = 1;
        else if (!strcmp(argv[i], "--remote") && i + 1 < argc) {
            const char *r = argv[++i];
            if (!strcmp(r, "mdm")) remote_token = MDM_TOKEN;
            else if (!strcmp(r, "mdm2")) remote_token = MDM2_TOKEN;
            else if (!strcmp(r, "qsc")) remote_token = QSC_TOKEN;
            else { usage(); return 2; }
        } else { usage(); return 2; }
    }
    if (!port && !stdio) { usage(); return 2; }

    signal(SIGINT, on_signal);
    signal(SIGTERM, on_signal);
    signal(SIGPIPE, SIG_IGN);

    diag_fd = open(DIAG_DEVICE, O_RDWR | O_CLOEXEC);
    if (diag_fd < 0) {
        logmsg("open %s: %s (root? diag driver present?)", DIAG_DEVICE, strerror(errno));
        return 1;
    }
    if (switch_logging(diag_fd) < 0) {
        close(diag_fd);
        return 1;
    }

    if (stdio) {
        in_fd = STDIN_FILENO;
        out_fd = STDOUT_FILENO;
        logmsg("relaying on stdio");
        relay(diag_fd, in_fd, out_fd, remote_token);
    } else {
        listen_fd = listen_tcp(port);
        if (listen_fd < 0) {
            logmsg("listen on 127.0.0.1:%d: %s", port, strerror(errno));
            close(diag_fd);
            return 1;
        }
        logmsg("listening on 127.0.0.1:%d", port);
        while (running) {
            int client = accept(listen_fd, NULL, NULL);
            if (client < 0) {
                if (errno == EINTR) continue;
                break;
            }
            logmsg("host connected");
            relay(diag_fd, client, client, remote_token);
            close(client);
            logmsg("host disconnected");
        }
        close(listen_fd);
    }
    close(diag_fd);
    return 0;
}
