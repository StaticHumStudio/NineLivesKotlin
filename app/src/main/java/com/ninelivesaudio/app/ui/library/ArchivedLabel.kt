package com.ninelivesaudio.app.ui.library

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The Archive tab's line for a local book that is there because its folder went
 * missing (or was removed in Settings), with the day it was first noticed.
 * Draft copy, pending Jeff.
 */
internal fun archivedLabel(archivedAtMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    "Folder not found since " +
        DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US).withZone(zone).format(Instant.ofEpochMilli(archivedAtMs))
