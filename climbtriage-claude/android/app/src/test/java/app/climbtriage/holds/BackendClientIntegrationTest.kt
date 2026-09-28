package app.climbtriage.holds

import app.climbtriage.contracts.CoordinateSystem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Live Kotlin-client ↔ Python-backend contract test. Runs only when CLIMBTRIAGE_BACKEND_URL is set,
 * against a backend started with CLIMBTRIAGE_PROVIDER=fixture. It verifies the wire protocol
 * (resumable upload, idempotent job, polling, result decoding) — not hold-detection quality.
 */
class BackendClientIntegrationTest {
    private val url = System.getProperty("climbtriage.backendUrl").orEmpty()

    private fun wallJpeg(): ByteArray {
        val img = BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color(128, 128, 128); g.fillRect(0, 0, 640, 480)
        g.color = Color(40, 180, 40); g.fillOval(100, 330, 40, 40); g.fillOval(300, 200, 36, 36)
        g.color = Color(200, 30, 30); g.fillRect(480, 60, 60, 50)
        g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(img, "jpg", it) }.toByteArray()
    }

    @Test fun detectHoldsRoundTrip() = runBlocking {
        assumeTrue("set CLIMBTRIAGE_BACKEND_URL to run", url.isNotBlank())
        val client = BackendClient(url)
        assertEquals("configured", client.health()["provider"].toString().trim('"'))
        val statuses = mutableListOf<String>()
        val result = client.detectHolds(wallJpeg()) { statuses += it.status }
        assertEquals("climbtriage.v1", result.schema)
        assertEquals(3, result.holds.size)
        assertTrue(result.holds.all { it.coords == CoordinateSystem.WALL_NORM })
        assertTrue("fixture output must be labelled", result.holds.all { it.provenance.isFixture })
        assertTrue(statuses.last() == "succeeded")
    }
}
