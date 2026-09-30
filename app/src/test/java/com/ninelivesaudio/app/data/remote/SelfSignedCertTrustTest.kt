package com.ninelivesaudio.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.lang.reflect.Proxy
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSession
import javax.net.ssl.X509TrustManager

/**
 * The self-signed toggle used to be read once, when Hilt built the OkHttp
 * client, which is before settings load. So it was always off and flipping it
 * did nothing. The trust manager and hostname verifier are now always
 * installed and read the pref on every handshake.
 *
 * Off must be exactly the platform check. On keeps trust-on-first-use.
 */
class SelfSignedCertTrustTest {

    private var allow = false
    private val fingerprints = mutableMapOf<String, String>()
    private val tofu = TofuServerTrust(
        serverUrl = { "https://abs.local:13378" },
        trustedFingerprint = { fingerprints[it] },
        saveFingerprint = { host, fp -> fingerprints[host] = fp },
        log = { _, _ -> },
    )

    private val isConfiguredHost = { host: String -> host.equals("abs.local", ignoreCase = true) }

    private fun gatedTrust(platform: X509TrustManager = SelfSignedCertTrustManager.platformTrustManager()) =
        PrefGatedTrustManager(platform, { allow }, tofu, isConfiguredHost)

    private fun gatedVerifier(platform: HostnameVerifier) =
        PrefGatedHostnameVerifier(platform, { allow }, tofu, isConfiguredHost)

    // ─── Pref off: the platform decides, nothing else ─────────────────────

    @Test
    fun `off rejects a self-signed cert the platform rejects`() {
        allow = false
        try {
            gatedTrust().checkServerTrusted(arrayOf(certA), "RSA")
            fail("platform trust accepted a self-signed cert")
        } catch (_: CertificateException) {
        }
        assertTrue("off must never enroll a fingerprint", fingerprints.isEmpty())
    }

    @Test
    fun `off hands the chain to the platform trust manager`() {
        allow = false
        val platform = RecordingTrustManager()
        gatedTrust(platform).checkServerTrusted(arrayOf(certA), "RSA")
        assertEquals(1, platform.serverChecks)
    }

    @Test
    fun `off hostname verification is the platform verifier's answer`() {
        allow = false
        var consulted = 0
        val platform = HostnameVerifier { _, _ -> consulted++; false }
        // The host matches the configured server, which TOFU would accept.
        assertFalse(gatedVerifier(platform).verify("abs.local", session(certA)))
        assertEquals(1, consulted)
        assertTrue(fingerprints.isEmpty())
    }

    // ─── Pref on: trust on first use, as before ───────────────────────────

    @Test
    fun `on accepts first contact and enrolls from the verified session`() {
        allow = true
        val verifier = gatedVerifier { _, _ -> fail("platform verifier used while on"); false }
        gatedTrust().checkServerTrusted(arrayOf(certA), "RSA")
        assertNull(fingerprints["abs.local"])
        assertTrue(verifier.verify("abs.local", session(certA)))
        assertTrue(fingerprints["abs.local"] != null)

        // Same cert again passes, a different one is refused.
        gatedTrust().checkServerTrusted(arrayOf(certA), "RSA")
        try {
            gatedTrust().checkServerTrusted(arrayOf(certB), "RSA")
            fail("a rotated cert was accepted")
        } catch (e: SelfSignedCertTrustManager.CertificateFingerprintMismatchException) {
            assertEquals("abs.local", e.host)
        }
    }

    @Test
    fun `on refuses another host whose certificate the platform rejects`() {
        // Other hosts get the platform's answer, not TOFU, so a self-signed
        // certificate on any host but the configured server stays refused.
        allow = true
        val verifier = gatedVerifier { _, _ -> false }
        assertFalse(verifier.verify("evil.example", session(certA)))
        assertTrue(fingerprints.isEmpty())
    }

