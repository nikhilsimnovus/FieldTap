"""FieldTap: a real-UE network probe built on a commercial Qualcomm Android handset.

Pipeline:  handset diag port -> fieldtap.diag (clean-room diag client)
           -> fieldtap.decode (log record -> RRC/NAS payload + channel)
           -> fieldtap.output (pcapng / GSMTAP / live Wireshark)
"""

__version__ = "0.1.0"
