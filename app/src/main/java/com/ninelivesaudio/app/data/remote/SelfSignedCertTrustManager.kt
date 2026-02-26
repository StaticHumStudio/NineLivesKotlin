package com.ninelivesaudio.app.data.remote

import android.util.Log
import com.ninelivesaudio.app.service.SettingsManager
import okhttp3.OkHttpClient
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
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
 *    The permissive trust manager is never installed unless the user explicitly enables it.
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
    private const val TAG = "SelfSignedTrustManager"

    /**
     * Fingerprint staged during [checkServerTrusted] but not yet persisted.
     * Only committed to storage in the [hostnameVerifier] callback after the
     * actual peer hostname has been confirmed to match the configured server.
     * This prevents redirect/interception from poisoning the TOFU trust store.
     */
    @Volatile
    private var pendingTofuFingerprint: Pair<String, String>? = null // (host, fingerprint)

    class CertificateFingerprintMismatchException(
        val host: String,
        val expectedFingerprint: String,
        val actualFingerprint: String,
    ) : CertificateException(
        "TLS certificate fingerprint mismatch for $host. " +
            "Possible MITM attack or intentional server certificate rotation."
    )


    /**
     * Configures the OkHttpClient.Builder to accept self-signed certificates
     * ONLY for the server host from settings, and ONLY if the user opted in.
     *
     * When `allowSelfSignedCertificates` is `false` (the default), this method is a
     * no-op — the default OkHttp trust manager validates certificate chains against
     * the system CA store as normal.
     */
    fun OkHttpClient.Builder.configureSelfSignedCerts(
        settingsManager: SettingsManager,
    ): OkHttpClient.Builder {
        // Only install the permissive trust manager when the user has opted in.
        // Without this guard, the no-op checkServerTrusted() accepts every cert.
        val settings = settingsManager.currentSettings
        if (!settings.allowSelfSignedCertificates) {
            return this
        }

        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                if (chain.isNullOrEmpty()) {
                    throw CertificateException("Server certificate chain is empty")
                }

                chain.forEach { cert -> cert.checkValidity() }

                val serverUrl = settingsManager.currentSettings.serverUrl
                val configuredHost = try {
                    URI(serverUrl).host?.lowercase()
                } catch (_: Exception) {
                    null
                }

                if (configuredHost.isNullOrEmpty()) {
                    throw CertificateException("Configured server host is invalid")
                }

                val leafCert = chain.first()
                val fingerprint = leafCert.sha256Fingerprint()
                val trustedFingerprint = settingsManager.getTrustedCertificateFingerprint(configuredHost)

                if (trustedFingerprint == null) {
                    // Stage for enrollment — actual persistence happens in hostnameVerifier
                    // after the peer hostname is confirmed. This prevents a redirect or
                    // interception to a different host from poisoning the stored fingerprint.
                    pendingTofuFingerprint = configuredHost to fingerprint
                    Log.i(TAG, "TOFU staged fingerprint for host=$configuredHost (awaiting hostname verification)")
                    return
                }

                if (!fingerprint.equals(trustedFingerprint, ignoreCase = true)) {
                    Log.e(
                        TAG,
                        "TLS fingerprint mismatch for host=$configuredHost expected=$trustedFingerprint actual=$fingerprint"
                    )
                    throw CertificateFingerprintMismatchException(
                        host = configuredHost,
                        expectedFingerprint = trustedFingerprint,
                        actualFingerprint = fingerprint,
                    )
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())

        sslSocketFactory(sslContext.socketFactory, trustManager)

        hostnameVerifier { hostname, _ ->
            val currentSettings = settingsManager.currentSettings
            val serverUrl = currentSettings.serverUrl
            if (serverUrl.isEmpty()) return@hostnameVerifier false

            try {
                val configuredHost = URI(serverUrl).host
                val matches = hostname.equals(configuredHost, ignoreCase = true)

                // Commit the staged TOFU fingerprint now that hostname is confirmed.
                // If the hostname doesn't match, the pending fingerprint is discarded
                // so a redirect/interception can't poison the trust store.
                val pending = pendingTofuFingerprint
                if (pending != null) {
                    if (matches && pending.first.equals(configuredHost, ignoreCase = true)) {
                        settingsManager.saveTrustedCertificateFingerprint(pending.first, pending.second)
                        Log.i(TAG, "TOFU enrolled fingerprint for host=${pending.first} after hostname verification")
                    } else {
                        Log.w(TAG, "TOFU enrollment discarded: hostname mismatch (peer=$hostname, configured=$configuredHost)")
                    }
                    pendingTofuFingerprint = null
                }

                matches
            } catch (_: Exception) {
                pendingTofuFingerprint = null
                false
            }
        }

        return this
    }

    private fun X509Certificate.sha256Fingerprint(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(encoded)
        return digest.joinToString(separator = ":") { "%02X".format(it) }
    }
}
