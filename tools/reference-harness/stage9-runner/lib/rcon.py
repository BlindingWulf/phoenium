"""Persistent Source-RCON client. One TCP connection per run (Stage 8: per-command connections caused
TIME_WAIT pressure and WinError 10048). Thread-safe: commands are serialised with a lock."""
from __future__ import annotations

import socket
import struct
import threading
import time

AUTH, EXEC = 3, 2


class RconError(RuntimeError):
    pass


class Rcon:
    def __init__(self, host: str, port: int, password: str, timeout: float = 30.0):
        self.host, self.port, self.password, self.timeout = host, port, password, timeout
        self._sock: socket.socket | None = None
        self._lock = threading.Lock()
        self._next_id = 100
        self.commands_sent = 0

    def connect(self, attempts: int = 60, delay: float = 1.0) -> "Rcon":
        last: Exception | None = None
        for _ in range(attempts):
            try:
                sock = socket.create_connection((self.host, self.port), timeout=self.timeout)
                sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                self._sock = sock
                request_id = self._send(AUTH, self.password)
                reply_id, _ = self._recv()
                if reply_id == -1 or reply_id != request_id:
                    raise RconError("RCON authentication failed")
                return self
            except (OSError, RconError) as error:
                last = error
                self.close()
                time.sleep(delay)
        raise RconError(f"RCON connect to {self.host}:{self.port} failed: {last}")

    def command(self, text: str) -> str:
        with self._lock:
            if self._sock is None:
                raise RconError("RCON not connected")
            request_id = self._send(EXEC, text)
            reply_id, body = self._recv()
            if reply_id != request_id:
                raise RconError(f"RCON reply id {reply_id} != request {request_id}")
            self.commands_sent += 1
            return body

    def close(self) -> None:
        if self._sock is not None:
            try:
                self._sock.close()
            except OSError:
                pass
            self._sock = None

    def _send(self, kind: int, body: str) -> int:
        self._next_id += 1
        payload = body.encode("utf-8")
        packet = struct.pack("<iii", 10 + len(payload), self._next_id, kind) + payload + b"\x00\x00"
        assert self._sock is not None
        self._sock.sendall(packet)
        return self._next_id

    def _recv(self) -> tuple[int, str]:
        size = struct.unpack("<i", self._read(4))[0]
        data = self._read(size)
        request_id, _kind = struct.unpack("<ii", data[:8])
        return request_id, data[8:-2].decode("utf-8", errors="replace")

    def _read(self, n: int) -> bytes:
        assert self._sock is not None
        chunks, remaining = [], n
        while remaining:
            chunk = self._sock.recv(remaining)
            if not chunk:
                raise RconError("RCON connection closed")
            chunks.append(chunk)
            remaining -= len(chunk)
        return b"".join(chunks)
