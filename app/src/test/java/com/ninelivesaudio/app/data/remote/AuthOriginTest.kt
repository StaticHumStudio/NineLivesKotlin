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

    @Test fun `cached bearer is not sent to a different reverse proxy path on its origin`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        try {
            server.start()
            val auth = AuthInterceptor().apply { setToken("fixture-a", server.url("/abs-a")) }
            val client = OkHttpClient.Builder().addNetworkInterceptor(auth).build()
            var received: String? = "not-called"
            server.capture("/abs-b/api/libraries") { received = it }

            client.newCall(Request.Builder().url(server.url("/abs-b/api/libraries")).build()).execute().close()

            assertNull("A bearer reached a same-origin B proxy path", received)
        } finally {
            server.stop(0)
        }
    }

    @Test fun `settings-only route switch suppresses an untagged retained bearer`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        try {
            server.start()
            val routeA = requireNotNull(ServerRoute.parse(server.url("/abs-a")))
            val routeB = requireNotNull(ServerRoute.parse(server.url("/abs-b")))
            val auth = AuthInterceptor { routeB }.apply { setToken("fixture-a", routeA.url) }
            val client = OkHttpClient.Builder().addNetworkInterceptor(auth).build()
            var received: String? = "not-called"
            server.capture("/abs-a/api/me") { received = it }

            client.newCall(Request.Builder().url(server.url("/abs-a/api/me")).build()).execute().close()

            assertNull("A bearer survived an unowned settings route switch", received)
        } finally {
            server.stop(0)
        }
    }

    @Test fun `no bearer dispatch suppresses a retained bearer on the matching route`() = withOrigins { a, _ ->
        val auth = AuthInterceptor().apply { setToken("fixture-a", a.url()) }
        val route = requireNotNull(ServerRoute.parse(a.url()))
        val client = OkHttpClient.Builder().addNetworkInterceptor(auth).build()
        var received: String? = "not-called"
        a.capture { received = it }

        client.newCall(
            Request.Builder().url(a.url("/login"))
                .tag(RemoteDispatchTag::class.java, RemoteDispatchTag.noBearer(route))
                .build()
        ).execute().close()

        assertNull("Login dispatch retained an old bearer", received)
    }

    @Test fun `frozen dispatch fails before sending after its scope changes`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        try {
            server.start()
            val aRoute = requireNotNull(ServerRoute.parse(server.url("/abs-a")))
            val bRoute = requireNotNull(ServerRoute.parse(server.url("/abs-b")))
            val auth = AuthInterceptor().apply { setToken("fixture-a", aRoute.url) }
            val frozen = FrozenRemoteRequest(aRoute, null, FrozenBearer("fixture-a", aRoute, 0), routeRevision = 0)
            auth.setToken("fixture-b", bRoute.url)
            val client = OkHttpClient.Builder().addNetworkInterceptor(auth).build()
            var calls = 0
            server.capture("/abs-a/api/me") { calls++ }

            assertThrows(StaleRemoteRequestException::class.java) {
                client.newCall(
                    Request.Builder().url(server.url("/abs-a/api/me"))
                        .tag(RemoteDispatchTag::class.java, RemoteDispatchTag.frozen(frozen))
                        .build()
                ).execute().close()
            }
            assertEquals(0, calls)
        } finally {
            server.stop(0)
        }
    }

    @Test fun `A to B to A route revision still rejects the original frozen request`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        try {
            server.start()
            val route = requireNotNull(ServerRoute.parse(server.url("/abs-a")))
            var revision = 7L
            val auth = AuthInterceptor({ route }, { revision }).apply { setToken("fixture-a", route.url) }
            val frozen = FrozenRemoteRequest(route, null, FrozenBearer("fixture-a", route, 0), routeRevision = revision)
            revision += 2 // The settings path changed A to B and back to A.
            val client = OkHttpClient.Builder().addNetworkInterceptor(auth).build()
            var calls = 0
            server.capture("/abs-a/api/me") { calls++ }

            assertThrows(StaleRemoteRequestException::class.java) {
                client.newCall(
                    Request.Builder().url(server.url("/abs-a/api/me"))
                        .tag(RemoteDispatchTag::class.java, RemoteDispatchTag.frozen(frozen))
                        .build()
                ).execute().close()
            }
            assertEquals(0, calls)
        } finally {
            server.stop(0)
        }
    }

    @Test fun `frozen dispatch strips its bearer on a same origin redirect outside its route`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        try {
            server.start()
            val route = requireNotNull(ServerRoute.parse(server.url("/abs-a")))
            val frozen = FrozenRemoteRequest(route, null, FrozenBearer("fixture-a", route, 0), routeRevision = 0)
            val auth = AuthInterceptor().apply { setToken("fixture-a", route.url) }
            val client = OkHttpClient.Builder().addNetworkInterceptor(auth).build()
            var redirectedBearer: String? = "not-called"
            server.createContext("/abs-a/api/me") { exchange ->
                exchange.responseHeaders.add("Location", server.url("/abs-b/api/me"))
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            }
            server.capture("/abs-b/api/me") { redirectedBearer = it }

            client.newCall(
                Request.Builder().url(server.url("/abs-a/api/me"))
                    .tag(RemoteDispatchTag::class.java, RemoteDispatchTag.frozen(frozen))
                    .build()
            ).execute().close()
            assertNull("A bearer reached the redirected route", redirectedBearer)
        } finally {
            server.stop(0)
        }
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
