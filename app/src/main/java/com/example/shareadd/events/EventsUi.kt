package com.example.shareadd.events

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.shareadd.ImagePreview
import com.example.shareadd.ImageThumbnails
import com.example.shareadd.Place
import com.example.shareadd.copyText
import com.example.shareadd.isShareAddPrivateImage
import com.example.shareadd.isTrustedImageRef
import com.example.shareadd.normalizeMapUrl
import com.example.shareadd.shareAddImageUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

fun newEventId(): String = "ev-" + UUID.randomUUID()
fun newTemplateId(): String = "tpl-" + UUID.randomUUID()

fun snapshotOf(place: Place) = PlaceSnapshot(placeName = place.placeName, title = place.title, url = place.url)

/** How the linked place relates to the details saved in the event. */
enum class PlaceLink { NONE, SAME, CHANGED, DELETED }

fun placeLinkState(event: ShareEvent, places: List<Place>): PlaceLink {
    val id = event.placeId ?: return PlaceLink.NONE
    val place = places.firstOrNull { it.id == id } ?: return PlaceLink.DELETED
    return if (snapshotOf(place) == event.place) PlaceLink.SAME else PlaceLink.CHANGED
}

private val EditorEventSaver = Saver<ShareEvent, String>(
    save = { eventsToJson(listOf(it)) },
    restore = { parseEventsStrict(it)?.firstOrNull() }
)

/** Saver for an optional event (e.g. the event open in an editor), kept as JSON text. */
val ShareEventSaver = Saver<ShareEvent?, String>(
    save = { e -> if (e == null) "" else eventsToJson(listOf(e)) },
    restore = { s -> if (s.isEmpty()) null else parseEventsStrict(s)?.firstOrNull() }
)

private fun sortEvents(events: List<ShareEvent>) =
    events.sortedWith(compareBy<ShareEvent>({ it.date.isBlank() }, { it.date }, { it.time }, { -it.updatedAt }))

// ======================================================================= Events page (inside the main LazyColumn)

