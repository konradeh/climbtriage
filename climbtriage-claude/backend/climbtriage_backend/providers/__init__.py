"""Provider selection. Real providers need server-side credentials; the fixture is opt-in only."""

from __future__ import annotations

import os

from .base import HoldSegmenter, ProviderError


def segmenter_from_env() -> HoldSegmenter | None:
    """The configured hold segmenter, or None when nothing real is configured.

    CLIMBTRIAGE_PROVIDER: "vlmrun" (default) or "fixture" (tests/demos of the job
    machinery only; results are stamped provenance.kind = "fixture").
    """
    kind = os.environ.get("CLIMBTRIAGE_PROVIDER", "vlmrun")
    if kind == "fixture":
        from .fixture import FixtureSegmenter
        return FixtureSegmenter()
    if kind == "vlmrun":
        key = os.environ.get("VLMRUN_API_KEY", "")
        if not key:
            return None
        from .vlmrun import VlmRunSam31Segmenter
        return VlmRunSam31Segmenter(key, base_url=os.environ.get(
            "VLMRUN_BASE_URL", "https://gateway.vlm.run/v1/openai"))
    raise ValueError(f"unknown CLIMBTRIAGE_PROVIDER {kind!r}")


__all__ = ["HoldSegmenter", "ProviderError", "segmenter_from_env"]
