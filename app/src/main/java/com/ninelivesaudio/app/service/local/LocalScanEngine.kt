package com.ninelivesaudio.app.service.local

import java.security.MessageDigest
import kotlin.time.Duration

/**
 * One entry seen during a scan, folder or file. The engine only ever asks for
 * a name, a type, a uri, a size, a mime type, and a list of children, so the
 * same tree-walking logic works over a real SAF [androidx.documentfile.provider.DocumentFile]
 * in production and over a plain in-memory fake in tests.
 */
interface ScanNode {
    val name: String?
    val isDirectory: Boolean
    val uriString: String
    val sizeBytes: Long
    val mimeType: String?
    fun children(): List<ScanNode>
}

/**
 * Metadata and cover work the engine needs but cannot do itself (it has no
 * Android imports). [LocalLibraryScanner] bridges this to the real
 * [LocalMetadataExtractor].
 */
interface ScanMetadataSource {
    fun extract(uriString: String): LocalMetadataExtractor.TrackMetadata?
    fun persistFolderCover(coverUriString: String?, bookId: String): String?
    fun extractEmbeddedCover(uriString: String, bookId: String): String?
}

/**
 * Pure JVM traversal and grouping engine for a local folder scan. No Android
 * imports, so it runs under plain JUnit. See issue #17 for the layouts this
 * exists to handle: an Author/Book tree, and a Book/CD1/CD2 multi-disc tree.
 *
 * Traversal rules, in short:
 * - A folder with direct audio files becomes one book from those files.
 * - Its subfolders are still visited afterward, each an independent candidate.
 * - A folder with no direct audio, whose audio-bearing subfolders all look
 *   like discs (CD1, Disc 2, Part 3...) and nothing else nearby holds audio,
 *   merges into one book instead of recursing.
 * - Everything else with no direct audio just recurses.
 * - Loose audio files sitting in the scan root each become their own
 *   single-file book, same as before this engine existed.
 */
class LocalScanEngine(private val metadataSource: ScanMetadataSource) {

    data class EngineResult(
        val books: List<ScannedLocalBook>,
        val skippedCount: Int,
        val errorMessages: List<String>,
        val foldersScanned: Int,
    )

    companion object {
        internal val AUDIO_EXTENSIONS = setOf(
            "mp3", "m4a", "m4b", "opus", "ogg", "flac", "aac", "wma", "wav",
        )

        internal val COVER_FILENAMES = setOf(
            "cover.jpg", "cover.jpeg", "cover.png",
            "folder.jpg", "folder.jpeg", "folder.png",
        )

        // Natural-order comparator: splits on digit runs so "2.mp3" < "10.mp3".
        private val DIGIT_RUN = Regex("\\d+|\\D+")
        internal val NATURAL_FILENAME_COMPARATOR: Comparator<String> = Comparator { a, b ->
            val ap = DIGIT_RUN.findAll(a.lowercase()).map { it.value }.toList()
            val bp = DIGIT_RUN.findAll(b.lowercase()).map { it.value }.toList()
            val n = minOf(ap.size, bp.size)
            var i = 0
            var result = 0
            while (i < n) {
                val x = ap[i]
                val y = bp[i]
                val cmp = if (x.first().isDigit() && y.first().isDigit()) {
                    // Compare as numbers; fall back to string compare on overflow.
                    val xn = x.toLongOrNull()
                    val yn = y.toLongOrNull()
                    if (xn != null && yn != null) xn.compareTo(yn) else x.compareTo(y)
                } else {
                    x.compareTo(y)
                }
                if (cmp != 0) { result = cmp; break }
                i++
            }
            if (result != 0) result else ap.size.compareTo(bp.size)
        }

        private val NUMBERED_SIBLING_KEYWORDS = listOf(
            "chapter", "volume", "disc", "disk", "part", "vol", "cd",
        )

        internal const val MAX_SCAN_DEPTH = 8
        internal const val MAX_FOLDERS_SCANNED = 1000
        private const val MAX_LOOKAHEAD_FOLDERS = 64
    }

