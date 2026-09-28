"""Object storage. Local filesystem in development; Azure Blob Storage in production.

Keys are relative POSIX paths. Writes are atomic (temp file + rename) so a crashed
worker never leaves a half-written result that looks complete.
"""

from __future__ import annotations

import os
import shutil
from pathlib import Path


class LocalBlobStore:
    def __init__(self, root: Path):
        self.root = root
        root.mkdir(parents=True, exist_ok=True)

    def _path(self, key: str) -> Path:
        path = (self.root / key).resolve()
        if self.root.resolve() not in path.parents:
            raise ValueError(f"blob key escapes the store: {key!r}")
        return path

    def put(self, key: str, data: bytes) -> str:
        path = self._path(key)
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp = path.with_suffix(path.suffix + ".tmp")
        tmp.write_bytes(data)
        os.replace(tmp, path)
        return key

    def append(self, key: str, data: bytes) -> None:
        path = self._path(key)
        path.parent.mkdir(parents=True, exist_ok=True)
        with open(path, "ab") as fh:
            fh.write(data)

    def get(self, key: str) -> bytes:
        return self._path(key).read_bytes()

    def exists(self, key: str) -> bool:
        return self._path(key).exists()

    def size(self, key: str) -> int:
        path = self._path(key)
        return path.stat().st_size if path.exists() else 0

    def truncate(self, key: str, size: int) -> None:
        path = self._path(key)
        if path.exists():
            with open(path, "r+b") as fh:
                fh.truncate(size)

    def delete(self, key: str) -> None:
        path = self._path(key)
        if path.exists():
            path.unlink()

    def delete_prefix(self, prefix: str) -> None:
        path = self._path(prefix)
        if path.is_dir():
            shutil.rmtree(path)
        elif path.exists():
            path.unlink()

    def copy(self, src: str, dst: str) -> str:
        return self.put(dst, self.get(src))
