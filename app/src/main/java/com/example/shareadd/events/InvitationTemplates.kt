package com.example.shareadd.events

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Variables a template may use. {prayer} and {imam} are additional variables for the prayer template. */
val TEMPLATE_VARIABLES = listOf(
    "title", "host", "date", "time", "opening_time", "place_name", "address", "map_link", "description", "notes", "prayer", "imam"
)

private val VARIABLE = Regex("""\{([A-Za-z_]+)\}""")

data class RenderResult(
    val text: String,
    /** variables in the template that ShareAdd does not know (they are left out of the text) */
    val unknownVariables: List<String>
)

/** Variable names used in a template, and the ones that are not supported. */
fun templateVariables(body: String): List<String> = VARIABLE.findAll(body).map { it.groupValues[1] }.distinct().toList()

fun unknownTemplateVariables(body: String): List<String> = templateVariables(body).filterNot { it in TEMPLATE_VARIABLES }

/**
 * Fills a template:
 *  - every known variable is replaced by its value;
 *  - a line that contains a variable without a value is removed (optional lines disappear cleanly);
 *  - unknown variables are removed from the text and reported, so "{x}" or "null" never reaches the invitation;
 *  - blank lines produced by removals are collapsed.
 */
fun renderInvitation(body: String, values: Map<String, String>): RenderResult {
    val unknown = unknownTemplateVariables(body)
    val lines = body.replace("\r\n", "\n").split("\n")
    val out = mutableListOf<String>()
    for (line in lines) {
        val vars = VARIABLE.findAll(line).map { it.groupValues[1] }.toList()
        val known = vars.filter { it in TEMPLATE_VARIABLES }
        if (known.any { values[it].isNullOrBlank() }) continue
        val filled = VARIABLE.replace(line) { m ->
            val name = m.groupValues[1]
            if (name in TEMPLATE_VARIABLES) values[name]?.trim().orEmpty() else ""
        }
        if (vars.isNotEmpty() && filled.isBlank()) continue
        out.add(filled.trimEnd())
    }
    val text = out.joinToString("\n")
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()
    return RenderResult(text, unknown)
}

// ------------------------------------------------------------------ built-in templates (Arabic + English)

const val BUILTIN_PRAYER = "builtin-prayer"
const val BUILTIN_FAMILY = "builtin-family"
const val BUILTIN_SOCIAL = "builtin-social"
const val BUILTIN_GENERAL = "builtin-general"

data class BuiltInTemplate(val id: String, val type: String, val nameAr: String, val nameEn: String, val bodyAr: String, val bodyEn: String)

val BUILT_IN_TEMPLATES = listOf(
    BuiltInTemplate(
        BUILTIN_PRAYER, EventTypes.PRAYER, "صلاة جماعة", "Congregational prayer",
        bodyAr = """
🕌 {title}
{imam}
👤 المستضيف: {host}
📅 التاريخ: {date}
🚪 فتح الباب: {opening_time}
⏰ وقت الصلاة: {time}
🏠 المكان: {place_name}
📍 العنوان: {address}
📝 {description}
ℹ️ {notes}
🗺 {map_link}
""".trim(),
        bodyEn = """
🕌 {title}
{imam}
👤 Host: {host}
📅 Date: {date}
🚪 Doors open: {opening_time}
⏰ Prayer time: {time}
🏠 Place: {place_name}
📍 Address: {address}
📝 {description}
ℹ️ {notes}
🗺 {map_link}
""".trim()
    ),
    BuiltInTemplate(
        BUILTIN_FAMILY, EventTypes.FAMILY, "مناسبة عائلية", "Family occasion",
        bodyAr = """
💐 {title}
👤 الداعي: {host}
📅 التاريخ: {date}
⏰ الوقت: {time}
🚪 فتح الباب: {opening_time}
🏠 المكان: {place_name}
📍 العنوان: {address}
📝 {description}
ℹ️ {notes}
🗺 {map_link}

حضوركم يسعدنا
""".trim(),
        bodyEn = """
💐 {title}
👤 Hosted by: {host}
📅 Date: {date}
⏰ Time: {time}
🚪 Doors open: {opening_time}
🏠 Place: {place_name}
📍 Address: {address}
📝 {description}
ℹ️ {notes}
🗺 {map_link}

We look forward to seeing you
""".trim()
    ),
    BuiltInTemplate(
        BUILTIN_SOCIAL, EventTypes.SOCIAL, "دعوة اجتماعية / اجتماع", "Social invitation / meeting",
        bodyAr = """
📣 {title}
👤 المضيف: {host}
📅 التاريخ: {date}
⏰ الوقت: {time}
🚪 فتح الباب: {opening_time}
🏠 المكان: {place_name}
📍 العنوان: {address}
📝 {description}
ℹ️ {notes}
🗺 {map_link}
""".trim(),
        bodyEn = """
📣 {title}
👤 Host: {host}
📅 Date: {date}
⏰ Time: {time}
🚪 Doors open: {opening_time}
🏠 Place: {place_name}
📍 Address: {address}
📝 {description}
ℹ️ {notes}
🗺 {map_link}
""".trim()
    ),
    BuiltInTemplate(
        BUILTIN_GENERAL, EventTypes.GENERAL, "قالب عام", "General",
        bodyAr = """
{title}
{host}
{date}
{time}
{place_name}
{address}
{description}
{notes}
{map_link}
""".trim(),
        bodyEn = """
{title}
{host}
{date}
{time}
{place_name}
{address}
{description}
{notes}
{map_link}
""".trim()
    )
)

