package com.ninelivesaudio.app.data.local

import com.ninelivesaudio.app.data.local.converter.toEntity
import com.ninelivesaudio.app.data.local.entity.AudioBookEntity
import com.ninelivesaudio.app.domain.model.AudioBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Every book row carries one folded copy of the fields the search reads. */
class SearchTextTest {

    @Test
    fun `the searched fields are folded and kept apart`() {
        assertEquals(
            "l'etranger\u001Falbert camus\u001Fclassiques\u001Fjerome",
            bookSearchText("L'Étranger", "Albert Camus", "Classiques", "Jérôme"),
        )
        assertEquals("dune\u001F\u001F\u001F", bookSearchText("DUNE", null, null, null))
    }

    @Test
    fun `a search cannot match across two fields`() {
        // "dune frank" would match "Dune Frank Herbert" if the fields ran together.
        val text = bookSearchText("Dune", "Frank Herbert", null, null)
        assertFalse(text.contains("dune frank"))
        assertFalse(text.contains("dune "))
    }

    @Test
    fun `a row is filled on write`() {
        val entity = AudioBook(
            id = "b1",
            title = "Émile",
            author = "Jean-Jacques ROUSSEAU",
            narrator = "Zoë",
            seriesName = "Œuvres",
        ).toEntity()

        assertEquals(bookSearchText("Émile", "Jean-Jacques ROUSSEAU", "Œuvres", "Zoë"), entity.searchText)
    }

    @Test
    fun `a copied row follows its new fields`() {
        val entity = AudioBookEntity(id = "b1", title = "Dune", author = "Author")

        val renamed = entity.copy(title = "Élan", author = "Wolfgang")

        assertEquals(bookSearchText("Élan", "Wolfgang", null, null), renamed.searchText)
    }
}
