"""
serial_shim.py — Drop-in serial.Serial replacement for Android / RadioDroid.

All hardware I/O (USB OTG and BLE) is handled on the Kotlin side.
This shim connects to an Android LocalSocket that Kotlin relays bytes through.

Port convention:  "android://SOCKET_NAME"
"""

import socket as _socket


class AndroidSerial:
    """Mimics the serial.Serial API used by CHIRP drivers."""

    def __init__(self, port: str, baudrate: int = 9600, timeout: float = 0.5):
        self.port     = port
        self.baudrate = baudrate
        self.timeout  = timeout
        self._sock    = None

        if not port.startswith("android://"):
            raise ValueError(f"RadioDroid only supports android:// ports, got: {port!r}")

        socket_name = port[len("android://"):]
        s = _socket.socket(_socket.AF_UNIX, _socket.SOCK_STREAM)
        s.settimeout(timeout)
        s.connect("\0" + socket_name)   # Abstract namespace socket (Android)
        self._sock = s

    def read(self, size: int = 1) -> bytes:
        chunks = []
        remaining = size
        while remaining > 0:
            try:
                chunk = self._sock.recv(remaining)
            except _socket.timeout:
                break
            if not chunk:
                break
            chunks.append(chunk)
            remaining -= len(chunk)
        return b"".join(chunks)

    def write(self, data: bytes) -> int:
        self._sock.sendall(data)
        return len(data)

    def flush(self):
        pass

    def close(self):
        if self._sock:
            try:
                self._sock.close()
            except Exception:
                pass
            self._sock = None

    @property
    def in_waiting(self) -> int:
        return 0

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.close()
