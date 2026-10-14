package com.example.shareadd

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

private val WEB_LINK = Regex("""^(https?)://([^/?#\s@]+)([/?#]\S*)?$""", RegexOption.IGNORE_CASE)
private val HOST = Regex("""^[\p{L}\p{N}](?:[\p{L}\p{N}-]*[\p{L}\p{N}])?(?:\.[\p{L}\p{N}](?:[\p{L}\p{N}-]*[\p{L}\p{N}])?)+(?::\d{1,5})?$""")

/**
 * MAP-01: returns the link to store/open, or null when it is not a usable web link.
 * - "maps.app.goo.gl/xyz" (no scheme) gets "https://" in front, like before;
 * - only http/https with a real host name are accepted (no javascript:, file:, intent: …);
 * - the link is otherwise kept exactly as entered (short links are not expanded or rewritten).
 */
fun normalizeMapUrl(input: String): String? {
    val text = input.trim()
    if (text.isEmpty() || text.any { it.isWhitespace() }) return null

    // a scheme has no dot, so "example.com:8080/x" is treated as a link without a scheme
    val hasScheme = Regex("""^[A-Za-z][A-Za-z0-9+-]*:""").containsMatchIn(text)
    val candidate = if (hasScheme) text else "https://$text"

    val match = WEB_LINK.matchEntire(candidate) ?: return null
    val host = match.groupValues[2]
    if (!HOST.matches(host)) return null
    return candidate
}

/** Opens a saved map link; an invalid link or a missing app shows a message instead of crashing. */
fun openMapLink(context: Context, url: String, arabic: Boolean) {
    val link = normalizeMapUrl(url)
    if (link == null) {
        Toast.makeText(
            context,
            if (arabic) "رابط الخريطة غير صالح" else "The map link is not valid",
            Toast.LENGTH_SHORT
        ).show()
        return
    }

    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(
            context,
            if (arabic) "لا يوجد تطبيق لفتح هذا الرابط" else "No app found to open this link",
            Toast.LENGTH_SHORT
        ).show()
    }
}