fun LazyListScope.eventsPageItems(
    arabic: Boolean,
    events: List<ShareEvent>,
    places: List<Place>,
    onNewEvent: () -> Unit,
    onManageTemplates: () -> Unit,
    onPreview: (ShareEvent) -> Unit,
    onEdit: (ShareEvent) -> Unit,
    onDuplicate: (ShareEvent) -> Unit,
    onDelete: (ShareEvent) -> Unit
) {
    item {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text(
                    if (arabic) "المناسبات والدعوات" else "Events & Invitations",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (arabic) "أنشئ دعوة من موقع محفوظ، واختر قالبًا، وشارك النص والرابط ورمز QR."
                    else "Create an invitation from a saved place, pick a template, then share the text, link and QR code.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = onNewEvent, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                    Text(if (arabic) "+ مناسبة جديدة" else "+ New Event")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onManageTemplates, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                    Text(if (arabic) "إدارة القوالب" else "Manage Templates")
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            if (arabic) "المناسبات المحفوظة: ${events.size}" else "Saved events: ${events.size}",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(10.dp))
        if (events.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🎟️", style = MaterialTheme.typography.headlineLarge)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (arabic) "لا توجد مناسبات بعد" else "No events yet",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
    items(sortEvents(events), key = { it.id }) { event ->
        EventCard(arabic, event, placeLinkState(event, places), onPreview, onEdit, onDuplicate, onDelete)
    }
}

@Composable
private fun EventCard(
    arabic: Boolean,
    event: ShareEvent,
    link: PlaceLink,
    onPreview: (ShareEvent) -> Unit,
    onEdit: (ShareEvent) -> Unit,
    onDuplicate: (ShareEvent) -> Unit,
    onDelete: (ShareEvent) -> Unit
) {
    var showMore by remember { mutableStateOf(false) }
    val uiLang = if (arabic) "ar" else "en"
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                event.title.ifBlank { if (arabic) "مناسبة بدون عنوان" else "Untitled event" },
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.13f), shape = RoundedCornerShape(50)) {
                    Text(
                        "🎟️ " + eventTypeLabel(event.type, arabic),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(if (event.language == "en") "EN" else "عربي", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
            }
            val dateLine = listOf(formatEventDateShort(event.date, uiLang), formatEventTime(event.time, uiLang)).filter { it.isNotBlank() }.joinToString(" — ")
            if (dateLine.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text("📅 $dateLine", fontWeight = FontWeight.SemiBold)
            }
            if (event.place.placeName.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text("📍 ${event.place.placeName}", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
            }
            PlaceLinkNote(arabic, link)
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { onPreview(event) }, modifier = Modifier.weight(1.3f)) {
                    Text(if (arabic) "معاينة ومشاركة" else "Preview & Share")
                }
                FilledTonalButton(onClick = { onEdit(event) }, modifier = Modifier.weight(1f)) {
                    Text(if (arabic) "تعديل" else "Edit")
                }
                Box {
                    OutlinedButton(onClick = { showMore = true }) { Text(if (arabic) "المزيد" else "More") }
                    DropdownMenu(expanded = showMore, onDismissRequest = { showMore = false }) {
                        DropdownMenuItem(text = { Text(if (arabic) "نسخة لإعادة الاستخدام" else "Duplicate") }, onClick = { showMore = false; onDuplicate(event) })
                        DropdownMenuItem(text = { Text(if (arabic) "حذف" else "Delete") }, onClick = { showMore = false; onDelete(event) })
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaceLinkNote(arabic: Boolean, link: PlaceLink) {
    val text = when (link) {
        PlaceLink.CHANGED -> if (arabic) "⚠️ تغيّرت بيانات الموقع المحفوظ؛ الدعوة تستخدم البيانات القديمة حتى تحدّثها." else "⚠️ The saved place changed; this invitation keeps the old details until you update it."
        PlaceLink.DELETED -> if (arabic) "⚠️ حُذف الموقع المرتبط؛ احتُفظ ببيانات الموقع داخل المناسبة." else "⚠️ The linked place was deleted; its details are kept in this event."
        else -> null
    } ?: return
    Spacer(Modifier.height(6.dp))
    Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
}

// ======================================================================= Event editor

/**
 * Create / edit / duplicate. The invitation text follows the template until the user edits it by hand;
 * after that it is kept as written (a reset button brings back the template text).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventEditorDialog(
    arabic: Boolean,
    initial: ShareEvent,
    isNew: Boolean,
    places: List<Place>,
    customTemplates: List<InvitationTemplate>,
    onDismiss: () -> Unit,
    onSave: (ShareEvent) -> Unit
) {
    var draft by rememberSaveable(stateSaver = EditorEventSaver) { mutableStateOf(initial) }
    val baseline = rememberSaveable { eventsToJson(listOf(initial)) }
    var showPlaceMenu by remember { mutableStateOf(false) }
    var pickDate by rememberSaveable { mutableStateOf(false) }
    var pickTime by rememberSaveable { mutableStateOf<String?>(null) } // "time" or "opening"
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf("") }

    val choices = templateChoices(customTemplates, draft.language, arabic)
    val template = choices.firstOrNull { it.id == draft.templateId } ?: choices.first { it.id == defaultTemplateFor(draft.type) }
    val rendered = renderEvent(draft, template.body)
    val shownText = if (draft.textEdited) draft.invitationText else rendered.text
    val uiLang = if (arabic) "ar" else "en"

    fun requestClose() {
        if (eventsToJson(listOf(draft)) != baseline) confirmDiscard = true else onDismiss()
    }

    AlertDialog(
        onDismissRequest = { requestClose() },
        title = {
            Text(
                if (isNew) if (arabic) "مناسبة جديدة" else "New Event"
                else if (arabic) "تعديل المناسبة" else "Edit Event"
            )
        },
        text = {
            LazyColumn {
                item {
                    Text(if (arabic) "نوع المناسبة" else "Event type")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(EventTypes.all) { type ->
                            FilterChip(
                                selected = draft.type == type,
                                onClick = {
                                    val followTemplate = draft.templateId == defaultTemplateFor(draft.type) || draft.templateId.isBlank()
                                    var next = draft.copy(type = type)
                                    if (followTemplate) next = next.copy(templateId = defaultTemplateFor(type))
                                    if (type == EventTypes.PRAYER && next.prayer.isBlank()) next = next.copy(prayer = Prayers.FAJR)
                                    if (type == EventTypes.PRAYER && (next.title.isBlank() || next.title == defaultPrayerTitle(draft.prayer, draft.language))) {
                                        next = next.copy(title = defaultPrayerTitle(next.prayer, next.language))
                                    }
                                    draft = next
                                },
                                label = { Text(eventTypeLabel(type, arabic)) }
                            )
                        }
                    }

                    if (draft.type == EventTypes.PRAYER) {
                        Spacer(Modifier.height(8.dp))
                        Text(if (arabic) "الصلاة" else "Prayer")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(Prayers.all) { p ->
                                FilterChip(
                                    selected = draft.prayer == p,
                                    onClick = {
                                        val keepTitle = draft.title.isNotBlank() && draft.title != defaultPrayerTitle(draft.prayer, draft.language)
                                        draft = draft.copy(prayer = p, title = if (keepTitle) draft.title else defaultPrayerTitle(p, draft.language))
                                    },
                                    label = { Text(Prayers.label(p, arabic)) }
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(if (arabic) "لغة الدعوة" else "Invitation language")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("ar" to "عربي", "en" to "English").forEach { (lang, label) ->
                            FilterChip(
                                selected = draft.language == lang,
                                onClick = {
                                    val wasDefaultTitle = draft.type == EventTypes.PRAYER && draft.title == defaultPrayerTitle(draft.prayer, draft.language)
                                    draft = draft.copy(language = lang, title = if (wasDefaultTitle) defaultPrayerTitle(draft.prayer, lang) else draft.title)
                                },
                                label = { Text(label) }
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = draft.title,
                        onValueChange = { draft = draft.copy(title = it) },
                        label = { Text(if (arabic) "عنوان المناسبة" else "Event title") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(Modifier.height(8.dp))
                    Text(if (arabic) "الموقع المحفوظ" else "Saved place")
                    Box {
                        OutlinedButton(onClick = { showPlaceMenu = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                draft.place.placeName.ifBlank { if (arabic) "اختيار موقع من العناوين المحفوظة" else "Choose a saved place" },
                                maxLines = 1
                            )
                        }
                        DropdownMenu(expanded = showPlaceMenu, onDismissRequest = { showPlaceMenu = false }) {
                            if (places.isEmpty()) {
                                DropdownMenuItem(text = { Text(if (arabic) "لا توجد عناوين محفوظة" else "No saved places") }, onClick = { showPlaceMenu = false })
                            }
                            places.forEach { p ->
                                DropdownMenuItem(
                                    text = { Text(if (p.title.isNotBlank()) "${p.placeName} — ${p.title}" else p.placeName) },
                                    onClick = {
                                        // the remembered house photo belongs to the previous place
                                        draft = draft.copy(placeId = p.id, place = snapshotOf(p), invitationPhoto = if (p.id == draft.placeId) draft.invitationPhoto else "")
                                        showPlaceMenu = false
                                    }
                                )
                            }
                        }
                    }
                    PlaceLinkNote(arabic, placeLinkState(draft, places))

                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = draft.host,
                        onValueChange = { draft = draft.copy(host = it) },
                        label = { Text(if (arabic) "اسم المستضيف (اختياري)" else "Host name (optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { pickDate = true }, modifier = Modifier.weight(1f)) {
                            Text(formatEventDateShort(draft.date, uiLang).ifBlank { if (arabic) "📅 التاريخ" else "📅 Date" }, maxLines = 1)
                        }
                        OutlinedButton(onClick = { pickTime = "time" }, modifier = Modifier.weight(1f)) {
                            Text(
                                formatEventTime(draft.time, uiLang).ifBlank {
                                    if (draft.type == EventTypes.PRAYER) if (arabic) "⏰ وقت الصلاة" else "⏰ Prayer time"
                                    else if (arabic) "⏰ الوقت" else "⏰ Time"
                                },
                                maxLines = 1
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { pickTime = "opening" }, modifier = Modifier.weight(1f)) {
                            Text(
                                if (draft.openingTime.isBlank()) if (arabic) "🚪 وقت فتح الباب (اختياري)" else "🚪 Doors open (optional)"
                                else (if (arabic) "🚪 فتح الباب: " else "🚪 Doors open: ") + formatEventTime(draft.openingTime, uiLang),
                                maxLines = 1
                            )
                        }
                        if (draft.openingTime.isNotBlank()) {
                            TextButton(onClick = { draft = draft.copy(openingTime = "") }) { Text(if (arabic) "مسح" else "Clear") }
                        }
                    }

                    if (draft.type == EventTypes.PRAYER) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = draft.imamLine,
                            onValueChange = { draft = draft.copy(imamLine = it) },
                            label = { Text(if (arabic) "عبارة الإمامة (اختياري)" else "Imam line (optional)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = draft.description,
                        onValueChange = { draft = draft.copy(description = it) },
                        label = { Text(if (arabic) "وصف مختصر (اختياري)" else "Short description (optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = draft.notes,
                        onValueChange = { draft = draft.copy(notes = it) },
                        label = { Text(if (arabic) "تنويه عام (اختياري)" else "General note (optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2
                    )

                    Spacer(Modifier.height(10.dp))
                    Text(if (arabic) "القالب" else "Template")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(choices) { c ->
                            FilterChip(selected = template.id == c.id, onClick = { draft = draft.copy(templateId = c.id) }, label = { Text(c.name) })
                        }
                    }
                    if (rendered.unknownVariables.isNotEmpty()) {
                        Text(
                            (if (arabic) "متغيرات غير معروفة في القالب لم تُستخدم: " else "Unknown template variables were left out: ") +
                                rendered.unknownVariables.joinToString(", ") { "{$it}" },
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = shownText,
                        onValueChange = { draft = draft.copy(invitationText = it, textEdited = true) },
                        label = { Text(if (arabic) "نص الدعوة (قابل للتعديل)" else "Invitation text (editable)") },
                        modifier = Modifier.fillMaxWidth(),
                        // each line takes its direction from its own text (Arabic invitation inside an English UI and vice versa)
                        textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Content),
                        minLines = 6
                    )
                    if (draft.textEdited) {
                        Text(
                            if (arabic) "عدّلت النص يدويًا؛ لن يتغير تلقائيًا عند تغيير الحقول." else "You edited the text; it no longer follows field changes automatically.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        TextButton(onClick = { draft = draft.copy(textEdited = false, invitationText = "") }) {
                            Text(if (arabic) "إعادة النص من القالب" else "Reset text from template")
                        }
                    }

                    if (error.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(error, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (draft.title.isBlank()) {
                    error = if (arabic) "اكتب عنوان المناسبة" else "Enter the event title"
                    return@Button
                }
                val now = System.currentTimeMillis()
                onSave(
                    draft.copy(
                        templateId = template.id,
                        invitationText = shownText,
                        createdAt = if (draft.createdAt == 0L) now else draft.createdAt,
                        updatedAt = now
                    )
                )
            }) { Text(if (arabic) "حفظ" else "Save") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text(if (arabic) "إلغاء" else "Cancel") }
        }
    )

    if (pickDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = parseDateMillis(draft.date) ?: System.currentTimeMillis())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { draft = draft.copy(date = millisToDate(it)) }
                    pickDate = false
                }) { Text(if (arabic) "تم" else "OK") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text(if (arabic) "إلغاء" else "Cancel") } }
        ) { DatePicker(state = state) }
    }

    pickTime?.let { which ->
        val current = if (which == "opening") draft.openingTime else draft.time
        val (h, m) = current.split(":").mapNotNull { it.toIntOrNull() }.let { if (it.size == 2) it[0] to it[1] else 18 to 0 }
        val state = rememberTimePickerState(initialHour = h, initialMinute = m, is24Hour = false)
        AlertDialog(
            onDismissRequest = { pickTime = null },
            title = {
                Text(
                    if (which == "opening") if (arabic) "وقت فتح الباب" else "Doors open"
                    else if (draft.type == EventTypes.PRAYER) if (arabic) "وقت الصلاة" else "Prayer time"
                    else if (arabic) "الوقت" else "Time"
                )
            },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    val value = "%02d:%02d".format(Locale.ROOT, state.hour, state.minute)
                    draft = if (which == "opening") draft.copy(openingTime = value) else draft.copy(time = value)
                    pickTime = null
                }) { Text(if (arabic) "تم" else "OK") }
            },
            dismissButton = { TextButton(onClick = { pickTime = null }) { Text(if (arabic) "إلغاء" else "Cancel") } }
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(if (arabic) "تجاهل التعديلات؟" else "Discard changes?") },
            text = { Text(if (arabic) "لديك تعديلات غير محفوظة على هذه المناسبة." else "You have unsaved changes to this event.") },
            confirmButton = { Button(onClick = { confirmDiscard = false; onDismiss() }) { Text(if (arabic) "تجاهل" else "Discard") } },
            dismissButton = { OutlinedButton(onClick = { confirmDiscard = false }) { Text(if (arabic) "متابعة التعديل" else "Keep editing") } }
        )
    }
}

private fun parseDateMillis(date: String): Long? = try {
    SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC"); isLenient = false }.parse(date)?.time
} catch (_: Exception) {
    null
}

private fun millisToDate(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(java.util.Date(millis))

// ======================================================================= Preview & share

/** Shares plain text (the link stays inside the text). */
fun shareText(context: Context, text: String, chooserTitle: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, chooserTitle))
}

/** QR block reused by the event preview and the saved-place QR dialog. Returns false when the link is not usable. */
@Composable
fun QrPreview(arabic: Boolean, link: String, sizeDp: Int = 200): Boolean {
    val valid = normalizeMapUrl(link)
    if (valid == null) {
        Text(
            if (arabic) "لا يمكن إنشاء رمز QR: رابط الموقع غير صالح أو غير موجود." else "No QR code: the place link is missing or not valid.",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall
        )
        return false
    }
    val bitmap = remember(valid) { QrCodes.bitmap(valid, 480) }
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Box(modifier = Modifier.background(Color.White, RoundedCornerShape(16.dp)).padding(10.dp)) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = if (arabic) "رمز QR لرابط الموقع" else "QR code for the place link",
                modifier = Modifier.size(sizeDp.dp)
            )
        }
    }
    return true
}

