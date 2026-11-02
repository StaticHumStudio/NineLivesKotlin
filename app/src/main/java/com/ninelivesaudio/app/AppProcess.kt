package com.ninelivesaudio.app

/**
 * True when [processName] is the app's main process, the one named after the
 * package. Side processes such as the crash dialog's ":acra" get a suffix.
 *
 * An unknown name counts as main, because an app that starts nothing is worse
 * than one that starts everything.
 */
internal fun isMainAppProcess(processName: String?, packageName: String): Boolean =
    processName == null || processName == packageName