    private data class NodeWithMeta(
        val node: ScanNode,
        val meta: LocalMetadataExtractor.TrackMetadata?,
    )

    private data class NumberedSibling(
        val prefix: String,
        val terminalNumber: String,
    )

    private class LookaheadBudget(var foldersRemaining: Int)

    fun scan(root: ScanNode, rootUriString: String): EngineResult {
        val books = mutableListOf<ScannedLocalBook>()
        val errorMessages = mutableListOf<String>()
        val childrenCache = mutableMapOf<String, List<ScanNode>>()
        var skippedCount = 0
        var foldersScanned = 0
        var depthCapMessageAdded = false
        var folderCapMessageAdded = false
        var folderCapHit = false

        fun childrenOf(node: ScanNode): List<ScanNode> =
            childrenCache.getOrPut(node.uriString) { node.children() }

        fun addDepthCapMessage() {
            if (!depthCapMessageAdded) {
                errorMessages += "Scan stopped early: folders nested deeper than " +
                    "$MAX_SCAN_DEPTH levels were skipped."
                depthCapMessageAdded = true
            }
        }

        fun addFolderCapMessage() {
            if (!folderCapMessageAdded) {
                errorMessages += "Scan stopped early: more than $MAX_FOLDERS_SCANNED folders. " +
                    "Pick a more specific folder."
                folderCapMessageAdded = true
            }
        }

        // Visits one folder below the root, applying R2 through R10. relPath and depth
        // describe this folder itself (relPath is empty only for the root, handled separately below).
        fun visit(folder: ScanNode, relPath: String, depth: Int) {
            if (folderCapHit) {
                skippedCount++
                return
            }
            if (foldersScanned >= MAX_FOLDERS_SCANNED) {
                folderCapHit = true
                addFolderCapMessage()
                skippedCount++
                return
            }
            foldersScanned++

            try {
                val children = childrenOf(folder).filterNot { isHidden(it) }
                val directAudio = children.filter { !it.isDirectory && isAudioFile(it) }
                val subfolders = children.filter { it.isDirectory }
                var emittedHere = false

                if (directAudio.isNotEmpty()) {
                    books += buildPlainBook(folder, relPath, depth, directAudio, children, rootUriString)
                    emittedHere = true
                }

                if (subfolders.isNotEmpty()) {
                    val childDepth = depth + 1
                    if (childDepth > MAX_SCAN_DEPTH) {
                        addDepthCapMessage()
                        skippedCount += subfolders.size
                    } else if (
                        directAudio.isEmpty() &&
                        foldersScanned + subfolders.size <= MAX_FOLDERS_SCANNED
                    ) {
                        val audioSubdirs = subfolders.filter { hasDirectAudio(it, ::childrenOf) }
                        val others = subfolders - audioSubdirs.toSet()
                        val lookaheadBudget = LookaheadBudget(MAX_LOOKAHEAD_FOLDERS)
                        val numberedSiblings = audioSubdirs.mapNotNull {
                            parseNumberedSibling(it.name.orEmpty())
                        }
                        val shouldMerge = audioSubdirs.isNotEmpty() &&
                            numberedSiblings.size == audioSubdirs.size &&
                            numberedSiblings.map { it.prefix }.distinct().size == 1 &&
                            others.none {
                                containsAudioAnywhere(it, childDepth, lookaheadBudget, ::childrenOf)
                            } &&
                            // A prettier parent merge is not worth losing nested audio.
                            // Recurse into plain CD and Bonus books when a disc has any.
                            audioSubdirs.none { disc ->
                                childrenOf(disc)
                                    .filterNot { isHidden(it) }
                                    .filter { it.isDirectory }
                                    .any {
                                        containsAudioAnywhere(
                                            it,
                                            childDepth + 1,
                                            lookaheadBudget,
                                            ::childrenOf,
                                        )
                                    }
                            }
                        if (shouldMerge) {
                            // Every audio-bearing subfolder is read to build the merged
                            // book, so it counts toward the folders-scanned total too.
                            foldersScanned += audioSubdirs.size
                            books += buildMergedDiscBook(
                                folder,
                                relPath,
                                depth,
                                audioSubdirs,
                                rootUriString,
                                children,
                                ::childrenOf,
                            )
                            emittedHere = true
                        } else {
                            for (sub in subfolders) {
                                visit(sub, joinRelPath(relPath, sub.name.orEmpty()), childDepth)
                            }
                        }
                    } else {
                        // R3: direct audio, or insufficient room to account for a merge,
                        // routes every subfolder through the guard-checked visit path.
                        for (sub in subfolders) {
                            visit(sub, joinRelPath(relPath, sub.name.orEmpty()), childDepth)
                        }
                    }
                }

                // R9: a visited dead end, no book of its own and nothing below it.
                if (subfolders.isEmpty() && !emittedHere) {
                    skippedCount++
                }
            } catch (e: Exception) {
                errorMessages += "Skipped folder '${folder.name}': ${e.message}"
                skippedCount++
            }
        }

        // The root is special: loose audio files here are single-file books (R1),
        // not folded into a "root folder book". Subfolders recurse normally.
        foldersScanned++
        val rootChildren = childrenOf(root).filterNot { isHidden(it) }
        val rootAudioFiles = rootChildren.filter { !it.isDirectory && isAudioFile(it) }
        val rootSubfolders = rootChildren.filter { it.isDirectory }

        for (file in rootAudioFiles) {
            try {
                books += buildSingleFileBook(file, rootUriString)
            } catch (e: Exception) {
                errorMessages += "Skipped file '${file.name}': ${e.message}"
                skippedCount++
            }
        }

        for (sub in rootSubfolders) {
            visit(sub, sub.name.orEmpty(), 1)
        }

        return EngineResult(
            books = books,
            skippedCount = skippedCount,
            errorMessages = errorMessages,
            foldersScanned = foldersScanned,
        )
    }