/** A template the event editor can choose: a built-in one (in the event language) or a custom one. */
data class TemplateChoice(val id: String, val name: String, val type: String, val body: String, val builtIn: Boolean)

fun templateChoices(custom: List<InvitationTemplate>, language: String, arabicUi: Boolean): List<TemplateChoice> =
    BUILT_IN_TEMPLATES.map {
        TemplateChoice(it.id, if (arabicUi) it.nameAr else it.nameEn, it.type, if (language == "en") it.bodyEn else it.bodyAr, true)
    } + custom.map { TemplateChoice(it.id, it.name, it.type, it.body, false) }

fun defaultTemplateFor(type: String): String = when (type) {
    EventTypes.PRAYER -> BUILTIN_PRAYER
    EventTypes.FAMILY -> BUILTIN_FAMILY
    EventTypes.SOCIAL -> BUILTIN_SOCIAL
    else -> BUILTIN_GENERAL
}

fun eventTypeLabel(type: String, arabic: Boolean): String = when (type) {
    EventTypes.PRAYER -> if (arabic) "صلاة جماعة" else "Prayer"
    EventTypes.FAMILY -> if (arabic) "مناسبة عائلية" else "Family"
    EventTypes.SOCIAL -> if (arabic) "اجتماعية / اجتماع" else "Social / meeting"
    else -> if (arabic) "عامة" else "General"
}

/** Suggested title for a prayer event (editable; never contains a person's name). */
fun defaultPrayerTitle(prayer: String, language: String): String {
    val label = Prayers.label(prayer, language != "en")
    if (label.isBlank()) return ""
    return if (language == "en") "$label congregational prayer" else "صلاة $label جماعة"
}

// ------------------------------------------------------------------ date / time display (no Hijri conversion in this batch)

private fun localeFor(language: String): Locale =
    if (language == "en") Locale.ENGLISH else Locale.forLanguageTag("ar-u-nu-latn")

/** "2026-10-20" → "الثلاثاء 20 أكتوبر 2026" / "Tuesday, October 20, 2026"; invalid or empty → "". */
fun formatEventDate(date: String, language: String): String {
    if (date.isBlank()) return ""
    return try {
        val parser = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { isLenient = false; timeZone = TimeZone.getTimeZone("UTC") }
        val d = parser.parse(date) ?: return ""
        val pattern = if (language == "en") "EEEE, MMMM d, yyyy" else "EEEE d MMMM yyyy"
        SimpleDateFormat(pattern, localeFor(language)).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(d)
    } catch (_: Exception) {
        ""
    }
}

/** Short form for buttons and cards (the full weekday form is too long there): "20 أكتوبر 2026" / "Oct 20, 2026". */
fun formatEventDateShort(date: String, language: String): String {
    if (date.isBlank()) return ""
    return try {
        val parser = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { isLenient = false; timeZone = TimeZone.getTimeZone("UTC") }
        val d = parser.parse(date) ?: return ""
        val pattern = if (language == "en") "MMM d, yyyy" else "d MMMM yyyy"
        SimpleDateFormat(pattern, localeFor(language)).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(d)
    } catch (_: Exception) {
        ""
    }
}

/** "18:30" → "6:30 م" / "6:30 PM"; invalid or empty → "". */
fun formatEventTime(time: String, language: String): String {
    if (time.isBlank()) return ""
    return try {
        val parser = SimpleDateFormat("HH:mm", Locale.ROOT).apply { isLenient = false; timeZone = TimeZone.getTimeZone("UTC") }
        val t = parser.parse(time) ?: return ""
        SimpleDateFormat("h:mm a", localeFor(language)).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(t)
    } catch (_: Exception) {
        ""
    }
}

/** Values for a template. Private notes of the place are never available here (the event only has a public snapshot). */
fun invitationValues(e: ShareEvent): Map<String, String> = mapOf(
    "title" to e.title,
    "host" to e.host,
    "date" to formatEventDate(e.date, e.language),
    "time" to formatEventTime(e.time, e.language),
    "opening_time" to formatEventTime(e.openingTime, e.language),
    "place_name" to e.place.placeName,
    "address" to e.place.title,
    "map_link" to e.place.url,
    "description" to e.description,
    "notes" to e.notes,
    "prayer" to Prayers.label(e.prayer, e.language != "en"),
    "imam" to e.imamLine
)

fun renderEvent(e: ShareEvent, templateBody: String): RenderResult = renderInvitation(templateBody, invitationValues(e))
