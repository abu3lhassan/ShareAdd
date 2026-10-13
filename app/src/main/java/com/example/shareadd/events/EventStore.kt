package com.example.shareadd.events

import android.content.Context
import com.example.shareadd.StoreLoadResult
import com.example.shareadd.saveRecoveryCopy

private const val EVENTS_PREFS = "shareadd_events"

fun loadEventsChecked(context: Context): StoreLoadResult<List<ShareEvent>> {
    val text = try {
        context.getSharedPreferences(EVENTS_PREFS, Context.MODE_PRIVATE).getString("events", null)
    } catch (_: Exception) {
        return StoreLoadResult(emptyList(), ok = false)
    }
    if (text.isNullOrBlank()) return StoreLoadResult(emptyList(), ok = true)
    val parsed = parseEventsStrict(text)
        ?: return StoreLoadResult(emptyList(), ok = false, recoveryFile = saveRecoveryCopy(context, "events", text))
    return StoreLoadResult(parsed, ok = true)
}

fun loadTemplatesChecked(context: Context): StoreLoadResult<List<InvitationTemplate>> {
    val text = try {
        context.getSharedPreferences(EVENTS_PREFS, Context.MODE_PRIVATE).getString("templates", null)
    } catch (_: Exception) {
        return StoreLoadResult(emptyList(), ok = false)
    }
    if (text.isNullOrBlank()) return StoreLoadResult(emptyList(), ok = true)
    val parsed = parseTemplatesStrict(text)
        ?: return StoreLoadResult(emptyList(), ok = false, recoveryFile = saveRecoveryCopy(context, "templates", text))
    return StoreLoadResult(parsed, ok = true)
}

fun saveEvents(context: Context, events: List<ShareEvent>) {
    context.getSharedPreferences(EVENTS_PREFS, Context.MODE_PRIVATE).edit().putString("events", eventsToJson(events)).apply()
}

fun saveTemplates(context: Context, templates: List<InvitationTemplate>) {
    context.getSharedPreferences(EVENTS_PREFS, Context.MODE_PRIVATE).edit().putString("templates", templatesToJson(templates)).apply()
}

/** Synchronous writes used when applying an import journal. */
fun commitEventsJson(context: Context, eventsJson: String?, templatesJson: String?): Boolean {
    if (eventsJson == null && templatesJson == null) return true
    val editor = context.getSharedPreferences(EVENTS_PREFS, Context.MODE_PRIVATE).edit()
    if (eventsJson != null) editor.putString("events", eventsJson)
    if (templatesJson != null) editor.putString("templates", templatesJson)
    return editor.commit()
}