    // ─── Book builders ─────────────────────────────────────────────────────

    private fun buildSingleFileBook(file: ScanNode, rootUri: String): ScannedLocalBook {
        val meta = metadataSource.extract(file.uriString)
        val filename = file.name ?: "Unknown"
        val bookId = "local_book_${sha256("$rootUri/$filename")}"

        val title = meta?.title?.takeIf { it.isNotBlank() }
            ?: meta?.album?.takeIf { it.isNotBlank() }
            ?: filename.substringBeforeLast(".")

        val author = meta?.albumArtist?.takeIf { it.isNotBlank() }
            ?: meta?.artist?.takeIf { it.isNotBlank() }
            ?: "Unknown Author"

        val coverUri = metadataSource.extractEmbeddedCover(file.uriString, bookId)

        val track = ScannedTrack(
            id = "local_file_${sha256(file.uriString)}",
            uri = file.uriString,
            filename = filename,
            index = 0,
            duration = meta?.duration ?: Duration.ZERO,
            mimeType = meta?.mimeType ?: file.mimeType,
            size = file.sizeBytes,
        )

        return ScannedLocalBook(
            id = bookId,
            title = title,
            author = author,
            coverUri = coverUri,
            duration = meta?.duration ?: Duration.ZERO,
            tracks = listOf(track),
        )
    }

    private fun buildPlainBook(
        folder: ScanNode,
        relPath: String,
        depth: Int,
        directAudio: List<ScanNode>,
        allChildren: List<ScanNode>,
        rootUri: String,
    ): ScannedLocalBook {
        val sorted = sortTracks(directAudio)
        val firstMeta = sorted.first().meta
        val tracks = buildTracks(sorted)
        val totalDuration = tracks.fold(Duration.ZERO) { acc, t -> acc + t.duration }
        val folderName = folder.name ?: "Unknown"

        val title = firstMeta?.album?.takeIf { it.isNotBlank() } ?: folderName
        val author = resolveAuthor(firstMeta, depth, relPath)
        val bookId = "local_book_${sha256("$rootUri/$relPath")}"

        val coverUri = metadataSource.persistFolderCover(findCoverImage(allChildren), bookId)
            ?: metadataSource.extractEmbeddedCover(sorted.first().node.uriString, bookId)

        return ScannedLocalBook(
            id = bookId,
            title = title,
            author = author,
            coverUri = coverUri,
            duration = totalDuration,
            tracks = tracks,
        )
    }

