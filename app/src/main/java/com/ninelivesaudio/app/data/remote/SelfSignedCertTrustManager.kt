package com.ninelivesaudio.app.data.remote

import android.util.Log
import com.ninelivesaudio.app.service.SettingsManager
import okhttp3.OkHttpClient
import okhttp3.internal.tls.OkHostnameVerifier
import java.net.Socket
import java.net.URI
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/**
 * Provides scoped SSL bypass for self-hosted Audiobookshelf servers.
 *
 * ## Security Trade-off
 *
 * Many Audiobookshelf users run self-hosted servers on their LAN with self-signed
 * certificates. Without this bypass, those users cannot connect at all. This is an
 * accepted trade-off for a self-hosted server client.
 *
 * ## Safeguards
 *
 * 1. **Opt-in only:** `allowSelfSignedCertificates` defaults to `false` in [AppSettings].
 *    The gate is always installed but reads the pref per handshake. While it is off,
 *    every check goes to the platform trust manager and OkHttp's default verifier.
 * 2. **Host-scoped:** The custom [javax.net.ssl.HostnameVerifier] restricts certificate
 *    acceptance to the single hostname extracted from the configured server URL. Requests
 *    to any other host use standard certificate validation.
 * 3. **No MITM for third parties:** Only the configured Audiobookshelf server is affected.
 *    All other HTTPS connections (analytics, CDNs, etc.) use the system CA store.
 *
 * ## TOFU Protection
 *
 * With self-signed cert opt-in enabled, the app stores a SHA-256 fingerprint for the
 * configured host on first successful handshake and rejects future mismatches.
 */
object SelfSignedCertTrustManager {
    class CertificateFingerprintMismatchException(
        val host: String,
        val expectedFingerprint: String,
        val actualFingerprint: String,
    ) : CertificateException(
        "TLS certificate fingerprint mismatch for $host. " +
            "Possible MITM attack or intentional server certificate rotation."
    )


    /**
     * Installs the pref-gated trust manager and hostname verifier on the client.
     *
     * Always installed, because Hilt builds this client before settings load, so
     * a check made here would always see the default (off) and flipping the
     * toggle would never take effect. Both read `allowSelfSignedCertificates`
     * on every handshake instead. Off, they hand straight to the platform trust
     * manager and OkHttp's default hostname verifier, so the default path is the
     * same check OkHttp would do on its own. On, trust-on-first-use applies.
     */
    fun OkHttpClient.Builder.configureSelfSignedCerts(
        settingsManager: SettingsManager,
    ): OkHttpClient.Builder {
        val allowSelfSigned = { settingsManager.currentSettings.allowSelfSignedCertificates }
        val tofu = TofuServerTrust(
            serverUrl = { settingsManager.currentSettings.serverUrl },
            trustedFingerprint = settingsManager::getTrustedCertificateFingerprint,
            saveFingerprint = settingsManager::saveTrustedCertificateFingerprint,
        )

        val isConfiguredHost = { host: String ->
            val configured = runCatching { URI(settingsManager.currentSettings.serverUrl).host }.getOrNull()
            configured != null && host.equals(configured, ignoreCase = true)
        }
        val trustManager = PrefGatedTrustManager(platformTrustManager(), allowSelfSigned, tofu, isConfiguredHost)
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())

        sslSocketFactory(sslContext.socketFactory, trustManager)
        hostnameVerifier(PrefGatedHostnameVerifier(OkHostnameVerifier, allowSelfSigned, tofu, isConfiguredHost))

        return this
    }

    /** The platform's default trust manager, the same one OkHttp uses by default. */
    internal fun platformTrustManager(): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }
}

/**
 * Off: the platform trust manager, every overload forwarded as is. The socket
 * and engine forms matter on Android, where the Network Security Config trust
 * manager needs the peer host they carry. On: trust-on-first-use, but only for
 * the configured server. Any other host (a CDN or proxy an absolute playback
 * URL points at) keeps the platform check, since the pinned fingerprint is the
 * configured server's and would reject a valid certificate elsewhere.
 */
