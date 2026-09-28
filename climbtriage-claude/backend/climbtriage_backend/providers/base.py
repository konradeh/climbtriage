"""Provider adapter interfaces. Everything vendor-specific lives behind these."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Protocol

import numpy as np


class ProviderError(RuntimeError):
    """A provider call failed. `transient` errors are retried; others fail the job."""

    def __init__(self, message: str, *, transient: bool):
        super().__init__(message)
        self.transient = transient


@dataclass
class Instance:
    """One segmented instance in the input image's pixel grid."""

    mask: np.ndarray            # bool, (H, W)
    score: float | None
    label: str                  # the prompt that produced it


@dataclass
class SegmentationResult:
    width: int
    height: int
    instances: list[Instance]
    raw: dict = field(default_factory=dict)      # the provider's own payload(s), checkpointed verbatim
    usage: list[dict] = field(default_factory=list)


class HoldSegmenter(Protocol):
    """Text-prompted instance segmentation of a still image."""

    provider: str
    model: str
    provenance_kind: str        # "model" for real providers, "fixture" for the test double

    def segment(self, image_jpeg: bytes, prompts: list[str]) -> dict:
        """Call the provider; return its raw JSON payload(s) keyed by prompt. May raise ProviderError."""

    def parse(self, raw: dict, width: int, height: int) -> SegmentationResult:
        """Turn a raw payload (possibly loaded from a checkpoint) into instances. Pure."""