/**
 * Shares the invitation text with the chosen attachments: the house photo and/or the QR image (both as FileProvider /
 * trusted content URIs). Text only → ACTION_SEND text; one image → ACTION_SEND; photo + QR → ACTION_SEND_MULTIPLE.
 */
fun shareInvitation(context: Context, text: String, photoUri: Uri?, qrFile: File?, chooserTitle: String) {
    val uris = ArrayList<Uri>()
    photoUri?.let { uris.add(it) }
    qrFile?.let { uris.add(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it)) }
    if (uris.isEmpty()) {
        shareText(context, text, chooserTitle)
        return
    }
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_STREAM, uris[0]) }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply { putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris) }
    }
    intent.type = "image/*"
    if (text.isNotBlank()) intent.putExtra(Intent.EXTRA_TEXT, text)
    val clip = ClipData.newRawUri("ShareAdd", uris[0])
    uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
    intent.clipData = clip
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, chooserTitle))
}

/** The selected house photo, shown whole (not cropped), decoded off the main thread at display size. */
@Composable
private fun HousePhotoPreview(ref: String, description: String) {
    val context = LocalContext.current
    val targetPx = with(LocalDensity.current) { 200.dp.roundToPx() }
    val bitmap by produceState(initialValue = ImageThumbnails.cached(ref, targetPx), ref, targetPx) {
        if (value == null) value = withContext(Dispatchers.IO) { ImageThumbnails.load(context, ref, targetPx) }
    }
    val shown = bitmap ?: return
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Image(
            bitmap = shown.asImageBitmap(),
            contentDescription = description,
            modifier = Modifier.heightIn(max = 180.dp).clip(RoundedCornerShape(16.dp)),
            contentScale = ContentScale.Fit
        )
    }
}