    private fun buildMergedDiscBook(
        folder: ScanNode,
        relPath: String,
        depth: Int,
        audioSubdirs: List<ScanNode>,
        rootUri: String,
        ownChildren: List<ScanNode>,
        childrenOf: (ScanNode) -> List<ScanNode>,
    ): ScannedLocalBook {
        val discsInOrder = audioSubdirs.sortedWith { a, b ->
            val aName = a.name.orEmpty()
            val bName = b.name.orEmpty()
            val aNumber = checkNotNull(parseNumberedSibling(aName)).terminalNumber
            val bNumber = checkNotNull(parseNumberedSibling(bName)).terminalNumber
            compareNumericStrings(aNumber, bNumber)
                .takeIf { it != 0 }
                ?: NATURAL_FILENAME_COMPARATOR.compare(aName, bName)
                    .takeIf { it != 0 }
                ?: aName.compareTo(bName)
        }

        // Sort each disc on its own (trackNumber restarts per disc), then lay the
        // discs end to end. Never sort the merged list by trackNumber as a whole.
        val perDisc = discsInOrder.map { disc ->
            val discAudio = childrenOf(disc)
                .filterNot { isHidden(it) }
                .filter { !it.isDirectory && isAudioFile(it) }
            sortTracks(discAudio)
        }
        val allSorted = perDisc.flatten()
        val tracks = buildTracks(allSorted)
        val totalDuration = tracks.fold(Duration.ZERO) { acc, t -> acc + t.duration }

        val firstTrack = allSorted.first()
        val folderName = folder.name ?: "Unknown"
        val title = firstTrack.meta?.album?.takeIf { it.isNotBlank() } ?: folderName
        val author = resolveAuthor(firstTrack.meta, depth, relPath)
        val bookId = "local_book_${sha256("$rootUri/$relPath")}"

        val firstDiscChildren = childrenOf(discsInOrder.first()).filterNot { isHidden(it) }
        val coverSource = findCoverImage(ownChildren) ?: findCoverImage(firstDiscChildren)
        val coverUri = metadataSource.persistFolderCover(coverSource, bookId)
            ?: metadataSource.extractEmbeddedCover(firstTrack.node.uriString, bookId)

        return ScannedLocalBook(
            id = bookId,
            title = title,
            author = author,
            coverUri = coverUri,
            duration = totalDuration,
            tracks = tracks,
        )
    }

    // ─── Shared helpers ────────────────────────────────────────────────────

    private fun sortTracks(files: List<ScanNode>): List<NodeWithMeta> {
        val withMeta = files.map { NodeWithMeta(it, metadataSource.extract(it.uriString)) }
        return withMeta.sortedWith(
            compareBy<NodeWithMeta> { it.meta?.trackNumber ?: Int.MAX_VALUE }
                .then(compareBy(NATURAL_FILENAME_COMPARATOR) { it.node.name.orEmpty() })
        )
    }

    private fun buildTracks(sorted: List<NodeWithMeta>): List<ScannedTrack> {
        return sorted.mapIndexed { index, nwm ->
            ScannedTrack(
                id = "local_file_${sha256(nwm.node.uriString)}",
                uri = nwm.node.uriString,
                filename = nwm.node.name ?: "track_${index + 1}",
                index = index,
                duration = nwm.meta?.duration ?: Duration.ZERO,
                mimeType = nwm.meta?.mimeType ?: nwm.node.mimeType,
                size = nwm.node.sizeBytes,
            )
        }
    }

