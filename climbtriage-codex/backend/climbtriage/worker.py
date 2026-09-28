"""Run separately: python -m climbtriage.worker. One process for the local MVP."""
from __future__ import annotations

import os
import time
from pathlib import Path

import httpx

from .providers import FalSam3, ProviderContractError
from .store import CONFIG, Store


def tick(store: Store, factory=FalSam3):
    item = store.claim()
    if item is None:
        return False
    provider = None
    try:
        if item["configuration"] != CONFIG:
            raise ProviderContractError("Job configuration differs from this worker; submit a new analysis")
        if time.time() - item["created"] > 900:
            raise ProviderContractError("Analysis timed out after 15 minutes")
        provider = factory()
        checkpoint = item["checkpoint"]
        if not checkpoint:
            checkpoint = provider.submit(item["data"], item["mime"])
            store.checkpoint(item, checkpoint, "provider_submitted", 1)
        elif provider.status(checkpoint) != "COMPLETED":
            store.checkpoint(item, checkpoint, "provider_waiting", 1, delay=2)
        else:
            result = provider.result(checkpoint, item["data"], item["request"]["timestamp_us"])
            store.finish(item, result)
    except httpx.HTTPStatusError as error:
        status = error.response.status_code
        store.fail(item, f"Provider HTTP {status}", retryable=status == 429 or status >= 500)
    except httpx.TransportError:
        store.fail(item, "Provider network failure", retryable=True)
    except (ProviderContractError, KeyError, TypeError, ValueError) as error:
        # Contract errors are actionable, not transient. Do not log request/image/key data.
        message = str(error) if isinstance(error, ProviderContractError) else "Provider response failed schema validation"
        store.fail(item, message)
    except Exception as error:
        # A codec/provider programming failure must not crash/reclaim forever.
        # Preserve type for diagnosis without serializing media, URLs or secrets.
        store.fail(item, f"Adapter failure: {type(error).__name__}; inspect the adapter locally")
    finally:
        if provider is not None and hasattr(provider, "close"):
            provider.close()
    return True


def main():
    store = Store(Path(os.environ.get("CLIMBTRIAGE_DATA", "data")) / "analysis.sqlite")
    print("ClimbTriage local worker; Ctrl+C to stop")
    while True:
        tick(store)
        time.sleep(.5)


if __name__ == "__main__":
    main()
