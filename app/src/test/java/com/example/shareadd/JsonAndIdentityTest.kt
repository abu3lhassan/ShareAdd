package com.example.shareadd

import com.example.shareadd.backup.JsonFormatException
import com.example.shareadd.backup.JsonIn
import com.example.shareadd.backup.JsonOut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader
import java.io.StringWriter

class JsonAndIdentityTest {

    private fun place(id: String, name: String) = Place(
        id = id, placeName = name, title = "t", url = "https://a.test/$name", description = "", privateNotes = "",
        categoryAr = "أخرى", categoryEn = "Other", images = emptyList(), rating = 0, favorite = false, pinned = false,
        createdAt = 1L, updatedAt = 2L
    )

    @Test
    fun json_roundTripKeepsArabicAndEscapes() {
        val tricky = "مطعم \"البيك\" \\ سطر\nجديد\t— \u0001 end"
        val sw = StringWriter()
        JsonOut(sw).beginObject().field("text", tricky).field("n", 1791700000000L).field("b", true).name("arr").beginArray().value("x").value(2).endArray().endObject().flush()
        val r = JsonIn(StringReader(sw.toString()))
        r.beginObject()
        assertEquals("text", r.nextName()); assertEquals(tricky, r.nextString())
        assertEquals("n", r.nextName()); assertEquals(1791700000000L, r.nextLong())
        assertEquals("b", r.nextName()); assertTrue(r.nextBoolean())
        assertEquals("arr", r.nextName()); r.beginArray(); assertEquals("x", r.nextString()); assertEquals(2L, r.nextLong()); r.endArray()
        r.endObject()
        r.endDocument()
    }

    @Test(expected = JsonFormatException::class)
    fun json_truncatedInputFails() {
        val r = JsonIn(StringReader("""[{"a":"b"},{"a":"c"""))
        r.beginArray()
        while (r.hasNext()) r.skipValue()
        r.endArray()
    }

    @Test(expected = JsonFormatException::class)
    fun json_trailingGarbageFails() {
        val r = JsonIn(StringReader("""[1,2] x"""))
        r.skipValue()
        r.endDocument()
    }

    @Test
    fun ids_generatedOnceKeptInOrderAndDuplicatesRepaired() {
        var n = 0
        val input = listOf(place("", "a"), place("pl-1", "b"), place("pl-1", "c"), place("  ", "d"), place("pl-2", "e"))
        val result = assignPlaceIds(input) { "gen-${++n}" }
        assertEquals(listOf("a", "b", "c", "d", "e"), result.places.map { it.placeName })
        assertEquals(2, result.generated)
        assertEquals(1, result.repaired)
        assertEquals("pl-1", result.places[1].id)
        assertNotEquals("pl-1", result.places[2].id)
        assertEquals(result.places.size, result.places.map { it.id }.toSet().size)
        // every other field unchanged
        input.zip(result.places).forEach { (a, b) -> assertEquals(a.copy(id = b.id), b) }
        // a second pass changes nothing
        val again = assignPlaceIds(result.places) { "never" }
        assertEquals(result.places, again.places)
        assertEquals(0, again.generated + again.repaired)
    }
}