internal class PrefGatedTrustManager(
    private val platform: X509TrustManager,
    private val allowSelfSigned: () -> Boolean,
    private val tofu: X509TrustManager,
    private val isConfiguredHost: (String) -> Boolean = { true },
) : X509ExtendedTrustManager() {

    // The plain overloads carry no peer host, so they keep the configured
    // server's TOFU behavior. Android's TLS stack calls the socket and engine
    // forms, which do carry it.
    private fun tofuFor(peerHost: String?): Boolean =
        allowSelfSigned() && (peerHost == null || isConfiguredHost(peerHost))

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        if (allowSelfSigned()) tofu.checkClientTrusted(chain, authType)
        else platform.checkClientTrusted(chain, authType)
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {
        if (allowSelfSigned()) tofu.checkClientTrusted(chain, authType)
        else if (platform is X509ExtendedTrustManager) platform.checkClientTrusted(chain, authType, socket)
        else platform.checkClientTrusted(chain, authType)
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {
        if (allowSelfSigned()) tofu.checkClientTrusted(chain, authType)
        else if (platform is X509ExtendedTrustManager) platform.checkClientTrusted(chain, authType, engine)
        else platform.checkClientTrusted(chain, authType)
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        if (allowSelfSigned()) tofu.checkServerTrusted(chain, authType)
        else platform.checkServerTrusted(chain, authType)
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {
        if (tofuFor((socket as? SSLSocket)?.handshakeSession?.peerHost)) tofu.checkServerTrusted(chain, authType)
        else if (platform is X509ExtendedTrustManager) platform.checkServerTrusted(chain, authType, socket)
        else platform.checkServerTrusted(chain, authType)
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {
        if (tofuFor(engine?.peerHost)) tofu.checkServerTrusted(chain, authType)
        else if (platform is X509ExtendedTrustManager) platform.checkServerTrusted(chain, authType, engine)
        else platform.checkServerTrusted(chain, authType)
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> =
        if (allowSelfSigned()) tofu.acceptedIssuers else platform.acceptedIssuers
}

/** Off, or any host but the configured server: the platform verifier. On for the server: TOFU enrollment. */
internal class PrefGatedHostnameVerifier(
    private val platform: HostnameVerifier,
    private val allowSelfSigned: () -> Boolean,
    private val tofu: HostnameVerifier,
    private val isConfiguredHost: (String) -> Boolean = { true },
) : HostnameVerifier {
    override fun verify(hostname: String, session: SSLSession): Boolean =
        if (allowSelfSigned() && isConfiguredHost(hostname)) tofu.verify(hostname, session)
        else platform.verify(hostname, session)
}

/**
 * Trust-on-first-use for the configured server, used only while the user has
 * opted in. Unchanged from when it was built inline in configureSelfSignedCerts.
 */
internal class TofuServerTrust(
    private val serverUrl: () -> String,
    private val trustedFingerprint: (String) -> String?,
    private val saveFingerprint: (String, String) -> Unit,
    private val log: (Int, String) -> Unit = { priority, message -> Log.println(priority, TAG, message) },
) : X509TrustManager, HostnameVerifier {

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("Client certificates not supported")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        if (chain.isNullOrEmpty()) {
            throw CertificateException("Server certificate chain is empty")
        }

        chain.forEach { cert -> cert.checkValidity() }

        val configuredHost = try {
            URI(serverUrl()).host?.lowercase()
        } catch (_: Exception) {
            null
        }

        if (configuredHost.isNullOrEmpty()) {
            throw CertificateException("Configured server host is invalid")
        }

        val leafCert = chain.first()
        val fingerprint = leafCert.sha256Fingerprint()
        val trusted = trustedFingerprint(configuredHost)

        if (trusted == null) {
            // First-time connection: accept here. Enrollment happens in verify()
            // from this session's own peer certificate, so it is bound to the
            // verified hostname and the actual handshake — no shared
            // cross-connection state to race or poison.
            log(Log.INFO, "TOFU first contact for host=$configuredHost (enrollment pending hostname verification)")
            return
        }

        if (!fingerprint.equals(trusted, ignoreCase = true)) {
            log(Log.ERROR, "TLS fingerprint mismatch for host=$configuredHost")
            throw SelfSignedCertTrustManager.CertificateFingerprintMismatchException(
                host = configuredHost,
                expectedFingerprint = trusted,
                actualFingerprint = fingerprint,
            )
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    override fun verify(hostname: String, session: SSLSession): Boolean {
        val url = serverUrl()
        if (url.isEmpty()) return false

        return try {
            val configuredHost = URI(url).host
            val matches = hostname.equals(configuredHost, ignoreCase = true)
            if (!matches) return false

            // Enroll TOFU here, from THIS session's own peer certificate, only
            // if nothing is stored yet. Deriving the fingerprint from the
            // verified session (rather than a shared field set during
            // checkServerTrusted) ties it to this exact handshake and hostname,
            // so concurrent first-time handshakes can't enroll each other's cert.
            // Wrapped separately so an enrollment hiccup never blocks a valid connection.
            try {
                val normalizedHost = configuredHost?.lowercase()
                if (!normalizedHost.isNullOrEmpty() && trustedFingerprint(normalizedHost) == null) {
                    val leaf = session.peerCertificates.firstOrNull() as? X509Certificate
                    if (leaf != null) {
                        saveFingerprint(normalizedHost, leaf.sha256Fingerprint())
                        log(Log.INFO, "TOFU enrolled fingerprint for host=$normalizedHost from verified session")
                    }
                }
            } catch (e: Exception) {
                log(Log.WARN, "TOFU enrollment skipped: ${e.message}")
            }

            true
        } catch (_: Exception) {
            false
        }
    }

    private companion object {
        const val TAG = "SelfSignedTrustManager"
    }
}

private fun X509Certificate.sha256Fingerprint(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(encoded)
    return digest.joinToString(separator = ":") { "%02X".format(it) }
}
