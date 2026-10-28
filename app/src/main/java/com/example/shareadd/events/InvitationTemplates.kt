package com.example.shareadd.events

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Variables an invitation (event) template may use. {prayer}, {imam} and {iqama_time} are meant for prayer templates. */
val TEMPLATE_VARIABLES = listOf(
    "title", "host", "date", "time", "opening_time", "place_name", "address", "map_link", "description", "notes", "prayer", "imam", "day",
    "iqama_time", "day_date"
)

/** Template type of place-sharing templates (stored in [InvitationTemplate.type] next to the event types). */
const val PLACE_TEMPLATE_TYPE = "place"

/** Variables a place-sharing template may use. The place's private notes are never one of them. */
val PLACE_TEMPLATE_VARIABLES = listOf("place_name", "address", "description", "map_link", "category", "rating", "notes")

fun variablesFor(templateType: String): List<String> = if (templateType == PLACE_TEMPLATE_TYPE) PLACE_TEMPLATE_VARIABLES else TEMPLATE_VARIABLES

/** Names shown to the user for each variable (the "Insert field" menu); the {name} itself stays the stable identity. */
fun variableLabel(name: String, arabic: Boolean): String = when (name) {
    "title" -> if (arabic) "عنوان المناسبة" else "Event title"
    "host" -> if (arabic) "المستضيف" else "Host"
    "day" -> if (arabic) "اليوم" else "Weekday"
    "date" -> if (arabic) "التاريخ" else "Date"
    "day_date" -> if (arabic) "الموعد (اليوم أو اليوم والتاريخ)" else "Day (or day and date)"
    "time" -> if (arabic) "وقت المناسبة / دخول وقت الصلاة" else "Event time / prayer time"
    "iqama_time" -> if (arabic) "وقت الإقامة" else "Iqama time"
    "opening_time" -> if (arabic) "وقت فتح الباب" else "Doors open"
    "place_name" -> if (arabic) "اسم المكان" else "Place name"
    "address" -> if (arabic) "العنوان المختصر" else "Short address"
    "map_link" -> if (arabic) "رابط الموقع" else "Location link"
    "description" -> if (arabic) "الوصف" else "Description"
    "notes" -> if (arabic) "التنويهات" else "Notices"
    "prayer" -> if (arabic) "الصلاة" else "Prayer"
    "imam" -> if (arabic) "عبارة الإمامة" else "Imam line"
    "category" -> if (arabic) "التصنيف" else "Category"
    "rating" -> if (arabic) "التقييم" else "Rating"
    else -> name
}

/**
 * Variables that are an optional part of a line ("🕌 {title} — {day}"): when empty, only the variable and the
 * separator right before it are left out, the rest of the line stays. A line made only of such empty variables is removed.
 */
private val INLINE_OPTIONAL_VARIABLES = setOf("day")

private val VARIABLE = Regex("""\{([A-Za-z_]+)\}""")

data class RenderResult(
    val text: String,
    /** variables in the template that ShareAdd does not know (they are left out of the text) */
    val unknownVariables: List<String>
)

/** Variable names used in a template, and the ones that are not supported. */
fun templateVariables(body: String): List<String> = VARIABLE.findAll(body).map { it.groupValues[1] }.distinct().toList()

fun unknownTemplateVariables(body: String, known: List<String> = TEMPLATE_VARIABLES): List<String> =
    templateVariables(body).filterNot { it in known }

/**
 * Fills a template:
 *  - every known variable is replaced by its value;
 *  - a line that contains a variable without a value is removed (optional lines disappear cleanly), except
 *    inline-optional variables such as {day}: only they (and the separator before them) are left out;
 *  - unknown variables are removed from the text and reported, so "{x}" or "null" never reaches the invitation;
 *  - a value of several lines (notices written as a list) keeps all its lines, breaks and bullets; when the line has
 *    text before it ("ℹ️ تنويه: {notes}"), that text stays on its own line as the heading and the list follows after a
 *    blank line (the heading comes only from the template, nothing is added to it);
 *  - blank lines produced by removals are collapsed.
 */