    @Test
    fun `flipping the pref takes effect on the next handshake`() {
        val trust = gatedTrust()
        allow = true
        trust.checkServerTrusted(arrayOf(certA), "RSA")
        allow = false
        try {
            trust.checkServerTrusted(arrayOf(certA), "RSA")
            fail("off still trusted a self-signed cert")
        } catch (e: CertificateException) {
            assertFalse(e is SelfSignedCertTrustManager.CertificateFingerprintMismatchException)
        }
    }

    // ─── Fixtures ─────────────────────────────────────────────────────────

    // ─── Pref on, but another host: the platform decides ──────────────────

    @Test
    fun `on hands a different host's hostname check to the platform`() {
        allow = true
        var consulted = 0
        val platform = HostnameVerifier { _, _ -> consulted++; true }
        assertTrue(gatedVerifier(platform).verify("cdn.example.com", session(certB)))
        assertEquals(1, consulted)
        assertTrue("another host must never enroll", fingerprints.isEmpty())
    }

    @Test
    fun `on hands a different host's chain to the platform trust manager`() {
        allow = true
        val platform = RecordingTrustManager()
        val engine = javax.net.ssl.SSLContext.getDefault().createSSLEngine("cdn.example.com", 443)
        gatedTrust(platform).checkServerTrusted(arrayOf(certB), "RSA", engine)
        assertEquals(1, platform.serverChecks)
    }

    @Test
    fun `on keeps trust on first use for the configured server's engine`() {
        allow = true
        val platform = RecordingTrustManager()
        val engine = javax.net.ssl.SSLContext.getDefault().createSSLEngine("abs.local", 13378)
        gatedTrust(platform).checkServerTrusted(arrayOf(certA), "RSA", engine)
        assertEquals("configured server must not reach the platform while on", 0, platform.serverChecks)
    }

    private class RecordingTrustManager : X509TrustManager {
        var serverChecks = 0
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            serverChecks++
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private fun session(leaf: X509Certificate): SSLSession =
        Proxy.newProxyInstance(
            SSLSession::class.java.classLoader,
            arrayOf(SSLSession::class.java),
        ) { _, method, _ ->
            if (method.name == "getPeerCertificates") arrayOf(leaf) else null
        } as SSLSession

    private fun parse(pem: String): X509Certificate =
        CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(pem.toByteArray())) as X509Certificate