@Composable
private fun CheckRow(checked: Boolean, enabled: Boolean = true, label: String, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = if (enabled) onChange else null, enabled = enabled)
        Text(label, fontWeight = FontWeight.SemiBold)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InvitationPreviewDialog(
    arabic: Boolean,
    event: ShareEvent,
    places: List<Place>,
    customTemplates: List<InvitationTemplate>,
    onClose: () -> Unit,
    onUpdateEvent: (ShareEvent) -> Unit
) {
    val context = LocalContext.current
    val uiLang = if (arabic) "ar" else "en"
    var confirmRegenerate by remember { mutableStateOf(false) }
    val link = placeLinkState(event, places)
    val chooser = if (arabic) "مشاركة الدعوة عبر" else "Share invitation with"
    val isPrayer = event.type == EventTypes.PRAYER

    // ---- share session: values for this share only; the saved event is not changed by them
    var sessionTime by rememberSaveable(event.id) { mutableStateOf(event.time) }
    var sessionOpening by rememberSaveable(event.id) { mutableStateOf(event.openingTime) }
    var updateEventTimes by rememberSaveable(event.id) { mutableStateOf(false) }
    var includePhoto by rememberSaveable(event.id) { mutableStateOf(true) }
    var includeQr by rememberSaveable(event.id) { mutableStateOf(false) }
    var pickTimeFor by rememberSaveable(event.id) { mutableStateOf<String?>(null) } // "time" or "opening"
    var showPhotoPicker by rememberSaveable(event.id) { mutableStateOf(false) }

    val times = ShareTimes(sessionTime, sessionOpening)
    val timesChanged = times != savedTimes(event)
    val templateBody = templateChoices(customTemplates, event.language, arabic).firstOrNull { it.id == event.templateId }?.body
    val shareTextResult = shareInvitationText(event, times, templateBody)
    val text = shareTextResult.text

    // ---- house photo: one of the linked place's own images (trusted store / readable legacy URI)
    val linkedPlace = event.placeId?.let { id -> places.firstOrNull { it.id == id } }
    val photoChoice = invitationPhotoChoice(
        event,
        linkedPlace,
        usablePlaceImage = { ref -> isTrustedImageRef(context, ref) && (!File(ref).isAbsolute || File(ref).exists()) },
        usableOrphanImage = { ref -> isShareAddPrivateImage(context, ref) }
    )
    val selectedPhoto = photoChoice.selected
    val photoToShare = if (includePhoto) selectedPhoto else null

    val qrLink = normalizeMapUrl(event.place.url)
    val qrToShare = includeQr && qrLink != null

    fun regenerate(e: ShareEvent): ShareEvent? {
        val choice = templateChoices(customTemplates, e.language, arabic).firstOrNull { it.id == e.templateId } ?: return null
        return e.copy(invitationText = renderEvent(e, choice.body).text, textEdited = false, updatedAt = System.currentTimeMillis())
    }

    /**
     * Saved only through a share/copy action, in one update:
     *  - the session times, when "update the event with these times" is ticked (never otherwise);
     *  - the house photo actually shared, so the event remembers it (the place photo itself is untouched).
     */
    fun saveShareChoices(sharedPhoto: String?) {
        val now = System.currentTimeMillis()
        var updated = event
        val saveTimes = updateEventTimes && timesChanged
        if (saveTimes) updated = applyShareTimes(updated, times, shareTextResult, now)
        if (sharedPhoto != null && sharedPhoto != event.invitationPhoto) updated = updated.copy(invitationPhoto = sharedPhoto, updatedAt = now)
        if (updated == event) return
        onUpdateEvent(updated)
        if (saveTimes) {
            updateEventTimes = false
            Toast.makeText(context, if (arabic) "تم تحديث أوقات المناسبة" else "Event times updated", Toast.LENGTH_SHORT).show()
        }
    }

    fun timeLabel(value: String) = formatEventTime(value, uiLang)

    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Text(
                if (arabic) "معاينة الدعوة" else "Invitation Preview",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PlaceLinkNote(arabic, link)
                if (link == PlaceLink.CHANGED) {
                    OutlinedButton(
                        onClick = {
                            val place = places.firstOrNull { it.id == event.placeId } ?: return@OutlinedButton
                            var updated = event.copy(place = snapshotOf(place), updatedAt = System.currentTimeMillis())
                            if (!event.textEdited) regenerate(updated)?.let { updated = it }
                            onUpdateEvent(updated)
                            Toast.makeText(
                                context,
                                if (event.textEdited)
                                    if (arabic) "تم تحديث بيانات الموقع؛ نصك المعدّل بقي كما هو" else "Place details updated; your edited text was kept"
                                else
                                    if (arabic) "تم تحديث بيانات الموقع ونص الدعوة" else "Place details and invitation text updated",
                                Toast.LENGTH_LONG
                            ).show()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (arabic) "تحديث بيانات الموقع من العنوان المحفوظ" else "Update place details from the saved place") }
                }

                // ---- times for this share
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(if (arabic) "أوقات هذه المشاركة" else "Times for this share", fontWeight = FontWeight.SemiBold)
                        OutlinedButton(onClick = { pickTimeFor = "time" }, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                (if (isPrayer) if (arabic) "⏰ وقت الصلاة: " else "⏰ Prayer time: " else if (arabic) "⏰ الوقت: " else "⏰ Time: ") +
                                    timeLabel(sessionTime).ifBlank { if (arabic) "اختيار" else "Choose" },
                                maxLines = 1
                            )
                        }
                        OutlinedButton(onClick = { pickTimeFor = "opening" }, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                if (sessionOpening.isBlank()) if (arabic) "🚪 فتح الباب (اختياري)" else "🚪 Doors open (optional)"
                                else (if (arabic) "🚪 فتح الباب: " else "🚪 Doors open: ") + timeLabel(sessionOpening),
                                maxLines = 1
                            )
                        }
                        if (sessionOpening.isNotBlank()) {
                            TextButton(onClick = { sessionOpening = "" }) { Text(if (arabic) "مسح وقت فتح الباب" else "Clear doors-open time") }
                        }
                        if (timesChanged) {
                            Text(
                                (if (arabic) "لهذه المشاركة فقط. المحفوظ في المناسبة: " else "For this share only. Saved in the event: ") +
                                    listOf(timeLabel(event.time), timeLabel(event.openingTime).let { if (it.isBlank()) "" else (if (arabic) "فتح الباب " else "doors ") + it })
                                        .filter { it.isNotBlank() }.joinToString(" — ").ifBlank { "—" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            CheckRow(
                                checked = updateEventTimes,
                                label = if (arabic) "تحديث المناسبة بهذه الأوقات" else "Update the event with these times",
                                onChange = { updateEventTimes = it }
                            )
                            TextButton(onClick = { sessionTime = event.time; sessionOpening = event.openingTime; updateEventTimes = false }) {
                                Text(if (arabic) "الرجوع للأوقات المحفوظة" else "Back to saved times")
                            }
                        }
                        when (shareTextResult.source) {
                            ShareTextSource.TIMES_REPLACED -> Text(
                                if (arabic) "نصك معدّل يدويًا؛ استُبدل الوقت فيه لهذه المشاركة فقط." else "Your text is hand-edited; the time in it was replaced for this share only.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            ShareTextSource.TIMES_NOT_APPLIED -> Text(
                                if (arabic) "نصك معدّل يدويًا ولا يمكن وضع الأوقات الجديدة فيه بأمان؛ سيُشارك كما هو. عدّله من «تعديل» إن لزم."
                                else "Your text is hand-edited and the new times cannot be placed in it safely; it is shared as it is. Change it from Edit if needed.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.SemiBold
                            )
                            else -> {}
                        }
                    }
                }

                // ---- house photo
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(if (arabic) "📷 صورة المنزل" else "📷 House photo", fontWeight = FontWeight.SemiBold)
                        if (photoChoice.previousUnavailable) {
                            Text(
                                if (arabic) "صورة الدعوة المختارة سابقًا لم تعد متاحة. اختر صورة أخرى أو شارك بدون صورة."
                                else "The invitation photo chosen before is no longer available. Choose another photo or share without one.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        if (photoChoice.available.isEmpty()) {
                            if (!photoChoice.previousUnavailable) {
                                Text(
                                    if (linkedPlace == null && event.placeId != null)
                                        if (arabic) "الموقع المرتبط محذوف ولا توجد صورة محفوظة لهذه الدعوة." else "The linked place was deleted and this invitation has no saved photo."
                                    else if (arabic) "لا توجد صور محفوظة لهذا الموقع." else "This place has no saved photos.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            if (selectedPhoto != null) {
                                CheckRow(
                                    checked = includePhoto,
                                    label = if (arabic) "إرفاق صورة المنزل" else "Attach the house photo",
                                    onChange = { includePhoto = it }
                                )
                                if (includePhoto) HousePhotoPreview(selectedPhoto, if (arabic) "صورة المنزل المختارة" else "Selected house photo")
                            }
                            if (linkedPlace != null) {
                                OutlinedButton(onClick = { showPhotoPicker = true }, modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        if (selectedPhoto == null) if (arabic) "اختيار صورة (${photoChoice.available.size})" else "Choose a photo (${photoChoice.available.size})"
                                        else if (arabic) "تغيير الصورة (${photoChoice.available.size})" else "Change photo (${photoChoice.available.size})"
                                    )
                                }
                            }
                        }
                    }
                }

                // ---- QR
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        CheckRow(
                            checked = qrToShare,
                            enabled = qrLink != null,
                            label = if (arabic) "إرفاق رمز QR لرابط الموقع" else "Attach the QR code of the place link",
                            onChange = { includeQr = it }
                        )
                        if (qrLink == null || includeQr) QrPreview(arabic, event.place.url, 160)
                    }
                }

                // ---- the invitation exactly as it will be shared
                Text(if (arabic) "نص الدعوة" else "Invitation text", fontWeight = FontWeight.SemiBold)
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
                ) {
                    SelectionContainer {
                        Text(text, modifier = Modifier.fillMaxWidth().padding(14.dp), style = LocalTextStyle.current.copy(textDirection = TextDirection.Content))
                    }
                }

                val parts = listOfNotNull(
                    if (arabic) "النص" else "text",
                    if (photoToShare != null) if (arabic) "صورة المنزل" else "house photo" else null,
                    if (qrToShare) if (arabic) "رمز QR" else "QR code" else null
                )
                Text(
                    (if (arabic) "سيُرسل: " else "Will send: ") + parts.joinToString(" + "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = {
                        val photoUri = photoToShare?.let { shareAddImageUri(context, it) }
                        if (photoToShare != null && photoUri == null) {
                            Toast.makeText(
                                context,
                                if (arabic) "تعذر إرفاق صورة المنزل؛ ستُشارك الدعوة بدونها" else "The house photo could not be attached; sharing without it",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        val qrFile = if (qrToShare) QrCodes.savePng(context, qrLink!!) else null
                        saveShareChoices(if (photoUri != null) photoToShare else null)
                        shareInvitation(context, text, photoUri, qrFile, chooser)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (arabic) "مشاركة الدعوة" else "Share invitation") }
                if (qrLink != null) {
                    OutlinedButton(
                        onClick = { QrCodes.share(context, QrCodes.savePng(context, qrLink), null, chooser) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (arabic) "مشاركة رمز QR كصورة" else "Share QR image") }
                }
                OutlinedButton(
                    onClick = {
                        saveShareChoices(null)
                        copyText(context, text)
                        Toast.makeText(context, if (arabic) "تم نسخ نص الدعوة" else "Invitation text copied", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (arabic) "نسخ النص" else "Copy text") }
                TextButton(onClick = { if (event.textEdited) confirmRegenerate = true else regenerate(event)?.let(onUpdateEvent) ?: Toast.makeText(context, if (arabic) "القالب غير موجود" else "Template not found", Toast.LENGTH_SHORT).show() }) {
                    Text(if (arabic) "إعادة إنشاء النص من القالب" else "Rebuild text from template")
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onClose) { Text(if (arabic) "إغلاق" else "Close") } }
    )

    pickTimeFor?.let { which ->
        val current = if (which == "opening") sessionOpening.ifBlank { sessionTime } else sessionTime
        val (h, m) = current.split(":").mapNotNull { it.toIntOrNull() }.let { if (it.size == 2) it[0] to it[1] else 18 to 0 }
        val state = rememberTimePickerState(initialHour = h, initialMinute = m, is24Hour = false)
        AlertDialog(
            onDismissRequest = { pickTimeFor = null },
            title = {
                Text(
                    if (which == "opening") if (arabic) "وقت فتح الباب" else "Doors open"
                    else if (isPrayer) if (arabic) "وقت الصلاة" else "Prayer time"
                    else if (arabic) "الوقت" else "Time"
                )
            },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    val value = "%02d:%02d".format(Locale.ROOT, state.hour, state.minute)
                    if (which == "opening") sessionOpening = value else sessionTime = value
                    pickTimeFor = null
                }) { Text(if (arabic) "تم" else "OK") }
            },
            dismissButton = { TextButton(onClick = { pickTimeFor = null }) { Text(if (arabic) "إلغاء" else "Cancel") } }
        )
    }

    if (showPhotoPicker) {
        AlertDialog(
            onDismissRequest = { showPhotoPicker = false },
            title = { Text(if (arabic) "اختيار صورة المنزل" else "Choose the house photo") },
            text = {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(photoChoice.available.size) { index ->
                        val ref = photoChoice.available[index]
                        val isSelected = ref == selectedPhoto
                        Box(
                            modifier = Modifier
                                .border(
                                    width = if (isSelected) 3.dp else 1.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    shape = RoundedCornerShape(20.dp)
                                )
                                .padding(4.dp)
                                .clickable {
                                    // remembered for this event (an explicit choice), the place photo itself is untouched
                                    onUpdateEvent(event.copy(invitationPhoto = ref, updatedAt = System.currentTimeMillis()))
                                    includePhoto = true
                                    showPhotoPicker = false
                                }
                        ) {
                            ImagePreview(
                                ref,
                                96,
                                if (arabic) "صورة المكان ${index + 1} من ${photoChoice.available.size}" else "Place photo ${index + 1} of ${photoChoice.available.size}"
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showPhotoPicker = false }) { Text(if (arabic) "إلغاء" else "Cancel") } }
        )
    }

    if (confirmRegenerate) {
        AlertDialog(
            onDismissRequest = { confirmRegenerate = false },
            title = { Text(if (arabic) "استبدال النص المعدّل؟" else "Replace your edited text?") },
            text = { Text(if (arabic) "سيُستبدل النص الذي عدّلته يدويًا بنص القالب الحالي." else "Your hand-edited text will be replaced by the current template text.") },
            confirmButton = {
                Button(onClick = {
                    confirmRegenerate = false
                    val r = regenerate(event)
                    if (r != null) onUpdateEvent(r) else Toast.makeText(context, if (arabic) "القالب غير موجود" else "Template not found", Toast.LENGTH_SHORT).show()
                }) { Text(if (arabic) "استبدال" else "Replace") }
            },
            dismissButton = { OutlinedButton(onClick = { confirmRegenerate = false }) { Text(if (arabic) "إلغاء" else "Cancel") } }
        )
    }
}