fun renderInvitation(body: String, values: Map<String, String>, knownVariables: List<String> = TEMPLATE_VARIABLES): RenderResult {
    val known = knownVariables
    val unknown = unknownTemplateVariables(body, known)
    val lines = body.replace("\r\n", "\n").split("\n")
    val out = mutableListOf<String>()
    for (original in lines) {
        val vars = VARIABLE.findAll(original).map { it.groupValues[1] }.toList()
        val used = vars.filter { it in known }
        if (used.any { it !in INLINE_OPTIONAL_VARIABLES && values[it].isNullOrBlank() }) continue
        var line = original
        val emptyInline = used.filter { it in INLINE_OPTIONAL_VARIABLES && values[it].isNullOrBlank() }.distinct()
        if (emptyInline.isNotEmpty()) {
            if (used.all { it in emptyInline }) continue
            emptyInline.forEach { name -> line = line.replace(Regex("""\s*[—–\-:,،]?\s*\{""" + name + """\}"""), "") }
        }
        val filled = VARIABLE.replace(line) { m ->
            val name = m.groupValues[1]
            val value = if (name in known) values[name]?.replace("\r\n", "\n")?.trim().orEmpty() else ""
            if ('\n' in value && line.substring(0, m.range.first).isNotBlank()) "\n\n" + value else value
        }
        if (vars.isNotEmpty() && filled.isBlank()) continue
        filled.split("\n").forEach { out.add(it.trimEnd()) }
    }
    val text = out.joinToString("\n")
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()
    return RenderResult(text, unknown)
}

// ------------------------------------------------------------------ built-in template definitions (Arabic + English)
// Only the first content of the stored default templates (see DEFAULT_TEMPLATE_SEEDS); never rendered directly.

const val BUILTIN_PRAYER = "builtin-prayer"
const val BUILTIN_FAMILY = "builtin-family"
const val BUILTIN_SOCIAL = "builtin-social"
const val BUILTIN_GENERAL = "builtin-general"

data class BuiltInTemplate(val id: String, val type: String, val nameAr: String, val nameEn: String, val bodyAr: String, val bodyEn: String)

val BUILT_IN_TEMPLATES = listOf(
    BuiltInTemplate(
        BUILTIN_PRAYER, EventTypes.PRAYER, "صلاة جماعة", "Congregational prayer",
        bodyAr = """
🕌 {title} — {day}
{imam}
👤 المستضيف: {host}
📅 التاريخ: {date}
🚪 فتح الباب: {opening_time}
⏰ وقت الصلاة: {time}
🕋 وقت الإقامة: {iqama_time}
🏠 المكان: {place_name}
📍 العنوان المختصر: {address}
🗺️ للوصول إلى الموقع: {map_link}

📝 {description}
ℹ️ تنويه: {notes}
""".trim(),
        bodyEn = """
🕌 {title} — {day}
{imam}
👤 Host: {host}
📅 Date: {date}
🚪 Doors open: {opening_time}
⏰ Prayer time: {time}
🕋 Iqama: {iqama_time}
🏠 Place: {place_name}
📍 Short address: {address}
🗺️ Directions: {map_link}

📝 {description}
ℹ️ Note: {notes}
""".trim()
    ),
    BuiltInTemplate(
        BUILTIN_FAMILY, EventTypes.FAMILY, "مناسبة عائلية", "Family occasion",
        bodyAr = """
💐 {title} — {day}
👤 الداعي: {host}
📅 التاريخ: {date}
⏰ الوقت: {time}
🚪 فتح الباب: {opening_time}
🏠 المكان: {place_name}
📍 العنوان المختصر: {address}
🗺️ للوصول إلى الموقع: {map_link}

📝 {description}
ℹ️ تنويه: {notes}

حضوركم يسعدنا
""".trim(),
        bodyEn = """
💐 {title} — {day}
👤 Hosted by: {host}
📅 Date: {date}
⏰ Time: {time}
🚪 Doors open: {opening_time}
🏠 Place: {place_name}
📍 Short address: {address}
🗺️ Directions: {map_link}

📝 {description}
ℹ️ Note: {notes}

We look forward to seeing you
""".trim()
    ),
    BuiltInTemplate(
        BUILTIN_SOCIAL, EventTypes.SOCIAL, "دعوة اجتماعية / اجتماع", "Social invitation / meeting",
        bodyAr = """
📣 {title} — {day}
👤 المضيف: {host}
📅 التاريخ: {date}
⏰ الوقت: {time}
🚪 فتح الباب: {opening_time}
🏠 المكان: {place_name}
📍 العنوان المختصر: {address}
🗺️ للوصول إلى الموقع: {map_link}

📝 {description}
ℹ️ تنويه: {notes}
""".trim(),
        bodyEn = """
📣 {title} — {day}
👤 Host: {host}
📅 Date: {date}
⏰ Time: {time}
🚪 Doors open: {opening_time}
🏠 Place: {place_name}
📍 Short address: {address}
🗺️ Directions: {map_link}

📝 {description}
ℹ️ Note: {notes}
""".trim()
    ),
    BuiltInTemplate(
        BUILTIN_GENERAL, EventTypes.GENERAL, "قالب عام", "General",
        bodyAr = """
{title} — {day}
{host}
{date}
{time}
{place_name}
{address}
للوصول إلى الموقع: {map_link}

{description}
تنويه: {notes}
""".trim(),
        bodyEn = """
{title} — {day}
{host}
{date}
{time}
{place_name}
{address}
Directions: {map_link}

{description}
Note: {notes}
""".trim()
    )
)

