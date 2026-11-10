package io.cuemodo.pocketpeek

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UniverseTest {
    @Test fun labelSplitsPortAddress() {
        assertEquals("0:0:0", Universe.label(0))
        assertEquals("0:0:15", Universe.label(15))
        assertEquals("0:1:0", Universe.label(16))
        assertEquals("1:0:2", Universe.label(258))
        assertEquals("127:15:15", Universe.label(Universe.MAX))
    }

    @Test fun parsesPlainNumber() {
        assertEquals(0, Universe.parse("0"))
        assertEquals(12, Universe.parse(" 12 "))
        assertEquals(Universe.MAX, Universe.parse("32767"))
    }

    @Test fun parsesNetSubUni() {
        assertEquals(12, Universe.parse("0:0:12"))
        assertEquals(258, Universe.parse("1.0.2"))
        assertEquals(258, Universe.parse("1 0 2"))
        assertEquals(Universe.MAX, Universe.parse("127:15:15"))
    }

    @Test fun rejectsInvalid() {
        assertNull(Universe.parse(""))
        assertNull(Universe.parse("abc"))
        assertNull(Universe.parse("-1"))
        assertNull(Universe.parse("32768"))
        assertNull(Universe.parse("0:16:0"))
        assertNull(Universe.parse("128:0:0"))
        assertNull(Universe.parse("0:0:16"))
        assertNull(Universe.parse("1:2"))
        assertNull(Universe.parse("1:2:3:4"))
    }

    @Test fun labelAndParseRoundTrip() {
        for (u in listOf(0, 1, 15, 16, 255, 256, 4096, Universe.MAX)) {
            assertEquals(u, Universe.parse(Universe.label(u)))
        }
    }
}

class FormatTest {
    @Test fun formatAgoUnits() {
        assertEquals("0 s", formatAgo(999))
        assertEquals("12 s", formatAgo(12_400))
        assertEquals("59 s", formatAgo(59_999))
        assertEquals("1 min", formatAgo(60_000))
        assertEquals("59 min", formatAgo(3_599_999))
        assertEquals("2 h", formatAgo(7_200_000))
    }

    @Test fun pctMatchesConsolelink() {
        assertEquals(0, pct(0))
        assertEquals(50, pct(128))
        assertEquals(64, pct(162))
        assertEquals(100, pct(255))
    }

    @Test fun zeroIsBlankInEveryMode() {
        for (m in DisplayMode.entries) assertEquals("", m.labels[0])
    }

    @Test fun modeLabels() {
        assertEquals("F", DisplayMode.PCT.labels[255])
        assertEquals("50", DisplayMode.PCT.labels[128])
        assertEquals("1", DisplayMode.PCT.labels[2])
        assertEquals("255", DisplayMode.DEC.labels[255])
        assertEquals("7", DisplayMode.DEC.labels[7])
        assertEquals("FF", DisplayMode.HEX.labels[255])
        assertEquals("0A", DisplayMode.HEX.labels[10])
    }

    @Test fun modeCycles() {
        assertEquals(DisplayMode.DEC, DisplayMode.PCT.next())
        assertEquals(DisplayMode.HEX, DisplayMode.DEC.next())
        assertEquals(DisplayMode.PCT, DisplayMode.HEX.next())
    }

}

class GridShapeTest {
    @Test fun everyShapeDividesAUniverse() {
        for (s in GridShape.CANDIDATES) assertEquals("$s", 512, s.cells * s.pages)
    }

    @Test fun candidatesAreOrderedByChannels() {
        val cells = GridShape.CANDIDATES.map { it.cells }
        assertEquals(cells.sortedDescending(), cells)
    }

    @Test fun r1PortraitGetsFourByEight() {
        // R1: 480 x 640 px at density 190 -> ~389 x ~350 dp left for the grid.
        assertEquals(GridShape(4, 8), GridShape.choose(389f, 350f))
    }

    @Test fun phonePortraitGetsFourBySixteen() {
        // Medium Phone emulator: 1080 x 2400 px at density 420 -> ~395 x ~750 dp for the grid.
        assertEquals(GridShape(4, 16), GridShape.choose(395f, 750f))
    }

    @Test fun landscapeTabletGetsSixteenBySixteen() {
        // 1280 x 800 photo frame at density 160: ~1264 x ~650 dp for the grid.
        assertEquals(GridShape(16, 16), GridShape.choose(1264f, 650f))
    }

    @Test fun bigLandscapeShowsWholeUniverse() {
        assertEquals(GridShape(32, 16), GridShape.choose(1900f, 1000f))
    }

    @Test fun portraitTabletUsesTallShapes() {
        assertEquals(GridShape(8, 16), GridShape.choose(780f, 1000f))
    }

    @Test fun tinySpaceFallsBackToSmallest() {
        assertEquals(GridShape.SMALLEST, GridShape.choose(100f, 100f))
    }
}
