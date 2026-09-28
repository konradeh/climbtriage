package app.climbtriage.contracts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The Kotlin mirror decodes the same golden file the backend validates against the JSON Schema. */
class ContractsGoldenTest {
    private val golden = File(System.getProperty("climbtriage.repoRoot"), "contracts/examples/session.v1.json").readText()

    @Test fun decodesGoldenSession() {
        val doc = ContractJson.decodeFromString(SessionDocument.serializer(), golden)
        assertEquals(90, doc.capture?.video?.rotationDeg)
        assertEquals(listOf(0L, 33366L, 66733L, 100100L), doc.capture?.timebase?.ptsUs)
        val lost = doc.personTracks[0].samples[1]
        assertEquals(Validity.LOST, lost.validity)
        assertTrue(lost.landmarks.isEmpty())
        assertEquals(CoordinateSystem.WALL_NORM, doc.wallVersions[0].coords)
        assertEquals("ADD_HOLD", doc.corrections[0].op)
    }

    @Test fun roundTripIsStable() {
        // Free-form payloads keep number literals verbatim ("0.50"), so compare canonical encodings.
        val s = SessionDocument.serializer()
        val once = ContractJson.encodeToString(s, ContractJson.decodeFromString(s, golden))
        val twice = ContractJson.encodeToString(s, ContractJson.decodeFromString(s, once))
        assertEquals(once, twice)
    }

    @Test fun rejectsOtherMajorVersion() {
        assertThrows(IllegalArgumentException::class.java) {
            ContractJson.decodeFromString(SessionDocument.serializer(), golden.replace("\"climbtriage.v1\"", "\"climbtriage.v2\""))
        }
    }

    @Test fun ulidsAreWellFormed() {
        val id = Ids.new("h")
        assertTrue(Regex("^h_[0-9A-Z]{26}$").matches(id))
    }
}