    // Two throwaway self-signed certs for abs.local, valid for a century.
    private val certA = parse(
        """
-----BEGIN CERTIFICATE-----
MIIDITCCAgmgAwIBAgIUY7DYR9G8X1FVxBIffvHKCh/Wxs0wDQYJKoZIhvcNAQEL
BQAwFDESMBAGA1UEAwwJYWJzLmxvY2FsMCAXDTI2MDkzMDE1MTUwNFoYDzIxMjYw
OTA2MTUxNTA0WjAUMRIwEAYDVQQDDAlhYnMubG9jYWwwggEiMA0GCSqGSIb3DQEB
AQUAA4IBDwAwggEKAoIBAQC8WBXuIl1atgC1vz023dv5dr8nPZ2BacXKHMjwCdQu
ev3j8hnvNwahjs5c3XQfdlj6/OfjiianAnRjwk6Uy9wRuiz+jyzEWu+5sQXIylkC
ZD01xuwMPyUeu3WtWHWJ0PPJMMCwY5HmsFX5h9CImF3vzL/iDs6WDe37bz3rH1EL
0mM6Yjz30QPicBRo7Rx1m+ON5rds6XOhQgYk91br3CyN26Bs9EBCiTMpeM3UuRSn
+c09v0ot18rll7IpGv2r6n1QVEd5ZuAe/Ez+Hd6W4uYXzLyRwlVd5+sUSzI3tG3n
4negv0oZh6Mi8LrEUE98hpr6a7lMDGTBQX/ZY06tE/GvAgMBAAGjaTBnMB0GA1Ud
DgQWBBTWlonZoMqC8e8IQ836jcYxFCgB9DAfBgNVHSMEGDAWgBTWlonZoMqC8e8I
Q836jcYxFCgB9DAPBgNVHRMBAf8EBTADAQH/MBQGA1UdEQQNMAuCCWFicy5sb2Nh
bDANBgkqhkiG9w0BAQsFAAOCAQEAZKmXSBsUQrmODcuBS/SVODeSQRfln6ZUZ0DM
g5eEhM2YVUvd4saS+0F8XDaNXpBTF4FadKnFtrr+41UBOxCDhwrO5R55h6fLMu/f
KDs743Xpn2IgigakbXHmykH1IHYXLkiUN42PAaWIePZVk5mvO85jBySkUeX9Qqoh
v5O16RxEhbHiUMreI3uMxDbmhO7eoouu2jgQovsFmuGL6Va2UxM5UXBwQThek0cD
uibe0PQU56DbCZ9k8Eq7jZ/FM/oIgK/EZtwQlXLZghhAiffO7UANgxngkbF2OdOx
p6hD2l//jI7FJdtY/EO37Z+Hf0EydLDZgVAy3uK/b4jxtqa0Pg==
-----END CERTIFICATE-----
"""
    )
    private val certB = parse(
        """
-----BEGIN CERTIFICATE-----
MIIDITCCAgmgAwIBAgIUe8FlwdtcSY87wnKjLniPbUgWfEkwDQYJKoZIhvcNAQEL
BQAwFDESMBAGA1UEAwwJYWJzLmxvY2FsMCAXDTI2MDkzMDE1MTUwNFoYDzIxMjYw
OTA2MTUxNTA0WjAUMRIwEAYDVQQDDAlhYnMubG9jYWwwggEiMA0GCSqGSIb3DQEB
AQUAA4IBDwAwggEKAoIBAQCQK/c9BR5tsS04m11Q1yXoYOQvmBd0x1je9F8h4mDq
OaHwb+fPUF9lNpfMvOPKp+RWCInrx1FMIkTg2pjitFNFJtAHNvrQCcT2TQaKaEM+
28kpWEKasHRu/RuzH3rgypAYPocdyBCLHeb6FACW/6au1jHVJR/vrN37MJECsXxT
Eo3+19MC7OduLQJYrLr0SQdnnjUUhMjun74GcZUiB+asJdCnTKFmj/K0Enn0E5BM
3qa9/WJhORBh7Oz7/LbuvdbBQci9Tw27k7qZvSIw4k275FYhjUSiakZFPumW+Aoj
gWYJB7y45a0ADZvBbvQvTDCxl+qYy/OaJKMyodoxVj6tAgMBAAGjaTBnMB0GA1Ud
DgQWBBQIW7EUTneF8loAfry9OkXK9tQLxTAfBgNVHSMEGDAWgBQIW7EUTneF8loA
fry9OkXK9tQLxTAPBgNVHRMBAf8EBTADAQH/MBQGA1UdEQQNMAuCCWFicy5sb2Nh
bDANBgkqhkiG9w0BAQsFAAOCAQEAiNS5TrsnBHl7bxYrYslPVwP2oBd2i84GGmg0
Gkwd3HUOy6R3iHKciL0tBEwFqzoWuQqo/24FWUzSiWkvzDMUFNsXbvLhZanWrKcc
bB4Z7dXNYK534oHNh7HKDVbMFYJWCiJd4ZSkRfl0mtoakPfG0Qjibs3SvWbFnylI
VJh4fBArILd8gium7FknT4pY/WVx3jJCU41x81vX+lKJANOm2w62mOtYpakwTWHp
XlhEHzni3xcxAIBFU55tgfN26nQC+upQ4e5iQFH3ZLJhq1FPFbMeiE8UeaXTN+8z
IpB+oqHjOBILWwuDVxRYaR1fDrFnzmVmweG+IyjAZZD2WXGY+g==
-----END CERTIFICATE-----
"""
    )
}
