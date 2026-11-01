package com.ninelivesaudio.app.widget

import com.ninelivesaudio.app.ui.home.HomeEmptyKind
import com.ninelivesaudio.app.ui.home.homeEmptyCopy

/**
 * Every user visible widget string that is not a manifest label or picker
 * description (those must be string resources). New lines are draft copy
 * pending Jeff. Empty library lines reuse Home's approved copy.
 */
internal object WidgetCopy {
    const val BINDER_LOCKED = "Unlock to fill the Binder"
    const val HOME_LOCKED = "Unlock to bring the altar home"
    const val SIGNED_OUT = "Signed out. Open Nine Lives to sign in."
    const val FOLDER_LOST = "The folder went quiet. Open Nine Lives to find it."

    const val SETUP_TITLE = "Set up your Binder"
    const val SETUP_TAP_HEADER = "When you tap a cover"
    const val SETUP_TAP_PLAY = "Play it"
    const val SETUP_TAP_OPEN = "Open its page"
    const val SETUP_SORT_HEADER = "Order"
    const val SETUP_DONE = "Done"
    const val SETUP_LOCKED_NOTE = "Your picks wait here until you unlock."

    const val CD_PLAY = "Play"
    const val CD_PAUSE = "Pause"
    const val CD_OPEN_APP = "Open Nine Lives"
    const val CD_LOCKED = "Locked. Tap to see the unlock."

    const val UNLOCK_BENEFIT = "Home screen widgets, the Binder and Nine Lives"

    private val serverNoBooks = homeEmptyCopy(HomeEmptyKind.SERVER_NO_BOOKS).subtitle
    private val localNoBooks = homeEmptyCopy(HomeEmptyKind.LOCAL_NO_BOOKS).subtitle
    private val nothingPlayed = homeEmptyCopy(HomeEmptyKind.SERVER_UNPLAYED).body!!

    /** Every widget line, for the punctuation test. */
    val all: List<String> = listOf(
        BINDER_LOCKED, HOME_LOCKED, SIGNED_OUT, FOLDER_LOST,
        SETUP_TITLE, SETUP_TAP_HEADER, SETUP_TAP_PLAY, SETUP_TAP_OPEN,
        SETUP_SORT_HEADER, SETUP_DONE, SETUP_LOCKED_NOTE,
        CD_PLAY, CD_PAUSE, CD_OPEN_APP, CD_LOCKED, UNLOCK_BENEFIT,
        serverNoBooks, localNoBooks, nothingPlayed,
    )

    /** The one calm line for a non ready state. Null for READY and LOCKED (the locked line is per widget). */
    fun emptyLine(content: WidgetContent, isLocalMode: Boolean): String? = when (content) {
        WidgetContent.READY, WidgetContent.LOCKED -> null
        WidgetContent.SIGNED_OUT -> SIGNED_OUT
        WidgetContent.FOLDER_LOST -> FOLDER_LOST
        WidgetContent.NO_BOOKS -> if (isLocalMode) localNoBooks else serverNoBooks
        WidgetContent.NOTHING_PLAYED -> nothingPlayed
    }
}
