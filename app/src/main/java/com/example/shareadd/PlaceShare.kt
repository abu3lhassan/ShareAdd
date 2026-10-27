package com.example.shareadd

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.example.shareadd.events.BUILTIN_PLACE_DETAILED
import com.example.shareadd.events.InvitationTemplate
import com.example.shareadd.events.TemplateChoice
import com.example.shareadd.events.placeShareValues
import com.example.shareadd.events.placeTemplateChoices
import com.example.shareadd.events.renderPlaceShare

/*
 * Sharing one saved place (no event needed): the text comes only from a place-sharing template, so nothing is added
 * before or after it. Notices written in the share dialog are for that share only and never stored in the place;
 * the place's private notes are never part of a share.
 */

/** The template to use: [templateId] if it is still available, else the built-in detailed template. */
fun placeShareTemplate(customTemplates: List<InvitationTemplate>, arabic: Boolean, templateId: String?): TemplateChoice {
    val choices = placeTemplateChoices(customTemplates, if (arabic) "ar" else "en", arabic)
    return choices.firstOrNull { it.id == templateId } ?: choices.first { it.id == BUILTIN_PLACE_DETAILED }
}

/** Share text of [place] with [templateBody]; [notes] = notices for this share only. */
fun placeShareText(place: Place, arabic: Boolean, templateBody: String, notes: String = ""): String =
    renderPlaceShare(
        templateBody,
        placeShareValues(
            placeName = place.placeName,
            shortAddress = place.title,
            description = place.description,
            url = place.url,
            category = if (arabic) place.categoryAr else place.categoryEn,
            rating = place.rating,
            notes = notes
        )
    ).text

/** The place template last used for sharing (a setting on this device; the built-in detailed one when unset). */
fun loadPlaceTemplateId(context: Context): String? =
    context.getSharedPreferences("shareadd_settings", Context.MODE_PRIVATE).getString("placeTemplateId", null)

fun savePlaceTemplateId(context: Context, id: String) {
    context.getSharedPreferences("shareadd_settings", Context.MODE_PRIVATE).edit().putString("placeTemplateId", id).apply()
}

/**
 * Share dialog of a place: choose the template, add notices for this share, edit the final text; the text shown is
 * exactly the text shared or copied. Closing it changes nothing (not the place, not the template).
 */
