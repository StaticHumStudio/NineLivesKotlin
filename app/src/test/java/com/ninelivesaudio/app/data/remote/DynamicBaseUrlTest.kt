package com.ninelivesaudio.app.data.remote

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DynamicBaseUrlTest {
    private fun routed(url: String): String {
        val client = OkHttpClient.Builder()
            .addInterceptor(DynamicBaseUrlInterceptor { "https://abs.example/base/" })
            .addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("fixture").body("".toResponseBody()).build()
            }.build()
        return client.newCall(Request.Builder().url(url).build()).execute().use { it.request.url.toString() }
    }

    @Test fun `exact placeholder preserves path and repeated query values`() {
        assertEquals("https://abs.example/base/api/items/a%20b?tag=x&tag=y", routed("http://localhost:80/api/items/a%20b?tag=x&tag=y"))
    }

    @Test fun `absolute localhost media with different origin is not rewritten`() {
        listOf("http://localhost:8080/audio/file.mp3", "https://localhost/audio/file.mp3")
            .forEach { assertEquals(it, routed(it)) }
    }

    @Test fun `frozen dispatch rejects a settings-only route switch before sending`() {
        val routeA = requireNotNull(ServerRoute.parse("https://abs.example/abs-a"))
        var calls = 0
        val client = OkHttpClient.Builder()
            .addInterceptor(DynamicBaseUrlInterceptor { "https://abs.example/abs-b" })
            .addInterceptor { chain ->
                calls++
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("fixture").body("".toResponseBody()).build()
            }.build()

        assertThrows(StaleRemoteRequestException::class.java) {
            client.newCall(
                Request.Builder().url("http://localhost/api/me")
                    .tag(RemoteDispatchTag::class.java, RemoteDispatchTag.noBearer(routeA))
                    .build()
            ).execute().close()
        }
        assertEquals(0, calls)
    }
}
