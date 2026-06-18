package com.ninelivesaudio.app.service.download

// ─── WorkManager identifiers for downloads ────────────────────────────────
//
// All downloads run on a single unique-work chain so they execute strictly one
// at a time (APPEND_OR_REPLACE on this name enforces the order). Each book's
// work is also tagged by its download id so it can be cancelled individually
// for pause/cancel without disturbing the rest of the queue.

/** Shared unique-work name for the sequential download chain. */
const val DOWNLOAD_WORK_NAME = "audiobook_downloads"

/** Input-data key carrying the download id into the worker. */
const val KEY_DOWNLOAD_ID = "download_id"

/** Per-download work tag, used to cancel a single book's work by tag. */
fun downloadWorkTag(downloadId: String): String = "download:$downloadId"
