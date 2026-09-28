"""Server configuration from the environment. Secrets are read here and never logged or echoed."""

from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path


def load_dotenv(path: Path = Path(".env")) -> None:
    """Minimal `.env` loader: KEY=VALUE lines; real environment variables always win."""
    if not path.exists():
        return
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        if value.strip():
            os.environ.setdefault(key.strip(), value.strip())


@dataclass(frozen=True)
class Settings:
    data_dir: Path
    max_upload_bytes: int = 25 * 1024 * 1024       # one wall image; video upload is not an M1 feature
    max_attempts: int = 3
    backoff_base_s: float = 2.0
    lease_s: float = 300.0
    poll_s: float = 0.5
    api_token: str | None = None                   # optional shared token for LAN/dev deployments

    @property
    def db_path(self) -> Path:
        return self.data_dir / "climbtriage.sqlite3"

    @property
    def blob_root(self) -> Path:
        return self.data_dir / "blobs"

    @staticmethod
    def from_env() -> "Settings":
        load_dotenv()
        return Settings(
            data_dir=Path(os.environ.get("CLIMBTRIAGE_DATA_DIR", "./.data")),
            max_attempts=int(os.environ.get("CLIMBTRIAGE_MAX_ATTEMPTS", "3")),
            api_token=os.environ.get("CLIMBTRIAGE_API_TOKEN") or None,
        )
