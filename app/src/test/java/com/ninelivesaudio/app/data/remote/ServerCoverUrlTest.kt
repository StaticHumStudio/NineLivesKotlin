package com.ninelivesaudio.app.data.remote

import com.ninelivesaudio.app.ui.components.THUMBNAIL_COVER_WIDTH_PX
import com.ninelivesaudio.app.ui.components.thumbnailCoverUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Book rows store absolute cover URLs with the syncing server's host. After a
 * switch between a LAN and a VPN address every cover pointed at the old host
 * and failed until a full resync. Cover requests now go to the current server.
 */
class ServerCoverUrlTest {

    private var server = "https://vpn.example/abs"

    private fun sent(url: String): String {
        val client = OkHttpClient.Builder()
            .addInterceptor(DynamicBaseUrlInterceptor { server })
            .addInterceptor(ServerCoverUrlInterceptor { server })
            .addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("fixture").body("".toResponseBody()).build()
            }.build()
        return client.newCall(Request.Builder().url(url).build()).execute().use { it.request.url.toString() }
    }

    @Test fun `a cover stored with the LAN address goes to the current VPN address`() {
        assertEquals(
            "https://vpn.example/abs/api/items/li_1/cover",
            sent("http://192.168.1.5:13378/api/items/li_1/cover"),
        )
    }

    @Test fun `switching back to the LAN address follows too`() {
        server = "http://192.168.1.5:13378"
        assertEquals(
            "http://192.168.1.5:13378/api/items/li_1/cover",
            sent("https://vpn.example/abs/api/items/li_1/cover"),
        )
    }

    @Test fun `the list row width survives the move`() {
        val stored = thumbnailCoverUrl("http://192.168.1.5:13378/api/items/li_1/cover", THUMBNAIL_COVER_WIDTH_PX)
        assertEquals("https://vpn.example/abs/api/items/li_1/cover?width=240", sent(stored))
    }

    @Test fun `the Android Auto width survives the move`() {
        assertEquals(
            "https://vpn.example/abs/api/items/li_1/cover?width=256",
            sent("http://192.168.1.5:13378/api/items/li_1/cover?width=256"),
        )
    }

    @Test fun `an encoded item id stays encoded`() {
        assertEquals(
            "https://vpn.example/abs/api/items/a%20b/cover",
            sent("http://192.168.1.5:13378/api/items/a%20b/cover"),
        )
    }

    @Test fun `a cover already on the current server is untouched`() {
        val url = "https://vpn.example/abs/api/items/li_1/cover?width=240"
        assertEquals(url, sent(url))
    }

    @Test fun `the retrofit cover call still routes through the placeholder`() {
        assertEquals("https://vpn.example/abs/api/items/li_1/cover", sent("http://localhost/api/items/li_1/cover"))
    }

    @Test fun `audio files and other calls are never rewritten`() {
        listOf(
            "http://192.168.1.5:13378/api/items/li_1/file/3",
            "http://192.168.1.5:13378/api/items/li_1",
            "http://192.168.1.5:13378/cover",
            "https://images.example/covers/li_1.jpg",
        ).forEach { assertEquals(it, sent(it)) }
    }

    @Test fun `no configured server leaves the request alone`() {
        server = ""
        val url = "http://192.168.1.5:13378/api/items/li_1/cover"
        assertEquals(url, sent(url))
    }
}