// ------------------------------------------------------------------ built-in place-sharing templates

const val BUILTIN_PLACE_DETAILED = "builtin-place-detailed"
const val BUILTIN_PLACE_SHORT = "builtin-place-short"

/** One optional ShareAdd signature, inside the template (the user can edit or delete it); nothing is added outside. */
val BUILT_IN_PLACE_TEMPLATES = listOf(
    BuiltInTemplate(
        BUILTIN_PLACE_DETAILED, PLACE_TEMPLATE_TYPE, "مشاركة مفصلة", "Detailed share",
        bodyAr = """
📍 {place_name}
🏠 العنوان المختصر: {address}
🗂 التصنيف: {category}
⭐ التقييم: {rating}
📝 {description}
🗺️ للوصول إلى الموقع: {map_link}

ℹ️ تنويه: {notes}

تمت المشاركة عبر ShareAdd
""".trim(),
        bodyEn = """
📍 {place_name}
🏠 Short address: {address}
🗂 Category: {category}
⭐ Rating: {rating}
📝 {description}
🗺️ Directions: {map_link}

ℹ️ Note: {notes}

Shared via ShareAdd
""".trim()
    ),
    BuiltInTemplate(
        BUILTIN_PLACE_SHORT, PLACE_TEMPLATE_TYPE, "مشاركة مختصرة", "Short share",
        bodyAr = """
📍 {place_name}
🏠 {address}
🗺️ {map_link}

ℹ️ {notes}
""".trim(),
        bodyEn = """
📍 {place_name}
🏠 {address}
🗺️ {map_link}

ℹ️ {notes}
""".trim()
    )
)

// ------------------------------------------------------------------ default templates: seeded once into the stored templates

/*
 * The definitions above are only the first content of the default templates. They are copied once into the stored
 * templates (one per definition and language, with a stable id); from then on the stored copy is the template: it is
 * edited in place like any other template, and choosing, rendering and sharing always read the stored templates.
 */

/** Stable id of a seeded default template: "default-prayer-ar", "default-place-short-en", ... */
fun defaultTemplateId(builtInId: String, language: String): String =
    "default-" + builtInId.removePrefix("builtin-") + if (language == "en") "-en" else "-ar"

private val SEED_DEFINITIONS = BUILT_IN_TEMPLATES + BUILT_IN_PLACE_TEMPLATES

/** The default templates as first stored (Arabic and English of each definition, in this order). */
val DEFAULT_TEMPLATE_SEEDS: List<InvitationTemplate> = SEED_DEFINITIONS.flatMap { b ->
    listOf(
        InvitationTemplate(defaultTemplateId(b.id, "ar"), b.nameAr, b.type, "ar", b.bodyAr),
        InvitationTemplate(defaultTemplateId(b.id, "en"), b.nameEn, b.type, "en", b.bodyEn)
    )
}

