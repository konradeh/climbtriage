package app.climbtriage.geometry

import org.junit.Assert.assertEquals
import org.junit.Test

class TransformTest {
    private fun assertPt(expected: Pt, actual: Pt, eps: Double = 1e-9) {
        assertEquals(expected.x, actual.x, eps)
        assertEquals(expected.y, actual.y, eps)
    }

    @Test fun rotation0_isNormalisation() {
        val t = FrameGeometry(1920, 1080, 0, false).videoPxToFrameNorm()
        assertPt(Pt(0.0, 0.0), t.apply(0.0, 0.0))
        assertPt(Pt(1.0, 1.0), t.apply(1920.0, 1080.0))
    }

    @Test fun rotation90_encodedTopLeftLandsTopRight() {
        // A portrait phone recording: 1920x1080 encoded, rotated 90° clockwise for display.
        val g = FrameGeometry(1920, 1080, 90, false)
        assertEquals(1080, g.displayWidth); assertEquals(1920, g.displayHeight)
        val t = g.videoPxToFrameNorm()
        assertPt(Pt(1.0, 0.0), t.apply(0.0, 0.0))
        assertPt(Pt(0.0, 1.0), t.apply(1920.0, 1080.0))
        assertPt(Pt(0.0, 0.0), t.apply(0.0, 1080.0))
    }

    @Test fun rotation180_and_270() {
        val t180 = FrameGeometry(200, 100, 180, false).videoPxToFrameNorm()
        assertPt(Pt(1.0, 1.0), t180.apply(0.0, 0.0))
        val t270 = FrameGeometry(200, 100, 270, false).videoPxToFrameNorm()
        assertPt(Pt(0.0, 1.0), t270.apply(0.0, 0.0))     // top-left → bottom-left
        assertPt(Pt(1.0, 0.0), t270.apply(200.0, 100.0))
    }

    @Test fun mirroringFlipsDisplayedX() {
        val t = FrameGeometry(200, 100, 0, true).videoPxToFrameNorm()
        assertPt(Pt(1.0, 0.0), t.apply(0.0, 0.0))
        assertPt(Pt(0.75, 0.5), t.apply(50.0, 50.0))
    }

    @Test fun roundTripThroughInverse() {
        for (rot in listOf(0, 90, 180, 270)) for (mirror in listOf(false, true)) {
            val g = FrameGeometry(640, 360, rot, mirror)
            val p = Pt(123.0, 77.0)
            assertPt(p, g.frameNormToVideoPx().apply(g.videoPxToFrameNorm().apply(p)), 1e-6)
        }
    }

    @Test fun letterboxPortraitVideoInLandscapeView() {
        val v = Viewport.fit(1080.0, 1920.0, 2000.0, 1000.0)
        assertEquals(1000.0, v.height, 1e-9)
        assertEquals(562.5, v.width, 1e-9)
        assertEquals((2000.0 - 562.5) / 2, v.left, 1e-9)
        assertPt(Pt(v.left, 0.0), v.fromFrameNorm().apply(0.0, 0.0))
        assertPt(Pt(0.5, 0.5), v.toFrameNorm().apply(1000.0, 500.0))
    }

    @Test fun composeWallToView() {
        // wall_norm → frame_norm (identity registration) → view
        val chain = Transform2D.IDENTITY.then(Viewport.fit(16.0, 9.0, 1600.0, 1600.0).fromFrameNorm())
        assertPt(Pt(800.0, 800.0), chain.apply(0.5, 0.5))
    }
}
