package com.ninelivesaudio.app.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalGuideStateTest {

    @Test
    fun `guide starts expanded when no local libraries exist`() {
        assertTrue(localGuideStartsExpanded(hasLocalLibraries = false))
    }

    @Test
    fun `guide starts collapsed once a library exists`() {
        assertFalse(localGuideStartsExpanded(hasLocalLibraries = true))
    }

    @Test
    fun `server guide starts expanded when not connected`() {
        assertTrue(serverGuideStartsExpanded(isConnected = false))
    }

    @Test
    fun `server guide starts collapsed when connected`() {
        assertFalse(serverGuideStartsExpanded(isConnected = true))
    }
}