/** seeded default id → built-in definition id */
private val SEED_DEFINITION_OF: Map<String, String> =
    SEED_DEFINITIONS.flatMap { b -> listOf(defaultTemplateId(b.id, "ar") to b.id, defaultTemplateId(b.id, "en") to b.id) }.toMap()

private val SEED_ORDER: Map<String, Int> = DEFAULT_TEMPLATE_SEEDS.mapIndexed { i, t -> t.id to i }.toMap()

fun isDefaultTemplate(id: String): Boolean = id in SEED_DEFINITION_OF

/** Language of a seeded default ("ar" / "en"), fixed by its id; null for any other template. */
fun defaultTemplateLanguage(id: String): String? = if (isDefaultTemplate(id)) if (id.endsWith("-en")) "en" else "ar" else null

/**
 * The stored templates with every missing default template added (matched by id). Stored templates — edited defaults
 * and the user's own templates — are kept exactly as they are and in their order, so running this again changes nothing.
 * Returns the same list instance when nothing is missing.
 */
fun seedDefaultTemplates(stored: List<InvitationTemplate>): List<InvitationTemplate> {
    val ids = stored.mapTo(HashSet()) { it.id }
    val missing = DEFAULT_TEMPLATE_SEEDS.filter { it.id !in ids }
    return if (missing.isEmpty()) stored else missing + stored
}

/**
 * The stored template an id refers to in [language]: the id of a built-in definition (events and settings saved before
 * the defaults were stored: "builtin-prayer") or of a default in the other language maps to the default of [language];
 * any other id stays as it is.
 */
fun templateIdFor(id: String, language: String): String {
    val definition = SEED_DEFINITION_OF[id] ?: id.takeIf { d -> SEED_DEFINITIONS.any { it.id == d } } ?: return id
    return defaultTemplateId(definition, language)
}

/** A template that can be chosen; [isDefault] = one of the seeded default templates (edited or not). */
data class TemplateChoice(val id: String, val name: String, val type: String, val body: String, val isDefault: Boolean)

private fun InvitationTemplate.toChoice() = TemplateChoice(id, name, type, body, isDefaultTemplate(id))

/** Stored templates for a list: the defaults first (in their seed order), then the user's own templates in their order. */
fun orderedTemplates(templates: List<InvitationTemplate>): List<InvitationTemplate> =
    templates.filter { isDefaultTemplate(it.id) }.sortedBy { SEED_ORDER[it.id] } + templates.filterNot { isDefaultTemplate(it.id) }

/** Invitation templates for an event in [language]: the stored defaults of that language, then the user's own event templates. */
fun templateChoices(templates: List<InvitationTemplate>, language: String): List<TemplateChoice> =
    orderedTemplates(templates)
        .filter { it.type != PLACE_TEMPLATE_TYPE && (defaultTemplateLanguage(it.id) ?: language) == language }
        .map { it.toChoice() }

/** Place-sharing templates in [language]: the stored place defaults of that language, then the user's own place templates. */
fun placeTemplateChoices(templates: List<InvitationTemplate>, language: String): List<TemplateChoice> =
    orderedTemplates(templates)
        .filter { it.type == PLACE_TEMPLATE_TYPE && (defaultTemplateLanguage(it.id) ?: language) == language }
        .map { it.toChoice() }

/** The template actually used; [fallback] = the requested template no longer exists and this default is used instead. */
data class ResolvedTemplate(val template: TemplateChoice, val fallback: Boolean)

private fun resolveIn(choices: List<TemplateChoice>, requestedId: String, language: String, defaultDefinition: String): ResolvedTemplate {
    val wanted = templateIdFor(requestedId, language)
    choices.firstOrNull { it.id == wanted }?.let { return ResolvedTemplate(it, false) }
    val defaultId = defaultTemplateId(defaultDefinition, language)
    // the stored default; its first content only if the stored copy is missing (storage could not be read)
    val default = choices.firstOrNull { it.id == defaultId } ?: DEFAULT_TEMPLATE_SEEDS.first { it.id == defaultId }.toChoice()
    return ResolvedTemplate(default, fallback = requestedId.isNotBlank())
}

