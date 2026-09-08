package com.ninelivesaudio.app.data.remote

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

class AuthOriginTest {
    private fun withOrigins(block: (HttpServer, HttpServer) -> Unit) {
        val a = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val b = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        try { a.start(); b.start(); block(a, b) } finally { a.stop(0); b.stop(0) }
    }

    private fun HttpServer.url(path: String = "/") = "http://127.0.0.1:${address.port}$path"
    private fun HttpServer.capture(path: String = "/", onRequest: (String?) -> Unit) {
        createContext(path) { exchange ->
            onRequest(exchange.requestHeaders.getFirst("Authorization"))
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
    }

    @Test fun `cached bearer is sent only to its origin including effective port`() = withOrigins { a, b ->
        val auth = AuthInterceptor().apply { setToken("fixture-a", a.url()) }
        val client = OkHttpClient.Builder().addNetworkInterceptor(auth).build()
        val observed = mutableListOf<Boolean>()
        a.capture { observed += it == "Bearer fixture-a" }
        b.capture { observed += it == null }
        listOf(a.url("/api/items/one"), a.url("/audio/one.mp3"), b.url("/audio/one.mp3"), b.url("/login"), a.url("/api/me"))
            .forEach { url -> client.newCall(Request.Builder().url(url).build()).execute().close() }
        assertEquals(listOf(true, true, true, true, true), observed)
    }

    @Test fun `redirects check every actual origin and preserve same origin auth`() = withOrigins { a, b ->
        val auth = AuthInterceptor().apply { setToken("fixture-a", a.url()) }
        val client = OkHttpClient.Builder().addNetworkInterceptor(auth).build()
        val observed = mutableListOf<Boolean>()
        a.createContext("/start") { exchange ->
            observed += exchange.requestHeaders.getFirst("Authorization") == "Bearer fixture-a"
            exchange.responseHeaders.add("Location", a.url("/next"))
            exchange.sendResponseHeaders(302, -1); exchange.close()
        }
        a.createContext("/next") { exchange ->
            observed += exchange.requestHeaders.getFirst("Authorization") == "Bearer fixture-a"
            exchange.responseHeaders.add("Location", b.url("/end"))
            exchange.sendResponseHeaders(302, -1); exchange.close()
        }
        b.capture("/end") { observed += it == null }
        client.newCall(Request.Builder().url(a.url("/start")).build()).execute().close()
        assertEquals(listOf(true, true, true), observed)
    }

    @Test fun `explicit auth survives while cleared or invalid scope adds nothing`() = withOrigins { a, b ->
        val auth = AuthInterceptor().apply { setToken("fixture-a", a.url()) }
        val client = OkHttpClient.Builder().addNetworkInterceptor(auth).build()
        val observed = mutableListOf<Boolean>()
        a.capture { observed += it == "Custom signed-request" }
        b.capture { observed += it == null }
        client.newCall(Request.Builder().url(a.url()).header("Authorization", "Custom signed-request").build()).execute().close()
        auth.setToken(null, a.url())
        client.newCall(Request.Builder().url(b.url()).build()).execute().close()
        auth.setToken("fixture-a", "invalid")
        client.newCall(Request.Builder().url(b.url()).build()).execute().close()
        assertEquals(listOf(true, true, true), observed)
        assertFalse(auth.hasToken())
    }
    @Test fun `logout during same origin redirect removes previously injected bearer`() = withOrigins { a, _ ->
        val auth = AuthInterceptor().apply { setToken("fixture-a", a.url()) }
        val client = OkHttpClient.Builder().addNetworkInterceptor(auth).build()
        var noAuthAfterLogout = false
        a.createContext("/start") { exchange ->
            auth.setToken(null, "")
            exchange.responseHeaders.add("Location", a.url("/end"))
            exchange.sendResponseHeaders(302, -1); exchange.close()
        }
        a.capture("/end") { noAuthAfterLogout = it == null }
        client.newCall(Request.Builder().url(a.url("/start")).build()).execute().close()
        assertTrue(noAuthAfterLogout)
    }

}
