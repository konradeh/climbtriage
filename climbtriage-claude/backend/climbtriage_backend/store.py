"""Durable state: uploads, jobs (which double as the dev queue), cache index, tombstones.

SQLite for local development. The interface is small on purpose so a PostgreSQL
implementation (`SELECT ... FOR UPDATE SKIP LOCKED` for claiming) and Azure Service Bus
as the wake-up signal can replace it without touching the API or worker logic.
"""

from __future__ import annotations

import json
import sqlite3
import threading
import time
from dataclasses import dataclass
from pathlib import Path

from .contracts import new_id

SCHEMA_SQL = """
CREATE TABLE IF NOT EXISTS uploads (
  id TEXT PRIMARY KEY, size INTEGER NOT NULL, sha256 TEXT NOT NULL, content_type TEXT NOT NULL,
  offset INTEGER NOT NULL DEFAULT 0, state TEXT NOT NULL DEFAULT 'open',  -- open|complete|corrupt|deleted
  created_at REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS jobs (
  id TEXT PRIMARY KEY, kind TEXT NOT NULL, upload_id TEXT NOT NULL, params TEXT NOT NULL,
  idem_key TEXT UNIQUE, request_hash TEXT NOT NULL, cache_key TEXT NOT NULL,
  status TEXT NOT NULL,                    -- queued|running|succeeded|failed|cancelled
  step INTEGER NOT NULL DEFAULT 0, steps_total INTEGER NOT NULL, step_name TEXT,
  attempts INTEGER NOT NULL DEFAULT 0, max_attempts INTEGER NOT NULL,
  next_attempt_at REAL NOT NULL DEFAULT 0, lease_until REAL NOT NULL DEFAULT 0,
  cancel_requested INTEGER NOT NULL DEFAULT 0, deleted INTEGER NOT NULL DEFAULT 0,
  error TEXT, result_ref TEXT, created_at REAL NOT NULL, updated_at REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS cache (
  cache_key TEXT PRIMARY KEY, result_ref TEXT NOT NULL, job_id TEXT NOT NULL
);
"""

TERMINAL = {"succeeded", "failed", "cancelled"}


@dataclass
class Job:
    id: str
    kind: str
    upload_id: str
    params: dict
    idem_key: str | None
    request_hash: str
    cache_key: str
    status: str
    step: int
    steps_total: int
    step_name: str | None
    attempts: int
    max_attempts: int
    next_attempt_at: float
    cancel_requested: bool
    deleted: bool
    error: str | None
    result_ref: str | None

    def public(self) -> dict:
        return {"id": self.id, "kind": self.kind, "status": self.status, "step": self.step,
                "stepsTotal": self.steps_total, "stepName": self.step_name,
                "attempts": self.attempts, "error": self.error}


def _job(row: sqlite3.Row) -> Job:
    return Job(id=row["id"], kind=row["kind"], upload_id=row["upload_id"], params=json.loads(row["params"]),
               idem_key=row["idem_key"], request_hash=row["request_hash"], cache_key=row["cache_key"],
               status=row["status"], step=row["step"], steps_total=row["steps_total"],
               step_name=row["step_name"], attempts=row["attempts"], max_attempts=row["max_attempts"],
               next_attempt_at=row["next_attempt_at"], cancel_requested=bool(row["cancel_requested"]),
               deleted=bool(row["deleted"]), error=row["error"], result_ref=row["result_ref"])


