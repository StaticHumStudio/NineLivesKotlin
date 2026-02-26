package com.ninelivesaudio.app.service

import android.os.Process
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackServiceControllerTrustTest {

    private val appPackage = "com.ninelivesaudio.app"

    @Test
    fun `allows own app package when uid matches`() {
        val trusted = PlaybackService.isTrustedController(
            appPackageName = appPackage,
            controllerPackageName = appPackage,
            controllerUid = 20001,
        ) { pkg, uid -> pkg == appPackage && uid == 20001 }

        assertTrue(trusted)
    }

    @Test
    fun `allows android auto package when uid matches`() {
        val trusted = PlaybackService.isTrustedController(
            appPackageName = appPackage,
            controllerPackageName = "com.google.android.projection.gearhead",
            controllerUid = 20002,
        ) { pkg, uid -> pkg == "com.google.android.projection.gearhead" && uid == 20002 }

        assertTrue(trusted)
    }

    @Test
    fun `allows system uid controller when package is owned by uid`() {
        val trusted = PlaybackService.isTrustedController(
            appPackageName = appPackage,
            controllerPackageName = "android",
            controllerUid = Process.SYSTEM_UID,
        ) { pkg, uid -> pkg == "android" && uid == Process.SYSTEM_UID }

        assertTrue(trusted)
    }

    @Test
    fun `rejects controller when package uid ownership fails`() {
        val trusted = PlaybackService.isTrustedController(
            appPackageName = appPackage,
            controllerPackageName = appPackage,
            controllerUid = 20001,
        ) { _, _ -> false }

        assertFalse(trusted)
    }

    @Test
    fun `rejects unknown package even when uid mapping matches`() {
        val trusted = PlaybackService.isTrustedController(
            appPackageName = appPackage,
            controllerPackageName = "com.evil.controller",
            controllerUid = 20003,
        ) { pkg, uid -> pkg == "com.evil.controller" && uid == 20003 }

        assertFalse(trusted)
    }
}
