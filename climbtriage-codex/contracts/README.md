# Contract sources

* `openapi-v1.json`: generated from the implemented FastAPI request schemas/routes. Run the service and fetch `/openapi.json` to regenerate. Result phase/record semantics are in `docs/CONTRACTS.md` and `backend/climbtriage/store.py`.
* Android serializable M1 records: `android/app/src/main/java/app/climbtriage/domain/Models.kt`.
* Deferred ContactEvent/Attempt/3D contracts: `docs/CONTRACTS.md` and the capture manifest section of `docs/SPECIFICATION.md`. They are design contracts, not fabricated runtime results.

Time is integer microseconds, normalized video coordinates are upright x-right/y-down, and hold IDs are independent UUIDs. Polygon `parts` on the provider wire are lists of `[x,y]` arrays; the Android adapter explicitly converts these into serializable Point records. This conversion is intentional, not an assumption that local and provider JSON are byte-identical.
