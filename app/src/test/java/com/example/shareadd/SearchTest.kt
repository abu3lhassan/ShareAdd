package com.example.shareadd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTest {

    private fun place(
        name: String, title: String = "", rating: Int = 0, catAr: String = "أخرى", catEn: String = "Other", desc: String = ""
    ) = Place(
        id = "id-$name", placeName = name, title = title, url = "https://maps.app.goo.gl/x", description = desc, privateNotes = "",
        categoryAr = catAr, categoryEn = catEn, images = emptyList(), rating = rating, favorite = false, pinned = false,
        createdAt = 1, updatedAt = 1
    )

    @Test
    fun numbersInAddressesAreNotRatingFilters() {
        val gate3 = place("بوابة 3", rating = 1)
        val floor2 = place("Floor 2 Office", rating = 5)
        assertTrue(matchesSmartSearch(gate3, "بوابة 3"))
        assertTrue(matchesSmartSearch(floor2, "Floor 2"))
        assertFalse(matchesSmartSearch(place("بوابة 7", rating = 3), "بوابة 3"))
        assertTrue(matchesSmartSearch(place("مخرج ٥"), "مخرج 5"))   // Arabic-Indic digits in data
        assertTrue(matchesSmartSearch(place("مخرج 5"), "مخرج ٥"))   // Arabic-Indic digits in query
    }

    @Test
    fun explicitRatingStillFilters() {
        val five = place("A", rating = 5)
        val three = place("B", rating = 3)
        assertTrue(matchesSmartSearch(five, "5 نجوم"))
        assertFalse(matchesSmartSearch(three, "5 نجوم"))
        assertTrue(matchesSmartSearch(three, "3 stars"))
        assertTrue(matchesSmartSearch(three, "⭐3"))
        assertTrue(matchesSmartSearch(three, "3⭐"))
        assertTrue(matchesSmartSearch(three, "٣ نجمة"))
        assertFalse(matchesSmartSearch(five, "3 star"))
        // rating + text together
        assertTrue(matchesSmartSearch(place("مطعم البيك", rating = 4), "البيك 4 نجوم"))
        assertFalse(matchesSmartSearch(place("مطعم البيك", rating = 2), "البيك 4 نجوم"))
    }

    @Test
    fun synonymsMatchAnyAlternativeAndKeepTheTypedWord() {
        // the place literally named "مكتب" is found (before the fix the typed word was replaced away)
        assertTrue(matchesSmartSearch(place("مكتب الشركة", catAr = "أخرى", catEn = "Other"), "مكتب"))
        // a Work-category place is found by "مكتب" without having to contain "office" too
        assertTrue(matchesSmartSearch(place("Head Quarters", catAr = "عمل", catEn = "Work"), "مكتب"))
        assertTrue(matchesSmartSearch(place("Al Baik", catAr = "مطاعم", catEn = "Restaurants"), "مطعم"))
        assertTrue(matchesSmartSearch(place("Brew Lab", desc = "specialty coffee"), "كافيه"))
        assertTrue(matchesSmartSearch(place("مقهى", catAr = "مطاعم", catEn = "Restaurants"), "كوفي"))
        assertFalse(matchesSmartSearch(place("حديقة", catAr = "أماكن", catEn = "Places"), "مطعم"))
        // all words still have to match
        assertFalse(matchesSmartSearch(place("Al Baik", catAr = "مطاعم", catEn = "Restaurants"), "مطعم جدة"))
    }

    @Test
    fun blankQueryMatchesEverything() {
        assertTrue(matchesSmartSearch(place("x"), "   "))
    }

    @Test
    fun categorySuggestionKeepsCurrentWhenNothingMatches() {
        val cats = defaultCategories()
        val current = cats.last()
        assertEquals(current, suggestCategoryFromText("https://example.test/a", "nice", cats, current))
        assertEquals("مطاعم", suggestCategoryFromText("", "best coffee", cats, current).ar)
    }
}
