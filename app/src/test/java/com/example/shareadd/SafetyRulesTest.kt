package com.example.shareadd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SafetyRulesTest {

    @Test
    fun mapLinks_validLinksAreKeptAsEntered() {
        val keep = listOf(
            "https://maps.app.goo.gl/abc123XYZ",
            "https://maps.app.goo.gl/abc?g_st=ic",
            "https://www.google.com/maps/place/مطعم+البيك/@24.7136,46.6753,17z",
            "http://example.com",
            "HTTPS://Maps.Google.com/?q=24.7,46.6",
            "https://goo.gl/maps/xyz#frag",
            "https://maps.google.com:443/maps?q=1"
        )
        keep.forEach { assertEquals(it, normalizeMapUrl(it)) }
    }

    @Test
    fun mapLinks_missingSchemeGetsHttps() {
        assertEquals("https://maps.app.goo.gl/abc", normalizeMapUrl("maps.app.goo.gl/abc"))
        assertEquals("https://maps.app.goo.gl/abc", normalizeMapUrl("  maps.app.goo.gl/abc  "))
        assertEquals("https://example.com:8080/x", normalizeMapUrl("example.com:8080/x"))
    }

    @Test
    fun mapLinks_unsafeOrBrokenLinksAreRejected() {
        val reject = listOf(
            "",
            "   ",
            "javascript:alert(1)",
            "file:///data/data/com.example.shareadd/shared_prefs/x.xml",
            "intent://scan/#Intent;scheme=zxing;end",
            "content://com.example.shareadd.fileprovider/shareadd_images/a.png",
            "geo:24.7,46.6",
            "https://",
            "https://exa mple.com",
            "not a link",
            "localhost",
            "https://user@evil.example/",
            "httpfoo",
            "https://-bad-.com/x"
        )
        reject.forEach { assertNull("should reject: $it", normalizeMapUrl(it)) }
    }

    @Test
    fun imageTypeIsDetectedFromContent() {
        fun bytes(vararg v: Int) = ByteArray(16) { if (it < v.size) v[it].toByte() else 0 }
        assertEquals("png", detectImageExtension(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)))
        assertEquals("jpg", detectImageExtension(bytes(0xFF, 0xD8, 0xFF, 0xE0)))
        val webp = "RIFF____WEBPVP8 ".toByteArray(Charsets.US_ASCII)
        assertEquals("webp", detectImageExtension(webp))
        assertNull(detectImageExtension("<?xml version".toByteArray(Charsets.US_ASCII)))
        assertNull(detectImageExtension(ByteArray(0)))
    }
}
