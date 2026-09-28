"""Pydantic mirror of contracts/schema/climbtriage.v1.schema.json (the subset the backend emits).

The JSON Schema is the source of truth; tests/test_contracts.py validates what these
models serialise against it and against the shared golden example.
"""

from __future__ import annotations

import os
import time
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

from . import SCHEMA

CoordinateSystem = Literal["video_px", "frame_norm", "inference_norm", "wall_norm"]

_CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"


def ulid() -> str:
    """A 26-character ULID: 48-bit millisecond time + 80 random bits, Crockford base32."""
    value = (int(time.time() * 1000) << 80) | int.from_bytes(os.urandom(10), "big")
    return "".join(_CROCKFORD[(value >> (5 * i)) & 31] for i in reversed(range(26)))


def new_id(prefix: str) -> str:
    return f"{prefix}_{ulid()}"


class _Model(BaseModel):
    model_config = ConfigDict(extra="forbid")


class Provenance(_Model):
    kind: Literal["model", "heuristic", "user", "fixture"]
    name: str
    version: str
    runId: str | None = None
    configHash: str | None = None


class HoldColor(_Model):
    lab: list[float] = Field(min_length=3, max_length=3)
    hex: str
    method: str


class Hold(_Model):
    id: str = Field(pattern=r"^h_[0-9A-Z]{26}$")
    kind: Literal["hold", "volume", "unknown"]
    coords: CoordinateSystem
    polygon: list[list[float]] = Field(min_length=3)
    bbox: list[float] = Field(min_length=4, max_length=4)
    color: HoldColor | None = None
    confidence: float | None = Field(default=None, ge=0, le=1)
    provenance: Provenance
    parents: list[str] = Field(default_factory=list)
    retired: bool = False


class Coverage(_Model):
    framesRequested: int
    framesAnalyzed: int
    framesDropped: int


class AnalysisRun(_Model):
    id: str
    kind: Literal["pose_detection", "hold_candidates", "tracking", "contact", "stillness"]
    inputSha256: str
    provider: str
    model: str
    modelVersion: str | None = None
    configHash: str | None = None
    schema_: Literal["climbtriage.v1"] = Field(default=SCHEMA, alias="schema")
    startedAt: str | None = None
    finishedAt: str | None = None
    status: Literal["running", "succeeded", "failed", "cancelled"]
    coverage: Coverage | None = None
    outputRef: str | None = None
    provenance: Provenance

    model_config = ConfigDict(extra="forbid", populate_by_name=True)


class HoldCandidatesResult(_Model):
    """The payload of a finished `hold_candidates` job."""

    schema_: Literal["climbtriage.v1"] = Field(default=SCHEMA, alias="schema")
    run: AnalysisRun
    imageWidthPx: int
    imageHeightPx: int
    holds: list[Hold]

    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    def dump(self) -> dict:
        return self.model_dump(by_alias=True, mode="json")
