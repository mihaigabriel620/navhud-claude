package com.mihai.navhud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A crash report belongs to the build that wrote it.
 *
 * The report from 1.15 was still popping up on a fresh install of 1.19,
 * describing a bug fixed in 1.17 — and while it was on screen, a real crash
 * would have had nowhere to be seen.
 */
class CrashReportTest {

    private fun report(name: String, code: Int) =
        "NavHUD $name ($code)\n2026-08-24 22:30:49  thread: main\n\njava.lang.RuntimeException: boom"

    @Test fun `the version code is read back out of the report`() {
        assertEquals(21, Crash.versionCodeOf(report("1.15", 21)))
        assertEquals(25, Crash.versionCodeOf(report("1.19", 25)))
    }

    @Test fun `a report from another build is not from this one`() {
        assertFalse(Crash.isFromThisBuild(report("1.15", 21)))
        assertFalse(Crash.isFromThisBuild(report("9.99", 999)))
    }

    @Test fun `a malformed report is not attributed to this build`() {
        assertNull(Crash.versionCodeOf("no version here at all"))
        assertNull(Crash.versionCodeOf(""))
        assertNull(Crash.versionCodeOf("NavHUD 1.15 (twenty-one)"))
        assertFalse(Crash.isFromThisBuild("garbage"))
    }

    @Test fun `the summary keeps the header lines`() {
        val text = report("1.19", 25)
        val s = Crash.summary(text, 3)
        assertEquals(3, s.lines().size)
        assertEquals("NavHUD 1.19 (25)", s.lines().first())
    }
}