/** The stored template of an event: its own [templateId] when it still exists, else the stored default of its [type]. */
fun resolveEventTemplate(templates: List<InvitationTemplate>, templateId: String, language: String, type: String): ResolvedTemplate =
    resolveIn(templateChoices(templates, language), templateId, language, defaultTemplateFor(type))

/** The stored place-sharing template [templateId] when it still exists, else the stored detailed default. */
fun resolvePlaceTemplate(templates: List<InvitationTemplate>, templateId: String?, language: String): ResolvedTemplate =
    resolveIn(placeTemplateChoices(templates, language), templateId.orEmpty(), language, BUILTIN_PLACE_DETAILED)

/** Built-in definition of the default template of an event type (use [defaultTemplateId] for the stored template). */
fun defaultTemplateFor(type: String): String = when (type) {
    EventTypes.PRAYER -> BUILTIN_PRAYER
    EventTypes.FAMILY -> BUILTIN_FAMILY
    EventTypes.SOCIAL -> BUILTIN_SOCIAL
    else -> BUILTIN_GENERAL
}

fun eventTypeLabel(type: String, arabic: Boolean): String = when (type) {
    PLACE_TEMPLATE_TYPE -> if (arabic) "مشاركة مكان" else "Place share"
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

/** Weekday of an event date: "2026-10-14" → "الأربعاء" / "Wednesday"; invalid or empty → "". */
fun formatEventWeekday(date: String, language: String): String = formatDatePattern(date, language, "EEEE", "EEEE")

/** Date line of an invitation, without the weekday (the weekday is shown with the title): "14 أكتوبر 2026" / "October 14, 2026". */
fun formatInvitationDate(date: String, language: String): String = formatDatePattern(date, language, "d MMMM yyyy", "MMMM d, yyyy")

private fun formatDatePattern(date: String, language: String, patternAr: String, patternEn: String): String {
    if (date.isBlank()) return ""
    return try {
        val parser = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { isLenient = false; timeZone = TimeZone.getTimeZone("UTC") }
        val d = parser.parse(date) ?: return ""
        SimpleDateFormat(if (language == "en") patternEn else patternAr, localeFor(language)).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(d)
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
    "day" to formatEventWeekday(e.date, e.language),
    "date" to if (e.showDate) formatInvitationDate(e.date, e.language) else "",
    "day_date" to if (e.showDate) formatEventDate(e.date, e.language) else formatEventWeekday(e.date, e.language),
    "time" to formatEventTime(e.time, e.language),
    "iqama_time" to formatEventTime(e.iqamaTime, e.language),
    "opening_time" to formatEventTime(e.openingTime, e.language),
    "place_name" to if (e.showPlaceName) e.place.placeName else "",
    "address" to e.place.title,
    "map_link" to e.place.url,
    "description" to e.description,
    "notes" to e.notes,
    "prayer" to Prayers.label(e.prayer, e.language != "en"),
    "imam" to e.imamLine
)

fun renderEvent(e: ShareEvent, templateBody: String): RenderResult = renderInvitation(templateBody, invitationValues(e))

/**
 * Values for a place-sharing template. [notes] are the notices written for this one share (never stored in the place);
 * the place's private notes are not available here.
 */
fun placeShareValues(
    placeName: String,
    shortAddress: String,
    description: String,
    url: String,
    category: String,
    rating: Int,
    notes: String
): Map<String, String> = mapOf(
    "place_name" to placeName,
    "address" to shortAddress,
    "description" to description,
    "map_link" to url,
    "category" to category,
    "rating" to if (rating in 1..5) "$rating/5" else "",
    "notes" to notes
)

fun renderPlaceShare(templateBody: String, values: Map<String, String>): RenderResult =
    renderInvitation(templateBody, values, PLACE_TEMPLATE_VARIABLES)
