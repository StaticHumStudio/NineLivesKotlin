package com.ninelivesaudio.app.data.remote

/**
 * Splits the list endpoint's combined `seriesName` into the first series'
 * name and sequence.
 *
 * Audiobookshelf's list endpoint never sends the structured series array. It
 * sends one string, `"<name> #<sequence>"` per series, joined with `", "`:
 *   "Series Name #7"        -> ("Series Name", "7")
 *   "Series Name #1.5"      -> ("Series Name", "1.5")
 *   "S1 #1, S2 #3"          -> ("S1", "1")        first series only
 *   "Love, Death #2"        -> ("Love, Death", "2") a comma inside a name
 *   "Saga #1-3"             -> ("Saga", "1-3")    sequences are free text
 *   "Series Name"           -> ("Series Name", null)
 *
 * The old parser only accepted a numeric sequence at the very end, so a
 * multi-series book became one bogus series ("S1 #1, S2") and a non-numeric
 * sequence kept its "#..." in the name, splitting the series per book.
 *
 * A first series with no sequence followed by one with a sequence
 * ("S1, S2 #3") reads the same as a series named "S1, S2". The string
 * carries nothing that tells them apart, so it parses as the latter.
 */
internal fun parseSeriesNameField(seriesName: String): Pair<String?, String?> {
    val trimmed = seriesName.trim()
    if (trimmed.isEmpty()) return null to null
    val match = FIRST_SERIES_SEGMENT.find(trimmed)
    return if (match != null) {
        match.groupValues[1].trim() to match.groupValues[2].trim()
    } else {
        trimmed to null
    }
}

// Lazy name, then "#", then a sequence with no comma or "#" in it, ending at
// the series separator or the end of the string.
private val FIRST_SERIES_SEGMENT = Regex("""^(.+?)\s*#([^,#]+?)\s*(?:,\s*|$)""")
