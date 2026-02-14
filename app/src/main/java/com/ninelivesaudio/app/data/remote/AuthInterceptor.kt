package com.ninelivesaudio.app.data.remote

import com.ninelivesaudio.app.service.SettingsManager
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OkHttp interceptor that injects the Bearer auth token into every request.
 * Token is read synchronously from EncryptedSharedPreferences via SettingsManager.
 */
@Singleton
class AuthInterceptor @Inject constructor(
    private val settingsManager: SettingsManager,
) : Interceptor {

    @Volatile
    private var cachedToken: String? = null

    fun setToken(token: String?) {
        cachedToken = token
    }

    fun hasToken(): Boolean = !cachedToken.isNullOrEmpty()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        if (!request.header("Authorization").isNullOrBlank()) {
            return chain.proceed(request)
        }

        val token = cachedToken
        if (token.isNullOrEmpty()) {
            return chain.proceed(request)
        }

        val authenticatedRequest = request.newBuilder()
            .header("Authorization", "Bearer $token")
            .build()

        return chain.proceed(authenticatedRequest)
    }
}
