package com.ninelivesaudio.app.service

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@UnstableApi
@RunWith(AndroidJUnit4::class)
class PlaybackDataSourceFactoryTest {

    @Test
    fun playbackDataSourceUsesAuthenticatedOkHttpClient() {
        val authorization = CompletableFuture<String?>()
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val serverThread = thread(name = "playback-auth-test-server") {
                server.accept().use { socket ->
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                    var authorizationHeader: String? = null
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Authorization:", ignoreCase = true)) {
                            authorizationHeader = line.substringAfter(':').trim()
                        }
                    }
                    authorization.complete(authorizationHeader)
                    socket.getOutputStream().use { output ->
                        output.write(
                            "HTTP/1.1 200 OK\r\nContent-Length: 1\r\nConnection: close\r\n\r\nx"
                                .toByteArray(),
                        )
                    }
                }
            }

            val client = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    chain.proceed(
                        chain.request().newBuilder()
                            .header("Authorization", "Bearer test-token")
                            .build(),
                    )
                }
                .build()
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val dataSource = createPlaybackDataSourceFactory(context, client).createDataSource()

            try {
                dataSource.open(
                    DataSpec.Builder()
                        .setUri(Uri.parse("http://127.0.0.1:${server.localPort}/audio"))
                        .build(),
                )
                assertEquals("Bearer test-token", authorization.get(5, TimeUnit.SECONDS))
            } finally {
                dataSource.close()
                serverThread.join(5_000)
            }
        }
    }
}