/** QR for a saved place (from the existing share options): preview + share image / text with image. */
@Composable
fun PlaceQrDialog(arabic: Boolean, link: String, shareTextValue: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val chooser = if (arabic) "مشاركة عبر" else "Share With"
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (arabic) "رمز QR للموقع" else "Place QR Code", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val ok = QrPreview(arabic, link, 220)
                if (ok) {
                    Text(normalizeMapUrl(link) ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { QrCodes.share(context, QrCodes.savePng(context, normalizeMapUrl(link)!!), shareTextValue, chooser) }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (arabic) "مشاركة النص مع رمز QR" else "Share text with QR")
                    }
                    OutlinedButton(onClick = { QrCodes.share(context, QrCodes.savePng(context, normalizeMapUrl(link)!!), null, chooser) }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (arabic) "مشاركة رمز QR كصورة" else "Share QR image")
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onClose) { Text(if (arabic) "إغلاق" else "Close") } }
    )
}

// ======================================================================= Templates

private val SAMPLE_VALUES_AR = mapOf(
    "title" to "عنوان المناسبة", "host" to "اسم المستضيف", "date" to "الثلاثاء 20 أكتوبر 2026", "time" to "6:30 م",
    "opening_time" to "6:00 م", "place_name" to "اسم الموقع", "address" to "العنوان المختصر", "map_link" to "https://maps.app.goo.gl/…",
    "description" to "وصف مختصر", "notes" to "تنويه", "prayer" to "العشائين", "imam" to "عبارة الإمامة"
)
private val SAMPLE_VALUES_EN = mapOf(
    "title" to "Event title", "host" to "Host name", "date" to "Tuesday, October 20, 2026", "time" to "6:30 PM",
    "opening_time" to "6:00 PM", "place_name" to "Place name", "address" to "Short address", "map_link" to "https://maps.app.goo.gl/…",
    "description" to "Short description", "notes" to "Note", "prayer" to "Maghrib & Isha", "imam" to "Imam line"
)

