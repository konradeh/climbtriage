package app.climbtriage.events

import app.climbtriage.contracts.ContactEvent
import app.climbtriage.contracts.PersonTrack
import app.climbtriage.contracts.RouteVersion
import app.climbtriage.contracts.WallVersion
import app.climbtriage.geometry.Transform2D

/**
 * M2 boundary — declared, not implemented in M1.
 *
 * Contract for the implementation (docs/06 M2.1):
 *  - distances in hold-height units on wall_norm (via [frameToWall]), separate enter/release
 *    thresholds, dwell measured in µs from PoseSample.tUs, relative motion limb↔hold;
 *  - a missing or non-VALID limb yields an `UNKNOWN` interval, never a closed contact;
 *  - feet use heel/foot-index landmarks when visible, else `footPoint = ankle_approx`;
 *  - wall smears and volume contacts are `target.surface`, never invented holds;
 *  - 2D proximity is reported as contact evidence, not as physical contact or force.
 *
 * `frameToWall` is null when registration is unreliable (e.g. stillness MOVED frames); the
 * inferencer must then emit `UNKNOWN` rather than use unregistered coordinates.
 */
interface ContactInferencer {
    fun infer(track: PersonTrack, wall: WallVersion, route: RouteVersion, frameToWall: (Long) -> Transform2D?): List<ContactEvent>
}
