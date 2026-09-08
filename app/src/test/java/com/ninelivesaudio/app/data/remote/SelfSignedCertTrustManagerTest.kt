package com.ninelivesaudio.app.data.remote

import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class SelfSignedCertTrustManagerTest {
    private val certificate = CertificateFactory.getInstance("X.509")
        .generateCertificate(ByteArrayInputStream(TEST_CERTIFICATE.toByteArray())) as X509Certificate

    @Test
    fun currentCommittedHostUsesTofuButBlankAndOtherHostsUsePlatformTrust() {
        var currentHost: String? = null
        val platform = RecordingTrustManager()
        val trusted = mutableMapOf<String, String>()
        val manager = trustManager(currentHost = { currentHost }, platform = platform, trusted = trusted)

        manager.checkServerTrustedForHost(
            arrayOf(certificate), "RSA", "127.0.0.1",
            SelfSignedCertTrustManager.HostAwareTrustCall.SOCKET,
        ) { platform.socketChecks++ }
        assertEquals(1, platform.socketChecks, "blank startup route must not trust a self-signed host")

        currentHost = "127.0.0.1"
        manager.checkServerTrustedForHost(
            arrayOf(certificate), "RSA", "127.0.0.1",
            SelfSignedCertTrustManager.HostAwareTrustCall.SOCKET,
        ) { platform.socketChecks++ }
        assertEquals(1, platform.socketChecks, "the current committed login route receives TOFU")

        currentHost = "cdn.example.test"
        manager.checkServerTrustedForHost(
            arrayOf(certificate), "RSA", "127.0.0.1",
            SelfSignedCertTrustManager.HostAwareTrustCall.ENGINE,
        ) { platform.engineChecks++ }
        assertEquals(1, platform.engineChecks, "a stale A-to-B route must return to platform trust")

        manager.checkServerTrusted(arrayOf(certificate), "RSA")
        assertEquals(1, platform.hostlessChecks, "hostless checks fail closed through platform trust")
    }

    @Test
    fun changedPinnedFingerprintStillFailsForCurrentCommittedHost() {
        val platform = RecordingTrustManager()
        val manager = trustManager(
            currentHost = { "127.0.0.1" },
            platform = platform,
            trusted = mutableMapOf("127.0.0.1" to "00:11"),
        )

        try {
            manager.checkServerTrustedForHost(
                arrayOf(certificate), "RSA", "127.0.0.1",
                SelfSignedCertTrustManager.HostAwareTrustCall.SOCKET,
            ) { platform.socketChecks++ }
            fail("A changed fingerprint must be rejected")
        } catch (_: SelfSignedCertTrustManager.CertificateFingerprintMismatchException) {
            // Expected.
        }
        assertEquals(0, platform.socketChecks)
    }

    private fun trustManager(
        currentHost: () -> String?,
        platform: RecordingTrustManager,
        trusted: MutableMap<String, String>,
    ) = SelfSignedCertTrustManager.HostScopedTrustManager(
        platformTrustManager = platform,
        configuredHost = currentHost,
        trustedFingerprint = trusted::get,
        saveFingerprint = { host, fingerprint -> trusted[host] = fingerprint },
    )

    private class RecordingTrustManager : X509TrustManager {
        var socketChecks = 0
        var engineChecks = 0
        var hostlessChecks = 0

        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
            hostlessChecks++
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private companion object {
        private const val TEST_CERTIFICATE = """-----BEGIN CERTIFICATE-----
MIIDGjCCAgKgAwIBAgIULryecn79i5imHE2ucIRaso+t9HgwDQYJKoZIhvcNAQEL
BQAwFDESMBAGA1UEAwwJMTI3LjAuMC4xMB4XDTI2MDkwODEzNDQwNFoXDTI2MDkx
MDEzNDQwNFowFDESMBAGA1UEAwwJMTI3LjAuMC4xMIIBIjANBgkqhkiG9w0BAQEF
AAOCAQ8AMIIBCgKCAQEAwES75NpWMaeZWK26oMkbxqCGlfRfgT8KzQO6W+LF51NM
Uiql5n1Bv2Z+c+hGxHdgYs0pjjZaQtox2catsOezY0iYTJa+MNtT8CiUMzKQ4bS4
sYRrZOKfJx4XN8Y1KDYh4P5/HTeRcu90+Ij21/P3BZI6Nf1uGCz0CGYzj2/BH0Ev
5KuGbPLaALnjnUZKyBPyU4PUW7u6CfJ3ZmDSlMkBHnda7oeYts1ISrrx6lH9y7gx
pr/Qg6aR2TjwA1i9azbwjamkFudOq4/xqr0Dab9MZPE0GpZxCzsV5LGp3+vhDeLw
bZgYc9lQqvJ6jxG3xrvm/jFokvpoxMpGIp0AzKEm8QIDAQABo2QwYjAdBgNVHQ4E
FgQUuG1+6ieEvRox34QuszxlRoaDZOswHwYDVR0jBBgwFoAUuG1+6ieEvRox34Qu
szxlRoaDZOswDwYDVR0TAQH/BAUwAwEB/zAPBgNVHREECDAGhwR/AAABMA0GCSqG
SIb3DQEBCwUAA4IBAQBMQN+2KFvidScxOyW5GOwkvm1YPQSJAbigB6yBmT98hHsG
/QWM6fcf9txeMMUGy7O/VnZ5M3ECH/1JzIQmk5dd/TBjJl7L5h3FyeLu7thowL3J
XucSUMNAIzCw5RaVwhmp31NimvtNOxzNpwf4JDXoXfx4aFguNOlViHr1++vMnGZV
CtB0yvaH+98P6lknRVYu04SqOodc3tDRZAmcRjSn8fI2qrc9IKvvKPIL3pLOQBhe
xnWkdZ8n4wb5n1nq3KRMIT4nRBtV3VEVcDNvJqAWSwei+qxipA9vCkjgxD2LRLLm
T41euBz3fBzs2LJ+iDUBE+xffUOQYjmJM6e3fXtE
-----END CERTIFICATE-----"""
    }
}
