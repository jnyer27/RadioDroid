"""
serial_shim.py — Drop-in serial.Serial replacement for Android / RadioDroid.

All hardware I/O (USB OTG and BLE) is handled on the Kotlin side.
This shim connects to an Android LocalSocket that Kotlin relays bytes through.

Port convention:   "android://SOCKET_NAME"          (data channel)
Control channel:   "android://SOCKET_NAME_ctrl"     (baud rate / config)

Many CHIRP drivers dynamically change radio.pipe.timeout and radio.pipe.baudrate
inside their sync_in() / _do_ident() implementations.  For example, the RT85
driver (th_uv88.py) sets:
    radio.pipe.baudrate = 57600   (BAUDRATE constant)
    radio.pipe.timeout  = 2       (STIMEOUT constant)
before sending the programming handshake.

This shim honours those assignments via Python properties:
  - .timeout   → calls _sock.settimeout() so subsequent reads use the new value
  - .baudrate  → sends "BAUD:XXXXX\\n" on the control socket; Kotlin calls
                 UsbSerialPort.setParameters() and replies "OK\\n" before
                 returning so the USB port is reconfigured synchronously.
  - .parity    → stored locally (USB is always N for now)
"""

import socket as _socket


class AndroidSerial:
    """Mimics the serial.Serial API used by CHIRP drivers."""

    def __init__(self, port: str, baudrate: int = 9600, timeout: float = 0.5):
        self.port     = port
        self._baudrate = int(baudrate)
        self._timeout  = float(timeout) if timeout is not None else 0.0
        self._parity   = "N"
        self._sock     = None
        self._ctrl     = None

        if not port.startswith("android://"):
            raise ValueError(
                f"RadioDroid only supports android:// ports, got: {port!r}"
            )

        socket_name = port[len("android://"):]

        # ── Data socket ───────────────────────────────────────────────────────
        s = _socket.socket(_socket.AF_UNIX, _socket.SOCK_STREAM)
        s.settimeout(self._timeout if self._timeout > 0 else None)
        s.connect("\0" + socket_name)
        self._sock = s

        # ── Control socket ────────────────────────────────────────────────────
        # Used to send baud-rate-change commands to Kotlin so the USB serial
        # port is reconfigured before data is exchanged at the new rate.
        # If the control socket is unavailable (e.g. BLE bridge), we silently
        # skip baud-rate changes — the USB relay handles that differently.
        try:
            ctrl = _socket.socket(_socket.AF_UNIX, _socket.SOCK_STREAM)
            ctrl.settimeout(5.0)          # generous timeout for ACK
            ctrl.connect("\0" + socket_name + "_ctrl")
            self._ctrl = ctrl
        except Exception:
            self._ctrl = None

    # ── timeout property ──────────────────────────────────────────────────────

    @property
    def timeout(self) -> float:
        return self._timeout

    @timeout.setter
    def timeout(self, value):
        self._timeout = float(value) if value is not None else 0.0
        if self._sock is not None:
            self._sock.settimeout(self._timeout if self._timeout > 0 else None)

    # ── baudrate property ─────────────────────────────────────────────────────

    @property
    def baudrate(self) -> int:
        return self._baudrate

    @baudrate.setter
    def baudrate(self, value):
        self._baudrate = int(value)
        if self._ctrl is None:
            return
        try:
            # Send command; Kotlin reconfigures USB then sends ACK
            self._ctrl.sendall(f"BAUD:{self._baudrate}\n".encode())
            # Block until Kotlin confirms the port is reconfigured
            resp = b""
            while b"\n" not in resp:
                chunk = self._ctrl.recv(16)
                if not chunk:
                    break
                resp += chunk
        except Exception:
            pass   # If control channel fails, continue best-effort

    # ── parity property ───────────────────────────────────────────────────────

    @property
    def parity(self) -> str:
        return self._parity

    @parity.setter
    def parity(self, value):
        self._parity = str(value)
        # USB always opened as PARITY_NONE; extend if needed

    # ── I/O ───────────────────────────────────────────────────────────────────

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
        pass   # no output buffer to flush

    def close(self):
        for s in (self._sock, self._ctrl):
            if s is not None:
                try:
                    s.close()
                except Exception:
                    pass
        self._sock = None
        self._ctrl = None

    @property
    def in_waiting(self) -> int:
        return 0

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.close()
