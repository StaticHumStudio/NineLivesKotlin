package com.ninelivesaudio.app.service

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.ninelivesaudio.app.domain.model.Chapter

/**
 * A ForwardingPlayer that overrides position and duration to return
 * chapter-relative values. This makes the Media3 notification seek bar
 * show the current chapter's progress instead of the entire book.
 *
 * When [currentChapter] is null (e.g. no chapters), all methods fall
 * through to the wrapped player, preserving the existing behaviour.
 */
@OptIn(UnstableApi::class)
class ChapterAwareForwardingPlayer(
    player: Player,
) : ForwardingPlayer(player) {

    /** Updated by PlaybackManager whenever the chapter changes. */
    var currentChapter: Chapter? = null

    /** Full chapter list, updated by PlaybackManager when a book is loaded. */
    var chapters: List<Chapter> = emptyList()

    /** Index into [chapters] for the current chapter, updated by PlaybackManager. */
    var currentChapterIndex: Int = -1

    /** Updated by PlaybackManager during position polling (absolute book position in ms). */
    var absolutePositionMs: Long = 0L

    /**
     * Lambda that delegates a chapter-relative seek back to PlaybackManager,
     * which knows how to translate an absolute position into the correct
     * ExoPlayer track + offset.
     */
    var seekHandler: ((Long) -> Unit)? = null

    // ─── Position / Duration Overrides ──────────────────────────────────

    override fun getCurrentPosition(): Long {
        val ch = currentChapter ?: return super.getCurrentPosition()
        val chapterStartMs = (ch.start * 1000).toLong()
        return (absolutePositionMs - chapterStartMs).coerceAtLeast(0L)
    }

    override fun getDuration(): Long {
        val ch = currentChapter ?: return super.getDuration()
        return (ch.durationSeconds * 1000).toLong()
    }

    override fun getContentDuration(): Long = getDuration()

    override fun getContentPosition(): Long = getCurrentPosition()

    override fun getBufferedPosition(): Long {
        val ch = currentChapter ?: return super.getBufferedPosition()
        val chapterDurationMs = (ch.durationSeconds * 1000).toLong()
        val currentPos = getCurrentPosition()
        val bufferedAhead = (super.getBufferedPosition() - super.getCurrentPosition()).coerceAtLeast(0L)
        return (currentPos + bufferedAhead).coerceIn(0L, chapterDurationMs)
    }

    override fun getContentBufferedPosition(): Long = getBufferedPosition()

    // ─── Seek Override ──────────────────────────────────────────────────

    override fun seekTo(positionMs: Long) {
        val ch = currentChapter
        if (ch == null) {
            super.seekTo(positionMs)
            return
        }

        // Clamp to chapter bounds
        val chapterDurationMs = (ch.durationSeconds * 1000).toLong()
        val clamped = positionMs.coerceIn(0L, chapterDurationMs)

        // Convert to absolute book position
        val chapterStartMs = (ch.start * 1000).toLong()
        val absoluteMs = chapterStartMs + clamped

        val handler = seekHandler
        if (handler != null) {
            handler(absoluteMs)
        } else {
            super.seekTo(absoluteMs)
        }
    }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        if (currentChapter != null) {
            // Chapter mode: interpret positionMs as chapter-relative
            seekTo(positionMs)
        } else {
            // No chapters: pass through to the real player with the track index intact
            super.seekTo(mediaItemIndex, positionMs)
        }
    }

    // ─── Chapter Skip (Android Auto next/previous buttons) ─────────────

    override fun seekToNext() {
        val ch = currentChapter
        val handler = seekHandler
        if (ch == null || handler == null) {
            super.seekToNext()
            return
        }
        // Skip to end of current chapter (= start of next chapter)
        handler((ch.end * 1000).toLong())
    }

    override fun seekToPrevious() {
        val ch = currentChapter
        val handler = seekHandler
        if (ch == null || handler == null) {
            super.seekToPrevious()
            return
        }
        val chapterStartMs = (ch.start * 1000).toLong()
        val posInChapterMs = absolutePositionMs - chapterStartMs

        if (posInChapterMs > 3000 && currentChapterIndex >= 0) {
            // More than 3s into chapter: jump to start of current chapter
            handler(chapterStartMs)
        } else if (currentChapterIndex > 0) {
            // Near start: jump to start of previous chapter
            val prevChapter = chapters[currentChapterIndex - 1]
            handler((prevChapter.start * 1000).toLong())
        } else {
            // First chapter: jump to beginning of book
            handler(0L)
        }
    }

    override fun hasNextMediaItem(): Boolean {
        if (chapters.isNotEmpty()) return true
        return super.hasNextMediaItem()
    }

    override fun hasPreviousMediaItem(): Boolean {
        if (chapters.isNotEmpty()) return true
        return super.hasPreviousMediaItem()
    }

    // ─── Command Availability ───────────────────────────────────────────

    override fun isCommandAvailable(command: Int): Boolean {
        if (command == Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) return true
        if (command == Player.COMMAND_SEEK_TO_NEXT) return chapters.isNotEmpty()
        if (command == Player.COMMAND_SEEK_TO_PREVIOUS) return chapters.isNotEmpty()
        return super.isCommandAvailable(command)
    }

    override fun getAvailableCommands(): Player.Commands {
        val builder = super.getAvailableCommands()
            .buildUpon()
            .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
        if (chapters.isNotEmpty()) {
            builder.add(Player.COMMAND_SEEK_TO_NEXT)
            builder.add(Player.COMMAND_SEEK_TO_PREVIOUS)
        }
        return builder.build()
    }
}
