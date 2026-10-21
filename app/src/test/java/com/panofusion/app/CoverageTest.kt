package com.panofusion.app
import org.junit.Assert.*
import org.junit.Test
class CoverageTest {
    @Test fun emptySphere() { assertEquals(0.0, Coverage.percentage(ByteArray(800), 40, 20), 1e-9) }
    @Test fun fullSphere() { assertEquals(100.0, Coverage.percentage(ByteArray(800) { -1 }, 40, 20), 1e-8) }
    @Test fun halfSphereAndTargetHemisphere() {
        val mask = ByteArray(800) { if (it % 40 in 10..29) -1 else 0 }
        assertEquals(50.0, Coverage.percentage(mask, 40, 20), 1e-8)
        assertEquals(100.0, Coverage.percentage(mask, 40, 20, true), 1e-8)
    }
    @Test fun polarRowsHaveLessArea() {
        val polar = ByteArray(800) { if (it < 40) -1 else 0 }
        val equator = ByteArray(800) { if (it / 40 == 10) -1 else 0 }
        assertTrue(Coverage.percentage(polar, 40, 20) < Coverage.percentage(equator, 40, 20))
    }
}