@Composable
fun PlaceShareDialog(
    place: Place,
    arabic: Boolean,
    customTemplates: List<InvitationTemplate>,
    initialTemplateId: String?,
    onDismiss: () -> Unit,
    onShareText: (text: String, templateId: String) -> Unit,
    onCopyText: (text: String, templateId: String) -> Unit,
    onShareImages: (text: String, templateId: String) -> Unit,
    onShowQr: () -> Unit,
    onManageTemplates: () -> Unit
) {
    val choices = placeTemplateChoices(customTemplates, if (arabic) "ar" else "en", arabic)
    var templateId by rememberSaveable(place.id) { mutableStateOf(placeShareTemplate(customTemplates, arabic, initialTemplateId).id) }
    var notes by rememberSaveable(place.id) { mutableStateOf("") }
    var editedText by rememberSaveable(place.id) { mutableStateOf<String?>(null) }
    var editedBase by rememberSaveable(place.id) { mutableStateOf("") }
    var pendingTemplateId by rememberSaveable(place.id) { mutableStateOf<String?>(null) }
    var confirmRebuild by rememberSaveable(place.id) { mutableStateOf(false) }

    val template = choices.firstOrNull { it.id == templateId } ?: placeShareTemplate(customTemplates, arabic, null)
    val generated = placeShareText(place, arabic, template.body, notes)
    val text = editedText ?: generated
    val editStale = editedText != null && editedBase != generated

    fun chooseTemplate(id: String) {
        if (id == templateId) return
        if (editedText != null) pendingTemplateId = id else templateId = id
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (arabic) "مشاركة المكان" else "Share Place",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("📍 ${place.placeName}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text(if (arabic) "القالب" else "Template", style = MaterialTheme.typography.bodySmall)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(choices, key = { it.id }) { c ->
                        FilterChip(selected = template.id == c.id, onClick = { chooseTemplate(c.id) }, label = { Text(c.name) })
                    }
                }
                TextButton(onClick = onManageTemplates) { Text(if (arabic) "إدارة القوالب" else "Manage templates") }
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text(if (arabic) "تنويه لهذه المشاركة (اختياري)" else "Notice for this share (optional)") },
                    supportingText = {
                        Text(if (arabic) "مثل وصف المدخل أو تعليمات الوصول؛ لا يُحفظ في المكان." else "E.g. the entrance or how to get there; not saved in the place.")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Content),
                    minLines = 3
                )
                Text(if (arabic) "نص المشاركة" else "Share text", fontWeight = FontWeight.SemiBold)
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
                ) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = {
                            if (editedText == null) editedBase = generated
                            editedText = it
                        },
                        modifier = Modifier.fillMaxWidth().padding(6.dp),
                        textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Content),
                        minLines = 6
                    )
                }
                Text(
                    if (arabic) "يمكنك تعديل النص هنا لهذه المشاركة فقط؛ ما تراه هو ما سيُرسل." else "You can edit the text here for this share only; what you see is what is sent.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (editedText != null) {
                    if (editStale) {
                        Text(
                            if (arabic) "عدّلت النص يدويًا ثم غيّرت التنويه؛ التنويه الجديد ليس في نصك. عدّله يدويًا أو أعد توليده (يستبدل تعديلاتك)."
                            else "You edited the text, then changed the notice; the new notice is not in your text. Edit it, or rebuild it (replaces your edits).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    TextButton(onClick = { confirmRebuild = true }) { Text(if (arabic) "إعادة توليد النص (يستبدل تعديلاتك)" else "Rebuild the text (replaces your edits)") }
                }
                Button(onClick = { onShareText(text, template.id) }, modifier = Modifier.fillMaxWidth()) { Text(if (arabic) "مشاركة النص" else "Share text") }
                OutlinedButton(onClick = { onCopyText(text, template.id) }, modifier = Modifier.fillMaxWidth()) { Text(if (arabic) "نسخ النص" else "Copy text") }
                if (place.images.isNotEmpty()) {
                    OutlinedButton(onClick = { onShareImages(text, template.id) }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (arabic) "مشاركة مع الصور" else "Share with images")
                    }
                }
                OutlinedButton(onClick = onShowQr, modifier = Modifier.fillMaxWidth()) { Text(if (arabic) "رمز QR للرابط" else "QR Code for the Link") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (arabic) "إغلاق" else "Close") } }
    )

    pendingTemplateId?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingTemplateId = null },
            title = { Text(if (arabic) "استبدال النص؟" else "Replace the text?") },
            text = { Text(if (arabic) "عدّلت نص المشاركة يدويًا. تطبيق قالب آخر يستبدل تعديلاتك." else "You edited the share text. Using another template replaces your edits.") },
            confirmButton = { Button(onClick = { templateId = id; editedText = null; pendingTemplateId = null }) { Text(if (arabic) "استبدال" else "Replace") } },
            dismissButton = { OutlinedButton(onClick = { pendingTemplateId = null }) { Text(if (arabic) "إلغاء" else "Cancel") } }
        )
    }
    if (confirmRebuild) {
        AlertDialog(
            onDismissRequest = { confirmRebuild = false },
            title = { Text(if (arabic) "إعادة توليد النص؟" else "Rebuild the text?") },
            text = { Text(if (arabic) "سيُستبدل نصك بنص القالب." else "Your text is replaced by the template text.") },
            confirmButton = { Button(onClick = { editedText = null; confirmRebuild = false }) { Text(if (arabic) "استبدال" else "Replace") } },
            dismissButton = { OutlinedButton(onClick = { confirmRebuild = false }) { Text(if (arabic) "إلغاء" else "Cancel") } }
        )
    }
}