    private fun resolveAuthor(
        meta: LocalMetadataExtractor.TrackMetadata?,
        depth: Int,
        relPath: String,
    ): String {
        meta?.albumArtist?.takeIf { it.isNotBlank() }?.let { return it }
        meta?.artist?.takeIf { it.isNotBlank() }?.let { return it }
        if (depth >= 2) {
            val segments = relPath.split("/")
            if (segments.size >= 2) {
                val parentName = segments[segments.size - 2]
                if (parentName.isNotBlank()) return parentName
            }
        }
        return "Unknown Author"
    }

    private fun findCoverImage(children: List<ScanNode>): String? {
        for (child in children) {
            if (child.isDirectory) continue
            val name = child.name?.lowercase() ?: continue
            if (name in COVER_FILENAMES) return child.uriString
        }
        return null
    }

    private fun hasDirectAudio(
        node: ScanNode,
        childrenOf: (ScanNode) -> List<ScanNode>,
    ): Boolean {
        return childrenOf(node).any { !isHidden(it) && !it.isDirectory && isAudioFile(it) }
    }

    private fun parseNumberedSibling(name: String): NumberedSibling? {
        var numberStart = name.length
        while (numberStart > 0 && name[numberStart - 1] in '0'..'9') {
            numberStart--
        }
        if (numberStart == name.length) return null

        var keywordEnd = numberStart
        while (keywordEnd > 0 && name[keywordEnd - 1].isNumberedSiblingSeparator()) {
            keywordEnd--
        }
        val keyword = NUMBERED_SIBLING_KEYWORDS.firstOrNull {
            keywordEnd >= it.length && name.regionMatches(
                thisOffset = keywordEnd - it.length,
                other = it,
                otherOffset = 0,
                length = it.length,
                ignoreCase = true,
            )
        } ?: return null

        val keywordStart = keywordEnd - keyword.length
        val prefix = name.substring(0, keywordStart)
        if (prefix.isNotEmpty() && !prefix.last().isNumberedSiblingSeparator()) {
            return null
        }
        return NumberedSibling(
            prefix = prefix.trim { it.isNumberedSiblingSeparator() }.lowercase(),
            terminalNumber = name.substring(numberStart),
        )
    }

    private fun Char.isNumberedSiblingSeparator(): Boolean =
        this == ' ' || this == '.' || this == '_' || this == '-'

    private fun compareNumericStrings(a: String, b: String): Int {
        val normalizedA = a.trimStart('0').ifEmpty { "0" }
        val normalizedB = b.trimStart('0').ifEmpty { "0" }
        return normalizedA.length.compareTo(normalizedB.length)
            .takeIf { it != 0 }
            ?: normalizedA.compareTo(normalizedB)
    }

    private fun containsAudioAnywhere(
        node: ScanNode,
        depth: Int,
        budget: LookaheadBudget,
        childrenOf: (ScanNode) -> List<ScanNode>,
    ): Boolean {
        // Exhaustion assumes audio exists. That disables merging and sends the tree
        // through normal recursion, where the scan depth and folder caps are enforced.
        if (budget.foldersRemaining == 0) return true
        budget.foldersRemaining--

        val children = childrenOf(node).filterNot { isHidden(it) }
        if (children.any { !it.isDirectory && isAudioFile(it) }) return true
        val subfolders = children.filter { it.isDirectory }
        if (subfolders.isEmpty()) return false
        if (depth >= MAX_SCAN_DEPTH) return true
        return subfolders.any {
            containsAudioAnywhere(it, depth + 1, budget, childrenOf)
        }
    }

    private fun isAudioFile(node: ScanNode): Boolean {
        val name = node.name?.lowercase() ?: return false
        val ext = name.substringAfterLast('.', "")
        return ext in AUDIO_EXTENSIONS
    }

    private fun isHidden(node: ScanNode): Boolean {
        val name = node.name ?: return true
        return name.startsWith(".")
    }

    private fun joinRelPath(parent: String, name: String): String =
        if (parent.isEmpty()) name else "$parent/$name"

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
