package com.ninelivesaudio.app.ui.changelog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangelogDataTest {

    @Test
    fun `badge appears only when the current version has not been seen`() {
        assertTrue(shouldShowChangelogBadge(currentVersion = "2.1.0", lastSeenVersion = ""))
        assertTrue(shouldShowChangelogBadge(currentVersion = "2.1.0", lastSeenVersion = "2.0.1"))
        assertFalse(shouldShowChangelogBadge(currentVersion = "2.1.0", lastSeenVersion = "2.1.0"))
        assertFalse(shouldShowChangelogBadge(currentVersion = "", lastSeenVersion = ""))
    }

    @Test
    fun `every release has a version and at least one complete section`() {
        ChangelogData.releases.forEach { release ->
            assertTrue(release.version.isNotBlank())
            assertTrue(release.dateLabel.isNotBlank())
            val sections = release.sections
            assertTrue(sections.isNotEmpty())
            sections.forEach { section ->
                assertTrue(section.header in setOf("New", "Improved", "Fixed"))
                val entries = section.entries
                assertTrue(entries.isNotEmpty())
                entries.forEach { entry ->
                    assertTrue(entry.isNotBlank())
                }
            }
        }
    }

    @Test
    fun `every changelog sentence avoids banned punctuation`() {
        val banned = setOf('\u2014', '\u2013', '\u003b')

        assertFalse(ChangelogData.ARCHIVE_SUBTITLE.any(banned::contains))

        ChangelogData.releases.forEach { release ->
            assertFalse(release.version.any(banned::contains))
            assertFalse(release.dateLabel.any(banned::contains))
            release.sections.forEach { section ->
                assertFalse(section.header.any(banned::contains))
                section.entries.forEach { entry ->
                    assertFalse(entry.any(banned::contains))
                }
            }
        }
    }

    @Test
    fun `releases are newest first and omit the unshipped version`() {
        assertEquals(
            listOf("2.1.0", "2.0.1", "2.0.0", "1.0.1", "1.0", "0.95", "0.9", "0.6"),
            ChangelogData.releases.map { it.version },
        )
    }
}
