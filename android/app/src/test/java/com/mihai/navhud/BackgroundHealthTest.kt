package com.mihai.navhud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The manual checklist is content, not code, so what is worth pinning is that
 * it stays honest: the two OnePlus-specific traps must not quietly fall out of
 * it in a later edit, because they are the two nobody would guess.
 */
class BackgroundHealthTest {

    @Test
    fun `the checklist keeps the two non-obvious OnePlus steps`() {
        val all = BackgroundHealth.manualSteps.joinToString(" ").lowercase()
        assertTrue("locking in recents is what stops the phone reverting the battery setting",
            all.contains("lock"))
        assertTrue("deep optimisation overrides the per-app switch",
            all.contains("deep optimisation"))
        assertTrue("settings are reset by system updates", all.contains("update"))
    }

    @Test
    fun `every step names where to go`() {
        for (s in BackgroundHealth.manualSteps) {
            assertTrue("no path in: $s", s.contains("→") || s.contains("Re-check"))
        }
    }

    @Test
    fun `the checklist is short enough to be read in a car park`() {
        assertTrue(BackgroundHealth.manualSteps.size <= 8)
    }
}
