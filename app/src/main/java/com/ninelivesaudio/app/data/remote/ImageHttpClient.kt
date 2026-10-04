package com.ninelivesaudio.app.data.remote

import okhttp3.Dispatcher
import okhttp3.OkHttpClient

/**
 * The HTTP client cover images load through: the app client with the same auth,
 * base URL and certificate setup, but its own request [Dispatcher].
 *
 * OkHttp's dispatcher lets 5 requests per host wait on a response at once. With
 * one shared client, a screen full of covers on a slow server (a cover the
 * server has not resized yet takes a while to answer) filled those slots, and
 * the play, session and progress calls queued behind them. Covers now wait in
 * their own line. The connection pool is still shared.
 */
fun imageLoaderHttpClient(appClient: OkHttpClient): OkHttpClient =
    appClient.newBuilder()
        .dispatcher(Dispatcher())
        .build()