class Store:
    def __init__(self, path: Path):
        path.parent.mkdir(parents=True, exist_ok=True)
        self._db = sqlite3.connect(str(path), check_same_thread=False, isolation_level=None, timeout=30)
        self._db.row_factory = sqlite3.Row
        self._db.execute("PRAGMA journal_mode=WAL")
        self._db.executescript(SCHEMA_SQL)
        self._lock = threading.RLock()

    # ── transactions ───────────────────────────────────────────────────────
    def _tx(self):
        store = self

        class _Tx:
            def __enter__(self):
                store._lock.acquire()
                store._db.execute("BEGIN IMMEDIATE")
                return store._db

            def __exit__(self, exc_type, exc, tb):
                try:
                    store._db.execute("ROLLBACK" if exc_type else "COMMIT")
                finally:
                    store._lock.release()
                return False
        return _Tx()

    # ── uploads ────────────────────────────────────────────────────────────
    def create_upload(self, size: int, sha256: str, content_type: str) -> str:
        upload_id = new_id("up")
        with self._tx() as db:
            db.execute("INSERT INTO uploads(id,size,sha256,content_type,created_at) VALUES(?,?,?,?,?)",
                       (upload_id, size, sha256, content_type, time.time()))
        return upload_id

    def upload(self, upload_id: str) -> sqlite3.Row | None:
        with self._lock:
            return self._db.execute("SELECT * FROM uploads WHERE id=?", (upload_id,)).fetchone()

    def advance_upload(self, upload_id: str, expected_offset: int, new_offset: int) -> bool:
        with self._tx() as db:
            cur = db.execute("UPDATE uploads SET offset=? WHERE id=? AND offset=? AND state='open'",
                             (new_offset, upload_id, expected_offset))
            return cur.rowcount == 1

    def set_upload_state(self, upload_id: str, state: str) -> None:
        with self._tx() as db:
            db.execute("UPDATE uploads SET state=? WHERE id=?", (state, upload_id))

    # ── jobs ───────────────────────────────────────────────────────────────
    def create_job(self, *, kind: str, upload_id: str, params: dict, idem_key: str | None,
                   request_hash: str, cache_key: str, steps_total: int, max_attempts: int
                   ) -> tuple[Job, bool]:
        """Insert, or return the existing job for this idempotency key. `(job, created)`.

        Raises ValueError when the key was used with a different request body.
        """
        with self._tx() as db:
            if idem_key:
                row = db.execute("SELECT * FROM jobs WHERE idem_key=?", (idem_key,)).fetchone()
                if row:
                    if row["request_hash"] != request_hash:
                        raise ValueError("Idempotency-Key reused with a different request")
                    return _job(row), False
            job_id = new_id("job")
            now = time.time()
            db.execute("""INSERT INTO jobs(id,kind,upload_id,params,idem_key,request_hash,cache_key,status,
                          steps_total,max_attempts,created_at,updated_at)
                          VALUES(?,?,?,?,?,?,?,'queued',?,?,?,?)""",
                       (job_id, kind, upload_id, json.dumps(params, sort_keys=True), idem_key, request_hash,
                        cache_key, steps_total, max_attempts, now, now))
            return _job(db.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone()), True

    def job(self, job_id: str) -> Job | None:
        with self._lock:
            row = self._db.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone()
        return _job(row) if row else None

    def claim(self, *, lease_s: float, now: float | None = None) -> Job | None:
        """Atomically take the next runnable job. Expired leases (a crashed worker) are reclaimed."""
        now = time.time() if now is None else now
        with self._tx() as db:
            row = db.execute("""SELECT * FROM jobs WHERE deleted=0 AND (
                                  (status='queued' AND next_attempt_at<=?) OR
                                  (status='running' AND lease_until<?))
                                ORDER BY created_at LIMIT 1""", (now, now)).fetchone()
            if not row:
                return None
            db.execute("""UPDATE jobs SET status='running', attempts=attempts+1, lease_until=?, updated_at=?
                          WHERE id=?""", (now + lease_s, now, row["id"]))
            return _job(db.execute("SELECT * FROM jobs WHERE id=?", (row["id"],)).fetchone())

    def progress(self, job_id: str, step: int, step_name: str, *, lease_s: float) -> bool:
        """Record a completed step and extend the lease. False = stop (cancelled or deleted)."""
        now = time.time()
        with self._tx() as db:
            db.execute("""UPDATE jobs SET step=?, step_name=?, lease_until=?, updated_at=?
                          WHERE id=? AND status='running'""", (step, step_name, now + lease_s, now, job_id))
            row = db.execute("SELECT cancel_requested, deleted FROM jobs WHERE id=?", (job_id,)).fetchone()
        return bool(row) and not row["cancel_requested"] and not row["deleted"]

    def retry_later(self, job_id: str, error: str, delay_s: float) -> None:
        with self._tx() as db:
            db.execute("""UPDATE jobs SET status='queued', error=?, next_attempt_at=?, updated_at=?
                          WHERE id=? AND status='running' AND deleted=0""",
                       (error, time.time() + delay_s, time.time(), job_id))

    def finish(self, job_id: str, status: str, *, error: str | None = None) -> None:
        with self._tx() as db:
            db.execute("UPDATE jobs SET status=?, error=?, updated_at=? WHERE id=? AND status NOT IN "
                       "('succeeded','failed','cancelled')", (status, error, time.time(), job_id))

    def commit_result(self, job_id: str, result_ref: str, *, cache_key: str | None, steps_total: int) -> bool:
        """Mark success **only if** the job was not deleted or cancelled meanwhile.

        This is the tombstone check that stops a late worker from recreating deleted
        data: it runs in the same transaction as the write that would make the result
        visible. False = the caller must discard the blob it wrote.
        """
        with self._tx() as db:
            cur = db.execute("""UPDATE jobs SET status='succeeded', result_ref=?, step=?, step_name='done',
                                error=NULL, updated_at=? WHERE id=? AND deleted=0 AND cancel_requested=0
                                AND status='running'""", (result_ref, steps_total, time.time(), job_id))
            if cur.rowcount != 1:
                return False
            if cache_key:
                db.execute("INSERT OR REPLACE INTO cache(cache_key,result_ref,job_id) VALUES(?,?,?)",
                           (cache_key, result_ref, job_id))
            return True

    def request_cancel(self, job_id: str) -> Job | None:
        with self._tx() as db:
            db.execute("UPDATE jobs SET cancel_requested=1, updated_at=? WHERE id=? AND deleted=0",
                       (time.time(), job_id))
            db.execute("""UPDATE jobs SET status='cancelled', updated_at=? WHERE id=? AND status='queued'""",
                       (time.time(), job_id))
        return self.job(job_id)

    def tombstone(self, job_id: str) -> Job | None:
        """Mark deleted and drop cache rows that point at this job's results."""
        with self._tx() as db:
            db.execute("UPDATE jobs SET deleted=1, cancel_requested=1, result_ref=NULL, updated_at=? WHERE id=?",
                       (time.time(), job_id))
            db.execute("DELETE FROM cache WHERE job_id=?", (job_id,))
        return self.job(job_id)

    def cached(self, cache_key: str) -> str | None:
        with self._lock:
            row = self._db.execute("SELECT result_ref FROM cache WHERE cache_key=?", (cache_key,)).fetchone()
        return row["result_ref"] if row else None