@Composable
fun TemplateManagerDialog(
    arabic: Boolean,
    customTemplates: List<InvitationTemplate>,
    onSaveTemplate: (InvitationTemplate) -> Unit,
    onDeleteTemplate: (InvitationTemplate) -> Unit,
    onClose: () -> Unit
) {
    var editing by remember { mutableStateOf<InvitationTemplate?>(null) }
    var preview by remember { mutableStateOf<Pair<String, String>?>(null) } // name to body
    var deleting by remember { mutableStateOf<InvitationTemplate?>(null) }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (arabic) "قوالب الدعوات" else "Invitation Templates") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Button(
                        onClick = {
                            val now = System.currentTimeMillis()
                            editing = InvitationTemplate(newTemplateId(), "", EventTypes.GENERAL, if (arabic) "ar" else "en", "{title}\n{date}\n{time}\n{place_name}\n{map_link}", createdAt = now, updatedAt = now)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (arabic) "+ قالب جديد" else "+ New template") }
                    Spacer(Modifier.height(6.dp))
                    Text(if (arabic) "القوالب المدمجة" else "Built-in templates", fontWeight = FontWeight.Bold)
                }
                items(BUILT_IN_TEMPLATES) { t ->
                    Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (arabic) t.nameAr else t.nameEn, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            TextButton(onClick = { preview = (if (arabic) t.nameAr else t.nameEn) to (if (arabic) t.bodyAr else t.bodyEn) }) {
                                Text(if (arabic) "معاينة" else "Preview")
                            }
                        }
                    }
                }
                item {
                    Spacer(Modifier.height(6.dp))
                    Text(if (arabic) "قوالبي" else "My templates", fontWeight = FontWeight.Bold)
                    if (customTemplates.isEmpty()) {
                        Text(if (arabic) "لا توجد قوالب مخصصة بعد" else "No custom templates yet", style = MaterialTheme.typography.bodySmall)
                    }
                }
                items(customTemplates, key = { it.id }) { t ->
                    Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(t.name.ifBlank { if (arabic) "بدون اسم" else "Untitled" }, fontWeight = FontWeight.SemiBold)
                            Text(eventTypeLabel(t.type, arabic) + " — " + (if (t.language == "en") "EN" else "عربي"), style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TextButton(onClick = { preview = t.name to t.body }) { Text(if (arabic) "معاينة" else "Preview") }
                                TextButton(onClick = { editing = t }) { Text(if (arabic) "تعديل" else "Edit") }
                                TextButton(onClick = { deleting = t }) { Text(if (arabic) "حذف" else "Delete") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onClose) { Text(if (arabic) "إغلاق" else "Close") } }
    )

    editing?.let { t ->
        TemplateEditorDialog(arabic, t, onCancel = { editing = null }, onSave = { onSaveTemplate(it); editing = null })
    }
    preview?.let { (name, body) ->
        val r = renderInvitation(body, if (arabic) SAMPLE_VALUES_AR else SAMPLE_VALUES_EN)
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text(name) },
            text = { Column(modifier = Modifier.verticalScroll(rememberScrollState())) { Text(r.text) } },
            confirmButton = { TextButton(onClick = { preview = null }) { Text(if (arabic) "إغلاق" else "Close") } }
        )
    }
    deleting?.let { t ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(if (arabic) "حذف القالب؟" else "Delete template?") },
            text = { Text(if (arabic) "المناسبات المحفوظة تحتفظ بنص دعوتها كما هو." else "Saved events keep their invitation text as it is.") },
            confirmButton = { Button(onClick = { onDeleteTemplate(t); deleting = null }) { Text(if (arabic) "حذف" else "Delete") } },
            dismissButton = { OutlinedButton(onClick = { deleting = null }) { Text(if (arabic) "إلغاء" else "Cancel") } }
        )
    }
}

