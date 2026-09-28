"""Single-machine durable queue. Transactions fence publication and deletion.

Small keyframes live in SQLite in this slice, making upload offsets, digest
checks and tombstones atomic. Production object storage needs generation fences.
"""
from __future__ import annotations

import hashlib
import json
import sqlite3
import time
import uuid
from contextlib import contextmanager
from pathlib import Path

MAX_UPLOAD = 12 * 1024 * 1024
MAX_CHUNK = 1024 * 1024
CONFIG = {"schema": 1, "adapter": "fal-sam3-binary-v1", "model": "fal-ai/sam-3/image",
          "prompt": "climbing hold", "max_masks": 128, "revision_epoch": "2026-09-28"}


class Conflict(Exception):
    pass


class Gone(Exception):
    pass


class Store:
    def __init__(self, path: Path):
        path.parent.mkdir(parents=True, exist_ok=True)
        self.path = path
        with self.db() as db:
            db.executescript("""
                PRAGMA journal_mode=WAL;
                CREATE TABLE IF NOT EXISTS uploads (
                  id TEXT PRIMARY KEY, sha TEXT NOT NULL, size INTEGER NOT NULL,
                  mime TEXT NOT NULL, data BLOB NOT NULL, complete INTEGER NOT NULL DEFAULT 0,
                  deleted INTEGER NOT NULL DEFAULT 0);
                CREATE TABLE IF NOT EXISTS jobs (
                  id TEXT PRIMARY KEY, upload_id TEXT NOT NULL, idem TEXT UNIQUE NOT NULL,
                  fingerprint TEXT NOT NULL, request TEXT NOT NULL, status TEXT NOT NULL,
                  phase TEXT NOT NULL, units INTEGER NOT NULL DEFAULT 0,
                  tries INTEGER NOT NULL DEFAULT 0, next_at REAL NOT NULL DEFAULT 0,
                  lease_until REAL NOT NULL DEFAULT 0, token TEXT, created REAL NOT NULL,
                  checkpoint TEXT, result TEXT, error TEXT, configuration TEXT NOT NULL);
            """)

    @contextmanager
    def db(self):
        db = sqlite3.connect(self.path, timeout=30)
        db.row_factory = sqlite3.Row
        try:
            db.execute("BEGIN IMMEDIATE")
            yield db
            db.commit()
        except BaseException:
            db.rollback()
            raise
        finally:
            db.close()

    @staticmethod
    def upload_row(db, ident):
        row = db.execute("SELECT * FROM uploads WHERE id=?", (ident,)).fetchone()
        if not row or row["deleted"]:
            raise Gone("Upload absent or deleted")
        return row

    def create_upload(self, sha, size, mime):
        if not 0 < size <= MAX_UPLOAD:
            raise Conflict("Keyframe must be 1 byte to 12 MiB")
        ident = str(uuid.uuid4())
        with self.db() as db:
            if db.execute("SELECT count(*) FROM uploads WHERE deleted=0").fetchone()[0] >= 128:
                raise Conflict("Local upload capacity reached; delete unused uploads")
            db.execute("INSERT INTO uploads(id,sha,size,mime,data) VALUES(?,?,?,?,?)",
                       (ident, sha, size, mime, b""))
        return self.upload_info(ident)

    def upload_info(self, ident):
        with self.db() as db:
            row = self.upload_row(db, ident)
            return {"id": ident, "offset": len(row["data"]), "complete": bool(row["complete"])}

    def append(self, ident, offset, chunk):
        if not chunk or len(chunk) > MAX_CHUNK:
            raise Conflict("Chunk must be 1 byte to 1 MiB")
        with self.db() as db:
            row = self.upload_row(db, ident)
            if offset != len(row["data"]) or row["complete"]:
                raise Conflict("Offset mismatch; GET upload before resuming")
            data = row["data"] + chunk
            if len(data) > row["size"]:
                raise Conflict("Upload exceeds declared size")
            complete = len(data) == row["size"]
            if complete and hashlib.sha256(data).hexdigest() != row["sha"]:
                raise Conflict("Digest mismatch; final chunk was not accepted")
            db.execute("UPDATE uploads SET data=?,complete=? WHERE id=?", (data, complete, ident))
        return self.upload_info(ident)

    def new_job(self, request, idem):
        serialized = json.dumps(request, sort_keys=True, separators=(",", ":"))
        with self.db() as db:
            upload = self.upload_row(db, request["upload_id"])
            if not upload["complete"]:
                raise Conflict("Upload incomplete")
            fingerprint = hashlib.sha256((serialized + upload["sha"] + json.dumps(CONFIG, sort_keys=True)).encode()).hexdigest()
            previous = db.execute("SELECT * FROM jobs WHERE idem=?", (idem,)).fetchone()
            if previous:
                if previous["fingerprint"] != fingerprint:
                    raise Conflict("Idempotency key reused with different input/configuration")
                if previous["status"] == "deleted":
                    raise Gone("Job deleted")
                return self.public(previous)
            # Reuse only within this live upload; never resurrect deleted data or mix consent scopes.
            cached = db.execute("SELECT * FROM jobs WHERE fingerprint=? AND status='succeeded'", (fingerprint,)).fetchone()
            if db.execute("SELECT count(*) FROM jobs WHERE status IN ('queued','running')").fetchone()[0] >= 32:
                raise Conflict("Local queue full")
            ident = str(uuid.uuid4())
            db.execute("""INSERT INTO jobs(id,upload_id,idem,fingerprint,request,status,phase,created,result,units,configuration)
                          VALUES(?,?,?,?,?,?,?,?,?,?,?)""",
                       (ident, request["upload_id"], idem, fingerprint, serialized,
                        "succeeded" if cached else "queued", "cached" if cached else "queued", time.time(),
                        cached["result"] if cached else None, 3 if cached else 0, json.dumps(CONFIG, sort_keys=True)))
            return self.public(db.execute("SELECT * FROM jobs WHERE id=?", (ident,)).fetchone())

    @staticmethod
    def public(row):
        result = json.loads(row["result"]) if row["result"] else None
        if result is not None:
            # Dense immutable provider data stays server-side; phone receives bounded geometry.
            result = {k: v for k, v in result.items() if k not in ("raw_provider_response", "raw_masks_png_base64")}
        return {"id": row["id"], "status": row["status"], "phase": row["phase"],
                "completed_units": row["units"], "total_units": 3, "retry_count": row["tries"],
                "error": row["error"], "result": result}

    def job(self, ident):
        with self.db() as db:
            row = db.execute("SELECT * FROM jobs WHERE id=?", (ident,)).fetchone()
            if not row or row["status"] == "deleted":
                raise Gone("Job absent or deleted")
            return self.public(row)

    def cancel(self, ident):
        with self.db() as db:
            row = db.execute("SELECT * FROM jobs WHERE id=?", (ident,)).fetchone()
            if not row or row["status"] == "deleted":
                raise Gone("Job absent or deleted")
            db.execute("""UPDATE jobs SET status='cancelled',phase='cancelled',token=NULL
                          WHERE id=? AND status IN ('queued','running')""", (ident,))
        return self.job(ident)

    def delete(self, ident):
        with self.db() as db:
            # Tombstone remains even when bytes and results are erased.
            db.execute("UPDATE uploads SET data=?,deleted=1 WHERE id=?", (b"", ident))
            db.execute("""UPDATE jobs SET status='deleted',phase='deleted',result=NULL,
                       checkpoint=NULL,error=NULL,token=NULL,request='{}' WHERE upload_id=?""", (ident,))

    def claim(self):
        now = time.time()
        with self.db() as db:
            row = db.execute("""SELECT * FROM jobs WHERE
                 (status='queued' AND next_at<=?) OR (status='running' AND lease_until<?)
                 ORDER BY created LIMIT 1""", (now, now)).fetchone()
            if not row:
                return None
            upload = self.upload_row(db, row["upload_id"])
            token = str(uuid.uuid4())
            db.execute("UPDATE jobs SET status='running',token=?,lease_until=? WHERE id=?",
                       (token, now + 90, row["id"]))
            item = dict(row)
            item.update(token=token, data=upload["data"], mime=upload["mime"], sha=upload["sha"])
            item["request"] = json.loads(row["request"])
            item["checkpoint"] = json.loads(row["checkpoint"] or "{}")
            item["configuration"] = json.loads(row["configuration"])
            return item

    def checkpoint(self, item, checkpoint, phase, units, delay=1):
        with self.db() as db:
            return db.execute("""UPDATE jobs SET status='queued',checkpoint=?,phase=?,units=?,
                          next_at=?,lease_until=0 WHERE id=? AND token=? AND status='running'""",
                              (json.dumps(checkpoint), phase, units, time.time() + delay,
                               item["id"], item["token"])).rowcount == 1

    def finish(self, item, result):
        with self.db() as db:
            return db.execute("""UPDATE jobs SET status='succeeded',phase='complete',units=3,result=?,error=NULL
                        WHERE id=? AND token=? AND status='running'""",
                              (json.dumps(result, allow_nan=False), item["id"], item["token"])).rowcount == 1

    def fail(self, item, error, retryable=False):
        retries = item["tries"] + 1
        retry = retryable and retries < 3 and time.time() - item["created"] < 900
        with self.db() as db:
            db.execute("""UPDATE jobs SET status=?,phase=?,tries=?,error=?,next_at=?
                          WHERE id=? AND token=? AND status='running'""",
                       ("queued" if retry else "failed", "retry_wait" if retry else "failed", retries,
                        error, time.time() + 2 ** retries, item["id"], item["token"]))
