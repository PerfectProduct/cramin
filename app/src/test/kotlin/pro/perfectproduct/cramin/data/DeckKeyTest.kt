package pro.perfectproduct.cramin.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import pro.perfectproduct.cramin.data.repo.DeckFilter
import pro.perfectproduct.cramin.data.repo.DeckKey
import pro.perfectproduct.cramin.util.Lang

class DeckKeyTest {
    @Test
    fun roundTrip() {
        val doc = DeckKey.Document(42, DeckFilter.STARRED)
        assertEquals("doc:42:starred", doc.key)
        assertEquals(doc, DeckKey.parse(doc.key))
        val all = DeckKey.All(Lang.HE, Lang.RU)
        assertEquals("all:he-ru", all.key)
        assertEquals(all, DeckKey.parse(all.key))
    }

    @Test
    fun rejectsGarbage() {
        assertNull(DeckKey.parse("doc:x:all"))
        assertNull(DeckKey.parse("doc:1:nope"))
        assertNull(DeckKey.parse("all:xx-ru"))
        assertNull(DeckKey.parse(""))
    }
}
