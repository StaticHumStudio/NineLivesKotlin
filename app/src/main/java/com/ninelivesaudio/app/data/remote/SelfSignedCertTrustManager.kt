package com.ninelivesaudio.app.data.remote

import android.util.Log
import com.ninelivesaudio.app.service.SettingsManager
import okhttp3.OkHttpClient
import java.net.Socket
import java.net.URI
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/**
 * Provides opt-in, host-scoped trust on self-hosted Audiobookshelf servers.
 *
 * The opt-in is snapshotted when the process singleton OkHttp client is built.
 * The selected server host is resolved at each host-aware TLS handshake because
 * the first authorized login route is not durable until that login begins.
 */
object SelfSignedCertTrustManager {
    private const val TAG = "SelfSignedTrustManager"

    class CertificateFingerprintMismatchException(
        val host: String,
        val expectedFingerprint: String,
        val actualFingerprint: String,
    ) : CertificateException(
        "TLS certificate fingerprint mismatch for $host. " +
            "Possible MITM attack or intentional server certificate rotation."
    )

    internal enum class HostAwareTrustCall { SOCKET, ENGINE }

    /**
     * Configures the process client from a durable opt-in read. Updating the
     * setting later does not mutate this client's TLS policy.
     */
    internal fun OkHttpClient.Builder.configureSelfSignedCerts(
        settingsManager: SettingsManager,
        onHostAwareTrustCheck: ((HostAwareTrustCall, String?) -> Unit)? = null,
    ): OkHttpClient.Builder {
        if (!settingsManager.readPersistedAllowSelfSignedCertificates()) return this

        val platformTrustManager = platformTrustManager()
        val trustManager = HostScopedTrustManager(
            platformTrustManager = platformTrustManager,
            configuredHost = { settingsManager.currentSettings.serverUrl.toNormalizedHost() },
            trustedFingerprint = settingsManager::getTrustedCertificateFingerprint,
            saveFingerprint = settingsManager::saveTrustedCertificateFingerprint,
            onHostAwareTrustCheck = onHostAwareTrustCheck,
        )
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())

        sslSocketFactory(sslContext.socketFactory, trustManager)
        hostnameVerifier(hostnameVerifierFor(trustManager, settingsManager))
        return this
    }

    internal fun hostnameVerifierFor(
        trustManager: HostScopedTrustManager,
        settingsManager: SettingsManager,
    ) = javax.net.ssl.HostnameVerifier { hostname, session ->
        val normalizedHost = hostname.normalizeHost()
        if (normalizedHost == null || normalizedHost != settingsManager.currentSettings.serverUrl.toNormalizedHost()) {
            HttpsURLConnection.getDefaultHostnameVerifier().verify(hostname, session)
        } else {
            try {
                val leaf = session.peerCertificates.firstOrNull() as? X509Certificate
                if (leaf == null) false else {
                    trustManager.enrollFingerprintIfNeeded(normalizedHost, leaf)
                    true
                }
            } catch (e: Exception) {
                Log.w(TAG, "TOFU enrollment skipped: ${e.message}")
                true
            }
        }
    }

    internal class HostScopedTrustManager(
        private val platformTrustManager: X509TrustManager,
        private val configuredHost: () -> String?,
        private val trustedFingerprint: (String) -> String?,
        private val saveFingerprint: (String, String) -> Unit,
        private val onHostAwareTrustCheck: ((HostAwareTrustCall, String?) -> Unit)? = null,
    ) : X509ExtendedTrustManager() {
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
            platformTrustManager.checkClientTrusted(chain, authType)
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String, socket: Socket) {
            if (platformTrustManager is X509ExtendedTrustManager) {
                platformTrustManager.checkClientTrusted(chain, authType, socket)
            } else {
                platformTrustManager.checkClientTrusted(chain, authType)
            }
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String, engine: SSLEngine) {
            if (platformTrustManager is X509ExtendedTrustManager) {
                platformTrustManager.checkClientTrusted(chain, authType, engine)
            } else {
                platformTrustManager.checkClientTrusted(chain, authType)
            }
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
            // A hostless check must not receive TOFU trust. The platform manager
            // rejects an untrusted chain here, including self-signed certificates.
            platformTrustManager.checkServerTrusted(chain, authType)
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String, socket: Socket) {
            checkServerTrustedForHost(
                chain = chain,
                authType = authType,
                peerHost = socket.peerHost(),
                call = HostAwareTrustCall.SOCKET,
                platformCheck = {
                    if (platformTrustManager is X509ExtendedTrustManager) {
                        platformTrustManager.checkServerTrusted(chain, authType, socket)
                    } else {
                        platformTrustManager.checkServerTrusted(chain, authType)
                    }
                },
            )
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String, engine: SSLEngine) {
            checkServerTrustedForHost(
                chain = chain,
                authType = authType,
                peerHost = engine.handshakeSession?.peerHost,
                call = HostAwareTrustCall.ENGINE,
                platformCheck = {
                    if (platformTrustManager is X509ExtendedTrustManager) {
                        platformTrustManager.checkServerTrusted(chain, authType, engine)
                    } else {
                        platformTrustManager.checkServerTrusted(chain, authType)
                    }
                },
            )
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = platformTrustManager.acceptedIssuers

        internal fun enrollFingerprintIfNeeded(host: String, leaf: X509Certificate) {
            if (trustedFingerprint(host) == null) {
                saveFingerprint(host, leaf.sha256Fingerprint())
                Log.i(TAG, "TOFU enrolled fingerprint for host=$host from verified session")
            }
        }

        internal fun checkServerTrustedForHost(
            chain: Array<out X509Certificate>,
            authType: String,
            peerHost: String?,
            call: HostAwareTrustCall,
            platformCheck: () -> Unit,
        ) {
            val normalizedPeerHost = peerHost.normalizeHost()
            onHostAwareTrustCheck?.invoke(call, normalizedPeerHost)
            if (normalizedPeerHost == null || normalizedPeerHost != configuredHost()) {
                platformCheck()
                return
            }

            if (chain.isEmpty()) throw CertificateException("Server certificate chain is empty")
            chain.forEach { it.checkValidity() }

            val fingerprint = chain.first().sha256Fingerprint()
            val trusted = trustedFingerprint(normalizedPeerHost)
            if (trusted == null) {
                Log.i(TAG, "TOFU first contact for host=$normalizedPeerHost (enrollment pending hostname verification)")
                return
            }
            if (!fingerprint.equals(trusted, ignoreCase = true)) {
                Log.e(TAG, "TLS fingerprint mismatch for host=$normalizedPeerHost expected=$trusted actual=$fingerprint")
                throw CertificateFingerprintMismatchException(normalizedPeerHost, trusted, fingerprint)
            }
        }

    }

    private fun platformTrustManager(): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
            ?: throw IllegalStateException("Platform X509 trust manager is unavailable")
    }

    private fun Socket.peerHost(): String? =
        (this as? SSLSocket)?.handshakeSession?.peerHost ?: (this as? SSLSocket)?.session?.peerHost

    private fun String?.toNormalizedHost(): String? = try {
        this?.takeIf(String::isNotBlank)?.let { URI(it).host.normalizeHost() }
    } catch (_: Exception) {
        null
    }

    private fun String?.normalizeHost(): String? =
        this?.trim()?.takeIf(String::isNotEmpty)?.lowercase(Locale.ROOT)

    private fun X509Certificate.sha256Fingerprint(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(encoded)
        return digest.joinToString(separator = ":") { "%02X".format(it) }
    }
}
