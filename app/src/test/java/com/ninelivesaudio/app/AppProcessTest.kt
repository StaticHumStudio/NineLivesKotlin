package com.ninelivesaudio.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The crash dialog runs in its own ":acra" process, and Android runs
 * Application.onCreate there too. Widgets, WorkManager, billing, sync and
 * playback only exist in the app's main process, and starting them in the
 * crash process crashed the dialog that was meant to report the first crash.
 */
class AppProcessTest {

    private val pkg = "com.ninelivesaudio.app"

    @Test fun `the app's own process is the main process`() {
        assertTrue(isMainAppProcess(processName = pkg, packageName = pkg))
    }

    @Test fun `the debug build's own process is the main process`() {
        assertTrue(isMainAppProcess(processName = "$pkg.debug", packageName = "$pkg.debug"))
    }

    @Test fun `the crash dialog process is not`() {
        assertFalse(isMainAppProcess(processName = "$pkg:acra", packageName = pkg))
        assertFalse(isMainAppProcess(processName = "$pkg.debug:acra", packageName = "$pkg.debug"))
    }

    @Test fun `no other side process is either`() {
        assertFalse(isMainAppProcess(processName = "$pkg:remote", packageName = pkg))
    }

    @Test fun `an unknown process name keeps the app working`() {
        // Better to start everything than to boot an app with nothing running.
        assertTrue(isMainAppProcess(processName = null, packageName = pkg))
    }
}