@Composable
private fun TemplateEditorDialog(arabic: Boolean, initial: InvitationTemplate, onCancel: () -> Unit, onSave: (InvitationTemplate) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial.name) }
    var type by rememberSaveable { mutableStateOf(initial.type) }
    var language by rememberSaveable { mutableStateOf(initial.language) }
    var body by rememberSaveable { mutableStateOf(initial.body) }
    var error by rememberSaveable { mutableStateOf("") }
    val unknown = unknownTemplateVariables(body)
    val sample = renderInvitation(body, if (language == "en") SAMPLE_VALUES_EN else SAMPLE_VALUES_AR)

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (arabic) "قالب دعوة" else "Invitation template") },
        text = {
            LazyColumn {
                item {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(if (arabic) "اسم القالب" else "Template name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(EventTypes.all) { t -> FilterChip(selected = type == t, onClick = { type = t }, label = { Text(eventTypeLabel(t, arabic)) }) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("ar" to "عربي", "en" to "English").forEach { (l, label) -> FilterChip(selected = language == l, onClick = { language = l }, label = { Text(label) }) }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = body, onValueChange = { body = it }, label = { Text(if (arabic) "نص القالب" else "Template text") }, modifier = Modifier.fillMaxWidth(), minLines = 6)
                    Text(
                        (if (arabic) "المتغيرات المتاحة: " else "Available variables: ") + TEMPLATE_VARIABLES.joinToString(" ") { "{$it}" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (unknown.isNotEmpty()) {
                        Text(
                            (if (arabic) "متغيرات غير معروفة لن تظهر في الدعوة: " else "Unknown variables will not appear in invitations: ") + unknown.joinToString(", ") { "{$it}" },
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(if (arabic) "معاينة بقيم تجريبية" else "Preview with sample values", fontWeight = FontWeight.SemiBold)
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)) {
                        Text(sample.text, modifier = Modifier.fillMaxWidth().padding(10.dp), style = MaterialTheme.typography.bodySmall)
                    }
                    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                when {
                    name.isBlank() -> error = if (arabic) "اكتب اسم القالب" else "Enter a template name"
                    body.isBlank() -> error = if (arabic) "اكتب نص القالب" else "Enter the template text"
                    else -> onSave(initial.copy(name = name.trim(), type = type, language = language, body = body, updatedAt = System.currentTimeMillis()))
                }
            }) { Text(if (arabic) "حفظ" else "Save") }
        },
        dismissButton = { OutlinedButton(onClick = onCancel) { Text(if (arabic) "إلغاء" else "Cancel") } }
    )
}

/** Accessibility helper for emoji-only labels in this feature. */
fun Modifier.describe(text: String): Modifier = this.semantics { contentDescription = text }
