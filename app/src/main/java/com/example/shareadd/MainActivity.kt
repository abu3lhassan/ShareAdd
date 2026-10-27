package com.example.shareadd

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.provider.MediaStore
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.io.File
import java.util.UUID
import com.example.shareadd.backup.BackupFiles
import com.example.shareadd.backup.BackupSettings
import com.example.shareadd.backup.BackupSnapshot
import com.example.shareadd.backup.BackupException
import com.example.shareadd.backup.BackupProblem
import com.example.shareadd.backup.CurrentData
import com.example.shareadd.backup.ImportMode
import com.example.shareadd.backup.ImportPlan
import com.example.shareadd.backup.StagedBackup
import com.example.shareadd.backup.ImportNotice
import com.example.shareadd.backup.ImportPhase
import com.example.shareadd.backup.ImportSession
import com.example.shareadd.backup.commitImport
import com.example.shareadd.backup.isTrustedLegacyBackupRef
import com.example.shareadd.backup.planImport
import com.example.shareadd.backup.stageBackup
import com.example.shareadd.backup.validateStagedImage
import com.example.shareadd.backup.ExportOutcome
import com.example.shareadd.backup.PreviewOutcome
import com.example.shareadd.backup.consumeExportInterrupted
import com.example.shareadd.backup.exportBackup
import com.example.shareadd.backup.planForPreview
import com.example.shareadd.backup.readBackupForPreview
import com.example.shareadd.events.InvitationTemplate
import com.example.shareadd.events.EventEditorDialog
import com.example.shareadd.events.EventTypes
import com.example.shareadd.events.InvitationPreviewDialog
import com.example.shareadd.events.PlaceQrDialog
import com.example.shareadd.events.ShareEventSaver
import com.example.shareadd.events.BUILTIN_PLACE_DETAILED
import com.example.shareadd.events.BUILTIN_PLACE_SHORT
import com.example.shareadd.events.TemplateManagerDialog
import com.example.shareadd.events.defaultTemplateFor
import com.example.shareadd.events.eventsPageItems
import com.example.shareadd.events.newEventId
import com.example.shareadd.events.ShareEvent
import com.example.shareadd.events.loadEventsChecked
import com.example.shareadd.events.loadTemplatesChecked
import com.example.shareadd.events.saveEvents
import com.example.shareadd.events.saveTemplates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class Category(
    val ar: String,
    val en: String
)

data class Place(
    /** stable identity (added in v2; generated once for older records, then saved) */
    val id: String,
    val placeName: String,
    val title: String,
    val url: String,
    val description: String,
    val privateNotes: String,
    val categoryAr: String,
    val categoryEn: String,
    val images: List<String>,
    val rating: Int,
    val favorite: Boolean,
    val pinned: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

/** A backup that was read, validated and staged; nothing is applied until the user confirms. */
data class PendingImport(
    val staged: StagedBackup,
    val fileName: String
)

enum class ShareAddPage {
    Dashboard,
    SavedLocations,
    Events
}

/** LIFE-01 / SEC-01: unlocked state kept across configuration changes only (a ViewModel does not survive process death). */
class LockSession : ViewModel() {
    var unlocked: Boolean? = null
}

class MainActivity : FragmentActivity() {

    private val sharedTextState = mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // finish an import that was interrupted after its journal was written (roll forward; a failure keeps the
        // journal and blocks saving), and remove staging folders of unconfirmed previews — before any store is read.
        // Skipped while a confirmed import is still being applied in this process (e.g. after a rotation).
        ImportSession.onActivityCreate(this)

        // LIFE-01: a share is handled once; after recreation (rotation, process restore) the same intent is not replayed.
        if (savedInstanceState == null) {
            sharedTextState.value = extractSharedText(intent)
        }

        setContent {
            ShareAddApp(
                initialSharedText = sharedTextState.value,
                onSharedTextConsumed = { sharedTextState.value = "" },
                lockSession = ViewModelProvider(this)[LockSession::class.java],
                openBiometric = { arabic, onSuccess ->
                    openBiometric(arabic, onSuccess)
                }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        val sharedText = extractSharedText(intent)
        if (sharedText.isNotBlank()) {
            sharedTextState.value = sharedText
        }
    }

    private fun extractSharedText(intent: Intent?): String {
        if (intent == null) return ""
        if (intent.action != Intent.ACTION_SEND) return ""

        return intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent.getStringExtra(Intent.EXTRA_SUBJECT)
            ?: ""
    }

    private fun openBiometric(arabic: Boolean, onSuccess: () -> Unit) {
        val manager = BiometricManager.from(this)
        val canUse = manager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )

        // BIO-01: the same authenticators as before; when they are not available (no enrolled biometric / device
        // credential, or this combination is unsupported on Android 9-10), say so instead of doing nothing.
        if (canUse != BiometricManager.BIOMETRIC_SUCCESS) {
            Toast.makeText(
                this,
                if (arabic) "الفتح بالبصمة غير متاح على هذا الجهاز. استخدم رمز الدخول."
                else "Biometric unlock is not available here. Use your PIN.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }
            }
        )

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("ShareAdd")
            .setSubtitle(if (arabic) "افتح التطبيق بالبصمة أو رمز الجهاز" else "Unlock with your biometric or device credential")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()

        prompt.authenticate(info)
    }
}

@Composable
fun ShareAddApp(
    initialSharedText: String,
    onSharedTextConsumed: () -> Unit,
    lockSession: LockSession,
    openBiometric: (Boolean, () -> Unit) -> Unit
) {
    val context = LocalContext.current

    // the unlocked state survives a configuration change (rotation) but not process death: a new process starts locked
    var unlocked by remember { mutableStateOf(lockSession.unlocked ?: !hasPin(context)) }
    var showLockOverlay by remember { mutableStateOf(false) }

    var darkMode by remember { mutableStateOf(loadBoolean(context, "darkMode", true)) }
    var arabic by remember { mutableStateOf(loadBoolean(context, "arabic", true)) }

    val darkColors = darkColorScheme(
        primary = Color(0xFFB69CFF),
        secondary = Color(0xFF00E5C0),
        background = Color(0xFF07080D),
        surface = Color(0xFF151823),
        onBackground = Color.White,
        onSurface = Color.White
    )

    val lightColors = lightColorScheme(
        primary = Color(0xFF5B35D5),
        secondary = Color(0xFF00897B),
        background = Color(0xFFF7F3FF),
        surface = Color.White,
        onBackground = Color(0xFF171721),
        onSurface = Color(0xFF171721)
    )

    // F-01/F-02: stores are written only while no import is being applied / no recovery is pending
    val importState by ImportSession.state.collectAsState()

    // after an import or a recovery rewrote the stores, the settings are read again from storage — before the
    // main screen of that generation is built, so it starts with the imported language/theme
    var settingsGeneration by remember { mutableStateOf(importState.generation) }
    if (settingsGeneration != importState.generation) {
        settingsGeneration = importState.generation
        darkMode = loadBoolean(context, "darkMode", true)
        arabic = loadBoolean(context, "arabic", true)
    }

    LaunchedEffect(darkMode) {
        if (ImportSession.canAutoSave()) saveBoolean(context, "darkMode", darkMode)
    }

    LaunchedEffect(arabic) {
        if (ImportSession.canAutoSave()) saveBoolean(context, "arabic", arabic)
    }

    CompositionLocalProvider(
        LocalLayoutDirection provides if (arabic) LayoutDirection.Rtl else LayoutDirection.Ltr
    ) {
        MaterialTheme(colorScheme = if (darkMode) darkColors else lightColors) {
            if (!unlocked) {
                LockScreen(
                    arabic = arabic,
                    allowCancel = false,
                    onCancel = {},
                    onUnlock = {
                        unlocked = true
                        lockSession.unlocked = true
                    },
                    onBiometric = {
                        openBiometric(arabic) {
                            unlocked = true
                            lockSession.unlocked = true
                        }
                    }
                )
            } else if (showLockOverlay) {
                // SEC-01: while the app is locked the main screen is not composed at all, so nothing behind the
                // lock can be reached. "Back" is offered only when no PIN exists yet (create-PIN flow).
                LockScreen(
                    arabic = arabic,
                    allowCancel = !hasPin(context),
                    onCancel = { if (!hasPin(context)) showLockOverlay = false },
                    onUnlock = {
                        showLockOverlay = false
                        lockSession.unlocked = true
                    },
                    onBiometric = {
                        openBiometric(arabic) {
                            showLockOverlay = false
                            lockSession.unlocked = true
                        }
                    }
                )
            } else if (importState.phase == ImportPhase.APPLYING) {
                // the main screen (and its automatic saving) is not composed while an import is written
                ImportBusyScreen(arabic)
            } else if (importState.phase == ImportPhase.RECOVERY_REQUIRED) {
                ImportRecoveryScreen(arabic)
            } else {
                // a new generation = the stores were rewritten: the screen is rebuilt from storage, never from old memory
                key(importState.generation) {
                    ShareAddScreen(
                        initialSharedText = initialSharedText,
                        onSharedTextConsumed = onSharedTextConsumed,
                        darkMode = darkMode,
                        arabic = arabic,
                        onToggleDark = { darkMode = !darkMode },
                        onToggleArabic = { arabic = !arabic },
                        onLock = {
                            showLockOverlay = true
                            // a manual lock with a real PIN stays locked across rotation
                            if (hasPin(context)) lockSession.unlocked = false
                        }
                    )
                }
            }
        }
    }
}

/** F-01: shown instead of the main screen while a confirmed import is written (survives rotation). */
@Composable
fun ImportBusyScreen(arabic: Boolean) {
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(if (arabic) "جارٍ تطبيق الاستيراد…" else "Applying import…", fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Text(
                    if (arabic) "لا تغلق التطبيق حتى يكتمل." else "Keep the app open until it finishes.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * F-02: an import journal exists but could not be written. Saving stays blocked (the main screen is not composed),
 * the journal is kept, and the user can retry.
 */
@Composable
fun ImportRecoveryScreen(arabic: Boolean) {
    val context = LocalContext.current
    var retrying by remember { mutableStateOf(false) }
    var lastRetryFailed by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("⚠️", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (arabic) "لم يكتمل استيراد سابق" else "A previous import did not finish",
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (arabic)
                        "تعذر حفظ بيانات الاستيراد على الجهاز. أوقفنا الحفظ والتعديل حتى يكتمل، ولم تُحذف أي بيانات. أعد المحاولة؛ وإن تكرر ذلك فتأكد من وجود مساحة كافية على الجهاز."
                    else
                        "The imported data could not be written to the device. Saving and editing are paused until it completes, and no data was deleted. Try again; if it keeps failing, make sure the device has enough free space.",
                    textAlign = TextAlign.Center
                )
                if (lastRetryFailed) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (arabic) "لم تنجح المحاولة." else "The retry did not succeed.",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    enabled = !retrying,
                    onClick = {
                        retrying = true
                        ImportSession.retryRecovery(context) { ok ->
                            retrying = false
                            lastRetryFailed = !ok
                        }
                    }
                ) { Text(if (retrying) if (arabic) "جارٍ المحاولة…" else "Retrying…" else if (arabic) "إعادة المحاولة" else "Try again") }
            }
        }
    }
}

@Composable
fun DeveloperText(arabic: Boolean) {
    Text(
        text = if (arabic)
            "تم التطوير من قبل المهندس علي حسين الحنابي"
        else
            "Developed by Eng. Ali Hussain Al-Hanabi",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.secondary,
        textAlign = TextAlign.Center
    )
}

@Composable
fun LockScreen(
    arabic: Boolean,
    allowCancel: Boolean,
    onCancel: () -> Unit,
    onUnlock: () -> Unit,
    onBiometric: () -> Unit
) {
    val context = LocalContext.current
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    val firstSetup = !hasPin(context)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF07080D), Color(0xFF1B1238), Color(0xFF07080D))
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = RoundedCornerShape(34.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF151823)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                BrandLogo(size = 90)

                Spacer(Modifier.height(16.dp))

                Text(
                    text = if (firstSetup)
                        if (arabic) "إنشاء رمز دخول" else "Create PIN"
                    else
                        if (arabic) "التطبيق مقفل" else "ShareAdd Locked",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(6.dp))

                DeveloperText(arabic)

                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = pin,
                    onValueChange = {
                        if (it.length <= 6 && it.all { c -> c.isDigit() }) pin = it
                    },
                    label = { Text(if (arabic) "رمز الدخول" else "PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (firstSetup) {
                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = confirmPin,
                        onValueChange = {
                            if (it.length <= 6 && it.all { c -> c.isDigit() }) confirmPin = it
                        },
                        label = { Text(if (arabic) "تأكيد الرمز" else "Confirm PIN") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (error.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(error, color = Color(0xFFFF6B6B))
                }

                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = {
                        if (firstSetup) {
                            if (pin.length < 4) {
                                error = if (arabic) "الرمز لازم يكون 4 أرقام أو أكثر" else "PIN must be at least 4 digits"
                            } else if (pin != confirmPin) {
                                error = if (arabic) "الرمزين غير متطابقين" else "PINs do not match"
                            } else {
                                savePin(context, pin)
                                onUnlock()
                            }
                        } else {
                            if (checkPin(context, pin)) {
                                onUnlock()
                            } else {
                                error = if (arabic) "رمز غير صحيح" else "Wrong PIN"
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (firstSetup)
                            if (arabic) "حفظ الرمز" else "Save PIN"
                        else
                            if (arabic) "فتح" else "Unlock"
                    )
                }

                if (!firstSetup) {
                    Spacer(Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = onBiometric,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (arabic) "فتح بالبصمة" else "Unlock with Biometric")
                    }
                }

                if (allowCancel) {
                    Spacer(Modifier.height(8.dp))

                    TextButton(
                        onClick = onCancel,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (arabic) "رجوع" else "Back")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareAddScreen(
    initialSharedText: String,
    onSharedTextConsumed: () -> Unit,
    darkMode: Boolean,
    arabic: Boolean,
    onToggleDark: () -> Unit,
    onToggleArabic: () -> Unit,
    onLock: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    // DATA-01: when stored data cannot be read, nothing is written back over it (saving is suspended).
    val placesLoad = remember { loadPlacesChecked(context) }
    val categoriesLoad = remember { loadCategoriesChecked(context) }
    val eventsLoad = remember { loadEventsChecked(context) }
    val templatesLoad = remember { loadTemplatesChecked(context) }
    val storageWritable = placesLoad.ok && categoriesLoad.ok && eventsLoad.ok && templatesLoad.ok
    var showStorageWarning by remember { mutableStateOf(!storageWritable) }

    var places by remember { mutableStateOf(placesLoad.value) }
    var categories by remember { mutableStateOf(categoriesLoad.value) }
    var events by remember { mutableStateOf(eventsLoad.value) }
    var customTemplates by remember { mutableStateOf(templatesLoad.value) }

    // a long operation (export / import / copying images) runs in the background; only one at a time
    var busyText by remember { mutableStateOf<String?>(null) }
    var busyProgress by remember { mutableStateOf("") }
    // date of the last export that completed successfully (never updated on failure / cancel)
    var lastBackupAt by remember { mutableStateOf(loadLastBackup(context)) }

    val storageWarningText = if (arabic)
        "تعذر قراءة بيانات ShareAdd المحفوظة. لم يتم تغيير البيانات الأصلية، وحُفظت نسخة استرداد داخل التطبيق. تم إيقاف الحفظ والتعديل مؤقتًا لحماية بياناتك."
    else
        "ShareAdd could not read its saved data. The original data was not changed, and a recovery copy was kept inside the app. Saving and editing are paused to protect your data."

    fun storageBlocked(): Boolean {
        if (storageWritable && ImportSession.canAutoSave()) return false
        showStorageWarning = true
        return true
    }

    // LIFE-01: the editor draft survives activity recreation (rotation, theme/locale change, process restore).
    // Only text and image file references are saved — never bitmaps.
    var placeName by rememberSaveable { mutableStateOf("") }
    var title by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf(extractUrl(initialSharedText)) }
    var desc by rememberSaveable { mutableStateOf(if (initialSharedText.isNotBlank()) initialSharedText.take(250) else "") }
    var privateNotes by rememberSaveable { mutableStateOf("") }
    var rating by rememberSaveable { mutableStateOf(0) }
    var favorite by rememberSaveable { mutableStateOf(false) }
    var pinned by rememberSaveable { mutableStateOf(false) }
    var category by rememberSaveable(stateSaver = CategorySaver) { mutableStateOf(categories.firstOrNull() ?: defaultCategories().first()) }
    var search by remember { mutableStateOf("") }
    var images by rememberSaveable(stateSaver = StringListSaver) { mutableStateOf(listOf<String>()) }
    // DATA-04: true once the user picks a category in the editor; automatic suggestions never override it
    var categoryTouched by rememberSaveable { mutableStateOf(false) }
    // DATA-08: the draft as it was when the editor opened, to detect unsaved changes
    var draftBaseline by rememberSaveable { mutableStateOf("") }
    var showDiscardConfirm by rememberSaveable { mutableStateOf(false) }

    fun draftSignature(): String = listOf(
        placeName, title, url, desc, privateNotes, rating.toString(), favorite.toString(), pinned.toString(),
        category.ar, category.en, images.joinToString("\u0002")
    ).joinToString("\u0001")
    var message by rememberSaveable {
        mutableStateOf(
            if (initialSharedText.isNotBlank())
                if (arabic) "تم استلام رابط من المشاركة" else "Shared link received"
            else ""
        )
    }

    LaunchedEffect(Unit) {
        // F-01/F-02: outcome of an import (or journal recovery) finished by ImportSession; this screen was built from storage
        when (val notice = ImportSession.consumeNotice()) {
            is ImportNotice.Applied -> {
                val p = notice.plan
                message = if (arabic)
                    "تم تطبيق الاستيراد: أُضيف ${p.placesAdded} مكان" + (if (p.placesSkipped > 0) "، وتُخطي ${p.placesSkipped} مكرر" else "") + (if (p.placesRemoved > 0) "، واستُبدل ${p.placesRemoved}" else "") + "، و${p.imagesToCommit.size} صورة" + (if (p.events != null) "، و${p.eventsAdded} مناسبة" else "")
                else
                    "Import applied: ${p.placesAdded} place(s) added" + (if (p.placesSkipped > 0) ", ${p.placesSkipped} duplicate(s) skipped" else "") + (if (p.placesRemoved > 0) ", ${p.placesRemoved} replaced" else "") + ", ${p.imagesToCommit.size} image(s)" + (if (p.events != null) ", ${p.eventsAdded} event(s)" else "")
                Toast.makeText(context, if (arabic) "تم تطبيق الاستيراد — التفاصيل في الشاشة" else "Import applied — details on screen", Toast.LENGTH_LONG).show()
            }
            ImportNotice.FailedNoChange -> {
                message = if (arabic) "فشل الاستيراد ولم يتم تغيير أي بيانات." else "Import failed; no data was changed."
                Toast.makeText(context, if (arabic) "لم يكتمل الاستيراد — التفاصيل في الشاشة" else "Import not completed — details on screen", Toast.LENGTH_LONG).show()
            }
            ImportNotice.Recovered -> {
                message = if (arabic) "اكتمل استيراد سابق كان قد توقف قبل انتهائه." else "An import that had stopped before finishing was completed."
            }
            null -> {}
        }
        // the cancelled export (old screen) may record its interruption a moment after this screen starts
        launch {
            repeat(2) { attempt ->
                if (attempt > 0) kotlinx.coroutines.delay(2500)
                if (consumeExportInterrupted(context)) {
                    message = if (arabic)
                        "توقف التصدير السابق قبل اكتماله (أُغلقت الشاشة أو أعيد إنشاؤها) وحُذف الملف غير المكتمل. أعد التصدير."
                    else
                        "The previous export stopped before finishing (the screen was closed or recreated) and the incomplete file was removed. Please export again."
                }
            }
        }
        if (!storageWritable) return@LaunchedEffect
        if (placesLoad.repairedIds > 0) {
            message = if (arabic)
                "تم إصلاح ${placesLoad.repairedIds} معرّف مكان مكرر دون دمج أي سجل"
            else
                "${placesLoad.repairedIds} duplicate place id(s) were repaired; no records were merged"
        }
        // PERF-02: image migration (file copies) runs off the main thread; only the references it changed are
        // applied to the current list, so edits made meanwhile are not overwritten.
        val snapshot = places
        val migration = withContext(Dispatchers.IO) { migratePlacesImagesToPrivateStorage(context, snapshot) }
        if (migration.changed) {
            val replaced = HashMap<String, String>()
            snapshot.zip(migration.places).forEach { (before, after) ->
                before.images.zip(after.images).forEach { (old, new) -> if (old != new) replaced[old] = new }
            }
            places = places.map { p ->
                if (p.images.any { it in replaced }) p.copy(images = p.images.map { replaced[it] ?: it }, updatedAt = System.currentTimeMillis()) else p
            }
        }
        // DATA-06: a partial migration is reported as partial, never as full success
        if (migration.failedCount > 0) {
            message = if (arabic) {
                "تعذر تأمين ${migration.failedCount} صورة داخل ShareAdd؛ بقيت مراجعها كما هي"
            } else {
                "${migration.failedCount} image(s) could not be secured inside ShareAdd; their references were kept"
            }
        } else if (migration.changed) {
            message = if (arabic) {
                "تم تأمين صور الأماكن داخل ShareAdd"
            } else {
                "Place images secured inside ShareAdd"
            }
        }
    }

    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var editingPlaceId by rememberSaveable { mutableStateOf<String?>(null) }
    var showCategoryManager by remember { mutableStateOf(false) }
    var showPinChange by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    // Events & Invitations
    var editingEvent by rememberSaveable(stateSaver = ShareEventSaver) { mutableStateOf<ShareEvent?>(null) }
    var editingEventIsNew by rememberSaveable { mutableStateOf(false) }
    var previewEventId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteEventId by rememberSaveable { mutableStateOf<String?>(null) }
    var showTemplateManager by rememberSaveable { mutableStateOf(false) }
    var qrPlaceId by rememberSaveable { mutableStateOf<String?>(null) }
    var showImportNotice by remember { mutableStateOf(false) }
    var currentPage by rememberSaveable { mutableStateOf(ShareAddPage.Dashboard) }
    var pendingImport by remember { mutableStateOf<PendingImport?>(null) }
    var importMode by remember { mutableStateOf(ImportMode.SKIP_DUPLICATES) }
    var detailsPlace by remember { mutableStateOf<Place?>(null) }
    var shareOptionsPlace by remember { mutableStateOf<Place?>(null) }
    // place-sharing template last used on this device (null = built-in detailed)
    var placeTemplateId by remember { mutableStateOf(loadPlaceTemplateId(context)) }

    /** Text of a place share outside the share dialog (share with images, QR): the last used place template. */
    fun placeText(place: Place): String = placeShareText(place, arabic, placeShareTemplate(customTemplates, arabic, placeTemplateId).body)
    var deletePlaceId by remember { mutableStateOf<String?>(null) }

    var newCategoryAr by remember { mutableStateOf("") }
    var newCategoryEn by remember { mutableStateOf("") }
    var editingOldCategory by remember { mutableStateOf<Category?>(null) }
    var editingCategoryAr by remember { mutableStateOf("") }
    var editingCategoryEn by remember { mutableStateOf("") }

    var filterCategory by remember { mutableStateOf<Category?>(null) }
    var onlyFavorite by remember { mutableStateOf(false) }
    var onlyPinned by remember { mutableStateOf(false) }
    var sortMode by remember { mutableStateOf("pinned_newest") }

    /** Opens the editor for one saved place, targeted by its id (never by list position). */
    fun startEdit(place: Place) {
        editingPlaceId = place.id
        placeName = place.placeName
        title = place.title
        url = place.url
        desc = place.description
        privateNotes = place.privateNotes
        rating = place.rating
        favorite = place.favorite
        pinned = place.pinned
        images = place.images
        category = Category(place.categoryAr, place.categoryEn)
        categoryTouched = false
        draftBaseline = draftSignature()
        showAddDialog = true
    }

    LaunchedEffect(initialSharedText) {
        if (initialSharedText.isNotBlank()) {
            onSharedTextConsumed()
            if (storageBlocked()) return@LaunchedEffect
            val extractedUrl = extractUrl(initialSharedText)

            editingPlaceId = null
            placeName = ""
            title = ""
            url = if (extractedUrl.isNotBlank()) extractedUrl else initialSharedText.trim()
            desc = initialSharedText.take(250)
            privateNotes = ""
            rating = 0
            favorite = false
            pinned = false
            images = emptyList()
            category = categories.firstOrNull() ?: defaultCategories().first()
            category = suggestCategoryFromText(url, desc, categories, category)
            categoryTouched = false
            draftBaseline = draftSignature()
            message = if (arabic) "تم استلام موقع من المشاركة" else "Location received from share"
            showAddDialog = true
        }
    }

    LaunchedEffect(places) {
        if (storageWritable && ImportSession.canAutoSave()) savePlaces(context, places)
    }

    LaunchedEffect(events) {
        if (storageWritable && ImportSession.canAutoSave()) saveEvents(context, events)
    }

    LaunchedEffect(customTemplates) {
        if (storageWritable && ImportSession.canAutoSave()) saveTemplates(context, customTemplates)
    }

    LaunchedEffect(categories) {
        if (storageWritable && ImportSession.canAutoSave()) saveCategories(context, categories)
        if (categories.none { it.ar == category.ar && it.en == category.en } && categories.isNotEmpty()) {
            category = categories.first()
        }
    }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        // Picker cancelled or nothing selected: keep the current draft images untouched.
        if (uris.isNotEmpty() && busyText == null) {
            val remaining = (5 - images.size).coerceAtLeast(0)
            val selected = uris.take(remaining)
            val skippedForLimit = uris.size - selected.size

            busyText = if (arabic) "جارٍ إضافة الصور…" else "Adding images…"
            busyProgress = ""
            scope.launch {
                // PERF-02: copying (possibly from a cloud provider) runs off the main thread
                val copiedImages = try {
                    withContext(Dispatchers.IO) {
                        selected.mapNotNull { uri -> copyImageToPrivateStorage(context, uri) }
                    }
                } finally {
                    busyText = null
                }
                val failedCount = selected.size - copiedImages.size

                // Append to the existing draft images; never replace them.
                if (copiedImages.isNotEmpty()) {
                    images = images + copiedImages
                }

                val parts = mutableListOf<String>()
                if (copiedImages.isNotEmpty()) {
                    parts += if (arabic) "تمت إضافة ${copiedImages.size} صورة داخل ShareAdd بشكل خاص" else "${copiedImages.size} image(s) added privately inside ShareAdd"
                }
                if (failedCount > 0) {
                    parts += if (arabic) "تعذر إضافة $failedCount صورة" else "Could not add $failedCount image(s)"
                }
                if (skippedForLimit > 0) {
                    parts += if (arabic) "لم تتم إضافة $skippedForLimit صورة لتجاوز الحد الأقصى 5 صور" else "$skippedForLimit image(s) not added: maximum is 5 images"
                }
                message = parts.joinToString(" — ")

                // The status message is drawn behind the edit dialog, so warn about images that were not added.
                if (failedCount > 0 || skippedForLimit > 0) {
                    val warning = listOfNotNull(
                        if (skippedForLimit > 0) {
                            if (arabic) "لم تُضف $skippedForLimit صورة: الحد الأقصى 5 صور" else "$skippedForLimit image(s) not added: max 5 images"
                        } else null,
                        if (failedCount > 0) {
                            if (arabic) "تعذر نسخ $failedCount صورة" else "$failedCount image(s) could not be copied"
                        } else null
                    ).joinToString("\n")
                    Toast.makeText(context, warning, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null && busyText == null) {
            val snapshot = BackupSnapshot(
                places = places,
                categories = categories,
                settings = BackupSettings(arabic = arabic, darkMode = darkMode),
                events = events,
                templates = customTemplates,
                exportedAt = System.currentTimeMillis(),
                appVersionName = appVersionName(context),
                appVersionCode = appVersionCode(context)
            )
            busyText = if (arabic) "جارٍ تصدير النسخة الاحتياطية…" else "Exporting backup…"
            busyProgress = ""
            scope.launch {
                val outcome = try {
                    exportBackup(context, uri, snapshot) { done, total ->
                        busyProgress = if (arabic) "الصور: $done / $total" else "Images: $done / $total"
                    }
                } finally {
                    busyText = null
                }
                message = when (outcome) {
                    is ExportOutcome.Success -> {
                        saveLastBackup(context, snapshot.exportedAt)
                        lastBackupAt = snapshot.exportedAt
                        val s = outcome.stats
                        val missingNote = if (s.imagesMissing > 0) {
                            if (arabic) " — تعذر تضمين ${s.imagesMissing} صورة غير متاحة" else " — ${s.imagesMissing} unavailable image(s) could not be included"
                        } else ""
                        if (arabic)
                            "تم حفظ ملف النسخة الاحتياطية (الإصدار 2): ${s.places} مكان، ${s.imagesWritten} صورة، ${s.events} مناسبة، ${s.templates} قالب$missingNote. تم تسليم الملف إلى الموقع المختار؛ تأكد من اكتمال رفعه إن اخترت Google Drive. ملاحظة: الإصدارات القديمة من التطبيق لا تقرأ هذا الملف."
                        else
                            "Backup file saved (version 2): ${s.places} places, ${s.imagesWritten} images, ${s.events} events, ${s.templates} templates$missingNote. The file was handed to the chosen location; check that the upload finished if you chose Google Drive. Note: older app versions cannot read this file."
                    }
                    is ExportOutcome.Failed -> if (arabic) {
                        "فشل تصدير النسخة الاحتياطية" + if (outcome.incompleteFileRemoved) "، وتم حذف الملف غير المكتمل" else "؛ قد يبقى ملف غير مكتمل في الموقع المختار — احذفه ولا تعتمد عليه"
                    } else {
                        "Backup export failed" + if (outcome.incompleteFileRemoved) "; the incomplete file was removed" else "; an incomplete file may remain at the chosen location — delete it and do not rely on it"
                    }
                }
                // short notice; the full result stays in the status line on the main screen
                Toast.makeText(
                    context,
                    if (outcome is ExportOutcome.Success)
                        if (arabic) "تم حفظ النسخة الاحتياطية — التفاصيل في الشاشة" else "Backup saved — details on screen"
                    else
                        if (arabic) "فشل تصدير النسخة الاحتياطية" else "Backup export failed",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null && busyText == null) {
            busyText = if (arabic) "جارٍ فحص النسخة الاحتياطية…" else "Checking backup…"
            busyProgress = ""
            scope.launch {
                val outcome = try {
                    readBackupForPreview(context, uri) { done ->
                        busyProgress = if (arabic) "الصور المتحقق منها: $done" else "Images checked: $done"
                    }
                } finally {
                    busyText = null
                }
                when (outcome) {
                    is PreviewOutcome.Ready -> {
                        pendingImport = PendingImport(outcome.staged, outcome.fileName)
                        importMode = ImportMode.SKIP_DUPLICATES
                    }
                    is PreviewOutcome.Rejected -> {
                        message = when (outcome.problem) {
                            BackupProblem.UNSUPPORTED_VERSION -> if (arabic)
                                "هذا الملف من إصدار أحدث (${outcome.version}) غير مدعوم في هذا التطبيق. لم يتم تغيير أي بيانات."
                            else
                                "This backup uses a newer, unsupported version (${outcome.version}). No data was changed."
                            BackupProblem.NOT_A_BACKUP -> if (arabic) "الملف ليس نسخة احتياطية من ShareAdd. لم يتم تغيير أي بيانات." else "This file is not a ShareAdd backup. No data was changed."
                            BackupProblem.EMPTY -> if (arabic) "ملف النسخة الاحتياطية فارغ. لم يتم تغيير أي بيانات." else "The backup file is empty. No data was changed."
                            BackupProblem.IMAGE_CHECK_FAILED -> if (arabic) "صور النسخة الاحتياطية لا تطابق بصمتها (الملف تالف). لم يتم تغيير أي بيانات." else "Backup images do not match their checksums (damaged file). No data was changed."
                            BackupProblem.DAMAGED -> if (arabic) "ملف النسخة الاحتياطية تالف أو غير مكتمل. لم يتم تغيير أي بيانات." else "The backup file is damaged or incomplete. No data was changed."
                        }
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    val filtered = places.filter {
        val matchesSearch = matchesSmartSearch(it, search)

        val matchesCategory = filterCategory == null ||
                (it.categoryAr == filterCategory!!.ar && it.categoryEn == filterCategory!!.en)

        val matchesFavorite = !onlyFavorite || it.favorite
        val matchesPinned = !onlyPinned || it.pinned

        matchesSearch && matchesCategory && matchesFavorite && matchesPinned
    }.sortedWith(
        when (sortMode) {
            "rating" -> compareByDescending<Place> { it.pinned }.thenByDescending { it.rating }.thenByDescending { it.updatedAt }
            "favorite" -> compareByDescending<Place> { it.favorite }.thenByDescending { it.updatedAt }
            "oldest" -> compareBy<Place> { it.createdAt }
            "updated" -> compareByDescending<Place> { it.updatedAt }
            else -> compareByDescending<Place> { it.pinned }.thenByDescending { it.createdAt }
        }
    )

    if (showStorageWarning) {
        AlertDialog(
            onDismissRequest = { showStorageWarning = false },
            title = { Text(if (arabic) "حماية البيانات" else "Data Protection") },
            text = { Text(storageWarningText) },
            confirmButton = {
                Button(onClick = { showStorageWarning = false }) {
                    Text(if (arabic) "حسنًا" else "OK")
                }
            }
        )
    }

    if (detailsPlace != null) {
        PlaceDetailsDialog(
            place = detailsPlace!!,
            arabic = arabic,
            onClose = { detailsPlace = null },
            onOpenMap = {
                openMapLink(context, detailsPlace!!.url, arabic)
            },
            onShare = {
                shareOptionsPlace = detailsPlace
            },
            onCopyLink = {
                copyText(context, detailsPlace!!.url)
                Toast.makeText(context, if (arabic) "تم نسخ الرابط" else "Link Copied", Toast.LENGTH_SHORT).show()
            },
            onEdit = {
                if (storageBlocked()) return@PlaceDetailsDialog
                val place = detailsPlace!!
                detailsPlace = null
                startEdit(place)
            }
        )
    }

    if (shareOptionsPlace != null) {
        fun rememberTemplate(id: String) {
            placeTemplateId = id
            savePlaceTemplateId(context, id)
        }
        PlaceShareDialog(
            place = shareOptionsPlace!!,
            arabic = arabic,
            customTemplates = customTemplates,
            initialTemplateId = placeTemplateId,
            onDismiss = { shareOptionsPlace = null },
            onShareText = { text, id ->
                rememberTemplate(id)
                sharePlace(context, shareOptionsPlace!!, arabic, includeImages = false, text = text)
                shareOptionsPlace = null
            },
            onCopyText = { text, id ->
                rememberTemplate(id)
                copyText(context, text)
                Toast.makeText(context, if (arabic) "تم نسخ نص المشاركة" else "Share text copied", Toast.LENGTH_SHORT).show()
                shareOptionsPlace = null
            },
            onShareImages = { text, id ->
                rememberTemplate(id)
                sharePlace(context, shareOptionsPlace!!, arabic, includeImages = true, text = text)
                shareOptionsPlace = null
            },
            onShowQr = {
                qrPlaceId = shareOptionsPlace!!.id
                shareOptionsPlace = null
            },
            onManageTemplates = { if (!storageBlocked()) showTemplateManager = true }
        )
    }

    qrPlaceId?.let { id ->
        val place = places.firstOrNull { it.id == id }
        if (place == null) {
            qrPlaceId = null
        } else {
            PlaceQrDialog(
                arabic = arabic,
                link = place.url,
                shareTextValue = placeText(place),
                onClose = { qrPlaceId = null }
            )
        }
    }

    editingEvent?.let { draftEvent ->
        EventEditorDialog(
            arabic = arabic,
            initial = draftEvent,
            isNew = editingEventIsNew,
            places = places,
            customTemplates = customTemplates,
            onDismiss = { editingEvent = null },
            onSave = { saved ->
                events = if (events.any { it.id == saved.id }) events.map { if (it.id == saved.id) saved else it } else events + saved
                editingEvent = null
                message = if (arabic) "تم حفظ المناسبة" else "Event saved"
            }
        )
    }

    previewEventId?.let { id ->
        val event = events.firstOrNull { it.id == id }
        if (event == null) {
            previewEventId = null
        } else {
            InvitationPreviewDialog(
                arabic = arabic,
                event = event,
                places = places,
                customTemplates = customTemplates,
                onClose = { previewEventId = null },
                onUpdateEvent = { updated -> events = events.map { if (it.id == updated.id) updated else it } }
            )
        }
    }

    deleteEventId?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteEventId = null },
            title = { Text(if (arabic) "حذف المناسبة؟" else "Delete event?") },
            text = { Text(if (arabic) "سيتم حذف هذه المناسبة ونص دعوتها. لن يتأثر الموقع المحفوظ." else "This event and its invitation text will be deleted. The saved place is not affected.") },
            confirmButton = {
                Button(onClick = {
                    events = events.filterNot { it.id == id }
                    deleteEventId = null
                }) { Text(if (arabic) "حذف" else "Delete") }
            },
            dismissButton = {
                OutlinedButton(onClick = { deleteEventId = null }) { Text(if (arabic) "إلغاء" else "Cancel") }
            }
        )
    }

    if (showTemplateManager) {
        TemplateManagerDialog(
            arabic = arabic,
            customTemplates = customTemplates,
            onSaveTemplate = { t ->
                customTemplates = if (customTemplates.any { it.id == t.id }) customTemplates.map { if (it.id == t.id) t else it } else customTemplates + t
            },
            onDeleteTemplate = { t -> customTemplates = customTemplates.filterNot { it.id == t.id } },
            onClose = { showTemplateManager = false }
        )
    }

    if (deletePlaceId != null) {
        val linkedEvents = events.count { it.placeId == deletePlaceId }
        AlertDialog(
            onDismissRequest = { deletePlaceId = null },
            title = { Text(if (arabic) "تأكيد الحذف" else "Confirm Delete") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (arabic) "هل تريد حذف هذا العنوان؟" else "Do you want to delete this address?")
                    if (linkedEvents > 0) {
                        Text(
                            if (arabic)
                                "تنبيه: يرتبط بهذا المكان $linkedEvents من المناسبات. لن تُحذف، وستحتفظ ببيانات الموقع المحفوظة فيها."
                            else
                                "Note: $linkedEvents event(s) use this place. They will not be deleted and keep their saved location details.",
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val id = deletePlaceId
                        if (id != null) {
                            places = places.filterNot { it.id == id }
                        }
                        deletePlaceId = null
                    }
                ) {
                    Text(if (arabic) "حذف" else "Delete")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { deletePlaceId = null }) {
                    Text(if (arabic) "إلغاء" else "Cancel")
                }
            }
        )
    }


    if (showImportNotice) {
        AlertDialog(
            onDismissRequest = { showImportNotice = false },
            title = { Text(if (arabic) "استيراد نسخة احتياطية" else "Import Backup File") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (arabic)
                            "اختر ملف نسخة احتياطية من جهازك أو من Google Drive عبر نافذة أندرويد."
                        else
                            "Choose a backup file from your device or Google Drive using the Android file picker."
                    )
                    Text(
                        if (arabic)
                            "سيتم عرض معاينة آمنة قبل تطبيق أي تغيير على أماكنك الحالية."
                        else
                            "A safe preview will be shown before any changes are applied to your current places.",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showImportNotice = false
                        importLauncher.launch(arrayOf("application/json", "text/*"))
                    }
                ) {
                    Text(if (arabic) "اختيار ملف" else "Choose File")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showImportNotice = false }) {
                    Text(if (arabic) "إلغاء" else "Cancel")
                }
            }
        )
    }

    if (pendingImport != null) {
        val pending = pendingImport!!
        val staged = pending.staged
        val current = CurrentData(places, categories, events, customTemplates)
        val plan = remember(pending, importMode, places, categories, events, customTemplates) {
            planForPreview(context, current, staged, importMode)
        }

        fun cancelImport() {
            BackupFiles.deleteStagingDir(context, staged.stagingDir)
            pendingImport = null
        }

        AlertDialog(
            onDismissRequest = { cancelImport() },
            title = {
                Text(
                    if (arabic) "معاينة الاستيراد" else "Import Preview",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        if (arabic)
                            "راجع الملف قبل تطبيقه. لن يتم تغيير بياناتك إلا بعد الضغط على تأكيد الاستيراد."
                        else
                            "Review this backup before applying it. Your data will not change until you confirm import."
                    )

                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (pending.fileName.isNotBlank()) Text(pending.fileName, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (staged.formatVersion >= 2)
                                    if (arabic) "صيغة النسخة: الإصدار ${staged.formatVersion}" else "Backup format: version ${staged.formatVersion}"
                                else
                                    if (arabic) "صيغة النسخة: قديمة (الإصدار 1)" else "Backup format: old (version 1)"
                            )
                            Text(if (arabic) "الأماكن الحالية: ${places.size}" else "Current places: ${places.size}")
                            Text(if (arabic) "الأماكن داخل الملف: ${plan.placesInFile}" else "Places in backup: ${plan.placesInFile}")
                            Text(if (arabic) "الصور السليمة داخل الملف: ${staged.stats.imagesEmbedded}" else "Valid images in backup: ${staged.stats.imagesEmbedded}")
                            if (staged.stats.legacyRefsKept > 0) {
                                Text(if (arabic) "مراجع صور قديمة بلا بيانات: ${staged.stats.legacyRefsKept}" else "Old image references without data: ${staged.stats.legacyRefsKept}")
                            }
                            if (staged.stats.imagesRejected + staged.stats.refsRejected > 0) {
                                Text(
                                    if (arabic) "صور أو مراجع مرفوضة لعدم سلامتها: ${staged.stats.imagesRejected + staged.stats.refsRejected}" else "Rejected unsafe/invalid images or references: ${staged.stats.imagesRejected + staged.stats.refsRejected}",
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            if (staged.formatVersion >= 2) {
                                Text(if (arabic) "التصنيفات: ${staged.categories?.size ?: 0} — المناسبات: ${plan.eventsInFile} — القوالب المخصصة: ${plan.templatesInFile}" else "Categories: ${staged.categories?.size ?: 0} — Events: ${plan.eventsInFile} — Custom templates: ${plan.templatesInFile}")
                            } else {
                                Text(
                                    if (arabic)
                                        "هذه نسخة قديمة لا تحتوي التصنيفات أو الإعدادات أو المناسبات أو القوالب؛ ستبقى الموجودة لديك كما هي."
                                    else
                                        "This old backup has no categories, settings, events or templates; your existing ones stay as they are.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }

                    Text(
                        if (arabic) "طريقة الاستيراد" else "Import method",
                        fontWeight = FontWeight.SemiBold
                    )

                    ImportModeRow(
                        selected = importMode == ImportMode.SKIP_DUPLICATES,
                        title = if (arabic) "دمج آمن: تخطي المكرر" else "Safe merge: skip duplicates",
                        subtitle = if (arabic) "الأفضل حاليًا. يضيف الجديد فقط." else "Recommended. Adds new places only.",
                        onClick = { importMode = ImportMode.SKIP_DUPLICATES }
                    )

                    ImportModeRow(
                        selected = importMode == ImportMode.MERGE_ALL,
                        title = if (arabic) "دمج الكل" else "Merge all",
                        subtitle = if (arabic) "يضيف كل ما في الملف حتى لو تكرر." else "Adds everything from the file, even duplicates.",
                        onClick = { importMode = ImportMode.MERGE_ALL }
                    )

                    ImportModeRow(
                        selected = importMode == ImportMode.REPLACE_ALL,
                        title = if (arabic) "استبدال الكل" else "Replace all",
                        subtitle = if (arabic) "يحذف القائمة الحالية ويستبدلها بالملف." else "Replaces your current list with this backup.",
                        onClick = { importMode = ImportMode.REPLACE_ALL }
                    )

                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(if (arabic) "النتيجة المتوقعة" else "Expected result", fontWeight = FontWeight.SemiBold)
                            if (importMode == ImportMode.SKIP_DUPLICATES) {
                                // same plan object that is applied on confirm, so these numbers are the result
                                Text(if (arabic) "أماكن في الملف: ${plan.placesInFile}" else "Places in the file: ${plan.placesInFile}")
                                Text(if (arabic) "أماكن ستُستورد: ${plan.placesAdded}" else "Places to import: ${plan.placesAdded}")
                                Text(if (arabic) "أماكن ستُتخطى لأنها مكررة: ${plan.placesSkipped}" else "Places skipped as duplicates: ${plan.placesSkipped}")
                                Text(
                                    if (arabic)
                                        "يُعدّ المكان مكررًا إذا كان موجودًا لديك أو تكرر داخل ملف النسخة نفسه، لذلك قد يُستورد أقل من عدد أماكن الملف حتى لو كانت قائمتك فارغة."
                                    else
                                        "A place counts as a duplicate if you already have it or if it repeats inside this backup file, so fewer places than the file holds may be imported even into an empty list.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            } else {
                                Text(if (arabic) "أماكن ستُضاف: ${plan.placesAdded}" else "Places to add: ${plan.placesAdded}")
                                if (plan.placesSkipped > 0) Text(if (arabic) "مكررة ستُتخطى: ${plan.placesSkipped}" else "Duplicates skipped: ${plan.placesSkipped}")
                            }
                            if (plan.placesRemoved > 0) Text(if (arabic) "أماكن حالية ستُستبدل: ${plan.placesRemoved}" else "Current places replaced: ${plan.placesRemoved}", color = MaterialTheme.colorScheme.error)
                            Text(if (arabic) "عدد الأماكن بعد الاستيراد: ${plan.places.size}" else "Places after import: ${plan.places.size}")
                            Text(if (arabic) "صور ستُضاف: ${plan.imagesToCommit.size}" else "Images to add: ${plan.imagesToCommit.size}")
                            if (plan.placeIdsChanged > 0) Text(if (arabic) "أماكن مكررة المعرف ستأخذ معرفًا جديدًا: ${plan.placeIdsChanged}" else "Places given a new id (id already used): ${plan.placeIdsChanged}")
                            if (plan.events != null) Text(if (arabic) "مناسبات ستُضاف: ${plan.eventsAdded}" + (if (plan.eventsSkipped > 0) " — مكررة: ${plan.eventsSkipped}" else "") else "Events to add: ${plan.eventsAdded}" + (if (plan.eventsSkipped > 0) " — duplicates: ${plan.eventsSkipped}" else ""))
                            if (plan.eventsRelinked > 0) Text(if (arabic) "مناسبات سيُعاد ربطها بالمكان الصحيح: ${plan.eventsRelinked}" else "Events re-linked to the right place: ${plan.eventsRelinked}")
                            if (plan.templates != null) Text(if (arabic) "قوالب ستُضاف: ${plan.templatesAdded}" else "Templates to add: ${plan.templatesAdded}")
                            if (plan.categories != null) Text(if (arabic) "تصنيفات جديدة: ${plan.categoriesAdded}" + (if (plan.categoriesOverflow > 0) " — تجاوزت الحد 15: ${plan.categoriesOverflow}" else "") else "New categories: ${plan.categoriesAdded}" + (if (plan.categoriesOverflow > 0) " — over the 15 limit: ${plan.categoriesOverflow}" else ""))
                            if (plan.settingsInFile) {
                                Text(
                                    if (plan.settings != null)
                                        if (arabic) "سيتم تطبيق إعدادات اللغة والمظهر من الملف" else "Language and theme settings from the file will be applied"
                                    else
                                        if (arabic) "إعدادات اللغة والمظهر لا تُطبق إلا مع «استبدال الكل»" else "Language and theme settings are applied only with \"Replace all\"",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }

                    if (importMode == ImportMode.REPLACE_ALL) {
                        Text(
                            if (arabic) "تنبيه: هذا الخيار يستبدل كل الأماكن الحالية." else "Warning: this option replaces all current places.",
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (busyText != null) return@Button
                        val confirmedPlan = plan
                        pendingImport = null
                        // F-01: applied by the process-wide ImportSession, so rotation cannot cancel it half-way; this
                        // screen is replaced by a progress screen and rebuilt from storage when it finishes
                        if (!ImportSession.startApply(context, confirmedPlan, staged)) {
                            BackupFiles.deleteStagingDir(context, staged.stagingDir)
                            message = if (arabic) "يوجد استيراد آخر قيد التطبيق؛ لم يتم تغيير أي بيانات." else "Another import is being applied; no data was changed."
                        }
                    }
                ) {
                    Text(if (arabic) "تأكيد الاستيراد" else "Confirm Import")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { cancelImport() }) {
                    Text(if (arabic) "إلغاء" else "Cancel")
                }
            }
        )
    }

    if (showDiscardConfirm && showAddDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text(if (arabic) "تجاهل التعديلات؟" else "Discard changes?") },
            text = { Text(if (arabic) "لديك تعديلات غير محفوظة على هذا العنوان." else "You have unsaved changes to this address.") },
            confirmButton = {
                Button(onClick = {
                    showDiscardConfirm = false
                    showAddDialog = false
                    editingPlaceId = null
                }) {
                    Text(if (arabic) "تجاهل" else "Discard")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDiscardConfirm = false }) {
                    Text(if (arabic) "متابعة التعديل" else "Keep editing")
                }
            }
        )
    }

    if (busyText != null) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(busyText ?: "", fontWeight = FontWeight.SemiBold) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(14.dp))
                    Text(busyProgress.ifBlank { if (arabic) "يرجى الانتظار…" else "Please wait…" })
                }
            },
            confirmButton = {}
        )
    }

    if (showCategoryManager) {
        CategoryManagerDialog(
            arabic = arabic,
            categories = categories,
            newCategoryAr = newCategoryAr,
            newCategoryEn = newCategoryEn,
            editingOldCategory = editingOldCategory,
            editingCategoryAr = editingCategoryAr,
            editingCategoryEn = editingCategoryEn,
            onNewCategoryArChange = { newCategoryAr = it },
            onNewCategoryEnChange = { newCategoryEn = it },
            onEditingCategoryArChange = { editingCategoryAr = it },
            onEditingCategoryEnChange = { editingCategoryEn = it },
            onStartEdit = {
                editingOldCategory = it
                editingCategoryAr = it.ar
                editingCategoryEn = it.en
            },
            onCancelEdit = {
                editingOldCategory = null
                editingCategoryAr = ""
                editingCategoryEn = ""
            },
            onAdd = {
                val ar = newCategoryAr.trim()
                val en = newCategoryEn.trim()

                when {
                    ar.isBlank() || en.isBlank() -> {
                        message = if (arabic) "اكتب اسم التصنيف بالعربي والإنجليزي" else "Enter Arabic and English category names"
                    }
                    categories.size >= 15 -> {
                        message = if (arabic) "الحد الأقصى 15 تصنيف" else "Maximum Is 15 Categories"
                    }
                    categories.any { it.ar.equals(ar, true) || it.en.equals(en, true) } -> {
                        message = if (arabic) "التصنيف موجود مسبقًا" else "Category Already Exists"
                    }
                    else -> {
                        categories = categories + Category(ar, en)
                        newCategoryAr = ""
                        newCategoryEn = ""
                        message = if (arabic) "تمت إضافة التصنيف" else "Category Added"
                    }
                }
            },
            onSaveEdit = {
                val old = editingOldCategory
                val ar = editingCategoryAr.trim()
                val en = editingCategoryEn.trim()

                when {
                    old == null -> Unit
                    ar.isBlank() || en.isBlank() -> {
                        message = if (arabic) "اكتب الاسم العربي والإنجليزي" else "Enter Arabic and English names"
                    }
                    categories.any {
                        (it.ar.equals(ar, true) || it.en.equals(en, true)) &&
                                !(it.ar == old.ar && it.en == old.en)
                    } -> {
                        message = if (arabic) "التصنيف موجود مسبقًا" else "Category Already Exists"
                    }
                    else -> {
                        categories = categories.map {
                            if (it.ar == old.ar && it.en == old.en) Category(ar, en) else it
                        }

                        places = places.map {
                            if (it.categoryAr == old.ar && it.categoryEn == old.en)
                                it.copy(categoryAr = ar, categoryEn = en, updatedAt = System.currentTimeMillis())
                            else it
                        }

                        if (category.ar == old.ar && category.en == old.en) {
                            category = Category(ar, en)
                        }

                        editingOldCategory = null
                        editingCategoryAr = ""
                        editingCategoryEn = ""
                        message = if (arabic) "تم تعديل التصنيف" else "Category Updated"
                    }
                }
            },
            onDelete = { toDelete ->
                if (categories.size <= 1) {
                    message = if (arabic) "لا يمكن حذف آخر تصنيف" else "Cannot Delete Last Category"
                } else {
                    val fallback = categories.firstOrNull {
                        !(it.ar == toDelete.ar && it.en == toDelete.en)
                    } ?: defaultCategories().first()

                    categories = categories.filterNot {
                        it.ar == toDelete.ar && it.en == toDelete.en
                    }

                    places = places.map {
                        if (it.categoryAr == toDelete.ar && it.categoryEn == toDelete.en)
                            it.copy(categoryAr = fallback.ar, categoryEn = fallback.en, updatedAt = System.currentTimeMillis())
                        else it
                    }

                    if (category.ar == toDelete.ar && category.en == toDelete.en) {
                        category = fallback
                    }

                    message = if (arabic) "تم حذف التصنيف" else "Category Deleted"
                }
            },
            onClose = {
                showCategoryManager = false
                editingOldCategory = null
                editingCategoryAr = ""
                editingCategoryEn = ""
                newCategoryAr = ""
                newCategoryEn = ""
            }
        )
    }

    if (showPinChange) {
        ChangePinDialog(
            arabic = arabic,
            onClose = { showPinChange = false },
            onSuccess = {
                message = if (arabic) "تم تحديث إعدادات رمز الدخول" else "PIN Settings Updated"
                showPinChange = false
            }
        )
    }

    if (showAboutDialog) {
        AboutShareAddDialog(
            arabic = arabic,
            onClose = { showAboutDialog = false }
        )
    }

    if (showAddDialog) {
        AddPlaceDialog(
            arabic = arabic,
            categories = categories,
            placeName = placeName,
            title = title,
            url = url,
            desc = desc,
            privateNotes = privateNotes,
            rating = rating,
            favorite = favorite,
            pinned = pinned,
            category = category,
            images = images,
            editing = editingPlaceId != null,
            onPlaceNameChange = { placeName = it },
            onTitleChange = { title = it },
            onUrlChange = {
                url = it
                if (editingPlaceId == null && !categoryTouched) category = suggestCategoryFromText(it, desc, categories, category)
            },
            onDescChange = {
                if (it.length <= 250) {
                    desc = it
                    if (editingPlaceId == null && !categoryTouched) category = suggestCategoryFromText(url, it, categories, category)
                }
            },
            onPrivateNotesChange = { privateNotes = it },
            onRatingChange = {
                rating = it
                if (it == 5) favorite = true
            },
            onFavoriteChange = { favorite = it },
            onPinnedChange = { pinned = it },
            onCategoryChange = {
                category = it
                categoryTouched = true
            },
            onChooseImages = { imagePicker.launch(arrayOf("image/*")) },
            onRemoveImage = { removeUri ->
                images = images.filterNot { it == removeUri }
            },
            onManageCategories = { showCategoryManager = true },
            onDismiss = {
                showAddDialog = false
                editingPlaceId = null
            },
            onDismissRequest = {
                if (draftSignature() != draftBaseline) {
                    showDiscardConfirm = true
                } else {
                    showAddDialog = false
                    editingPlaceId = null
                }
            },
            onSave = {
                val checkedUrl = normalizeMapUrl(url)
                if (placeName.isNotBlank() && title.isNotBlank() && url.isNotBlank() && checkedUrl == null) {
                    // MAP-01: the dialog stays open with the entered data; the message is shown above the dialog
                    message = if (arabic) "رابط الخريطة غير صالح" else "The map link is not valid"
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                } else if (placeName.isNotBlank() && title.isNotBlank() && checkedUrl != null) {
                    val fixedUrl = checkedUrl
                    val now = System.currentTimeMillis()

                    val editedPlace = editingPlaceId?.let { id -> places.firstOrNull { it.id == id } }
                    val newPlace = Place(
                        id = editedPlace?.id ?: editingPlaceId ?: newPlaceId(),
                        placeName = placeName.trim(),
                        title = title.trim(),
                        url = fixedUrl.trim(),
                        description = desc.trim(),
                        privateNotes = privateNotes.trim(),
                        categoryAr = category.ar,
                        categoryEn = category.en,
                        images = images,
                        rating = rating,
                        favorite = favorite,
                        pinned = pinned,
                        createdAt = editedPlace?.createdAt ?: now,
                        updatedAt = now
                    )

                    places = if (editedPlace != null) {
                        places.map { place -> if (place.id == editedPlace.id) newPlace else place }
                    } else {
                        places + newPlace
                    }

                    placeName = ""
                    title = ""
                    url = ""
                    desc = ""
                    privateNotes = ""
                    rating = 0
                    favorite = false
                    pinned = false
                    images = emptyList()
                    category = categories.firstOrNull() ?: defaultCategories().first()
                    message = ""
                    editingPlaceId = null
                    showAddDialog = false
                } else {
                    message = if (arabic)
                        "فضلاً أدخل اسم العنوان والعنوان المختصر والرابط"
                    else
                        "Please Enter Address Name, Short Address, And Link"
                }
            }
        )
    }

    val gradient = if (darkMode) {
        Brush.verticalGradient(
            listOf(Color(0xFF07080D), Color(0xFF12172A), Color(0xFF07080D))
        )
    } else {
        Brush.verticalGradient(
            listOf(Color(0xFFF7F3FF), Color(0xFFEDE7FF), Color.White)
        )
    }

    fun openAddDialog() {
        if (storageBlocked()) return
        editingPlaceId = null
        placeName = ""
        title = ""
        url = ""
        desc = ""
        privateNotes = ""
        rating = 0
        favorite = false
        pinned = false
        images = emptyList()
        category = categories.firstOrNull() ?: defaultCategories().first()
        categoryTouched = false
        draftBaseline = draftSignature()
        showAddDialog = true
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                DrawerContent(
                    arabic = arabic,
                    currentPage = currentPage,
                    lastBackupAt = lastBackupAt,
                    onDashboard = {
                        currentPage = ShareAddPage.Dashboard
                        scope.launch { drawerState.close() }
                    },
                    onEvents = {
                        currentPage = ShareAddPage.Events
                        scope.launch { drawerState.close() }
                    },
                    onSavedLocations = {
                        currentPage = ShareAddPage.SavedLocations
                        scope.launch { drawerState.close() }
                    },
                    onChangePin = {
                        showPinChange = true
                        scope.launch { drawerState.close() }
                    },
                    onCategories = {
                        if (!storageBlocked()) showCategoryManager = true
                        scope.launch { drawerState.close() }
                    },
                    onAbout = {
                        showAboutDialog = true
                        scope.launch { drawerState.close() }
                    },
                    onExport = {
                        if (!storageBlocked()) exportLauncher.launch("shareadd-backup-${System.currentTimeMillis()}.json")
                        scope.launch { drawerState.close() }
                    },
                    onImport = {
                        if (!storageBlocked()) showImportNotice = true
                        scope.launch { drawerState.close() }
                    },
                    onLock = {
                        onLock()
                        scope.launch { drawerState.close() }
                    }
                )
            }
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(gradient)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(16.dp)
            ) {
                item {
                    TopQuickControls(
                        arabic = arabic,
                        darkMode = darkMode,
                        onMenu = { scope.launch { drawerState.open() } },
                        onToggleArabic = onToggleArabic,
                        onToggleDark = onToggleDark
                    )

                    Spacer(Modifier.height(10.dp))

                    HeroHeaderCard(arabic = arabic, totalPlaces = places.size)

                    if (!storageWritable) {
                        Spacer(Modifier.height(10.dp))
                        Text(storageWarningText, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                    }

                    if (message.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(message, color = MaterialTheme.colorScheme.secondary)
                    }

                    Spacer(Modifier.height(16.dp))

                    if (currentPage == ShareAddPage.Dashboard) {
                        DashboardMainContent(
                            arabic = arabic,
                            places = places,
                            categories = categories,
                            search = search,
                            filteredCount = filtered.size,
                            onSearchChange = { search = it },
                            onAdd = { openAddDialog() },
                            onOpenSavedLocations = { currentPage = ShareAddPage.SavedLocations }
                        )
                    } else if (currentPage == ShareAddPage.SavedLocations) {
                        SavedLocationsHeader(
                            arabic = arabic,
                            search = search,
                            onSearchChange = { search = it },
                            onAdd = { openAddDialog() }
                        )

                        Spacer(Modifier.height(10.dp))

                        FilterAndSortSection(
                            arabic = arabic,
                            categories = categories,
                            filterCategory = filterCategory,
                            onlyFavorite = onlyFavorite,
                            onlyPinned = onlyPinned,
                            sortMode = sortMode,
                            onFilterCategoryChange = { filterCategory = it },
                            onFavoriteChange = { onlyFavorite = it },
                            onPinnedChange = { onlyPinned = it },
                            onSortChange = { sortMode = it }
                        )

                        Spacer(Modifier.height(14.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    sharePlacesList(context, places, arabic, if (arabic) "كل العناوين" else "All Addresses")
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(if (arabic) "مشاركة الكل" else "Share All")
                            }

                            OutlinedButton(
                                onClick = {
                                    sharePlacesList(context, filtered, arabic, if (arabic) "القائمة الحالية" else "Current List")
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(if (arabic) "مشاركة الظاهر" else "Share Current")
                            }
                        }

                        Spacer(Modifier.height(18.dp))

                        Text(
                            if (arabic) "المواقع المحفوظة: ${filtered.size}" else "Saved Locations: ${filtered.size}",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(Modifier.height(10.dp))

                        if (filtered.isEmpty()) {
                            EmptyAddressesCard(arabic = arabic)
                            Spacer(Modifier.height(10.dp))
                        }
                    }
                }

                if (currentPage == ShareAddPage.Events) {
                    eventsPageItems(
                        arabic = arabic,
                        events = events,
                        places = places,
                        onNewEvent = {
                            if (!storageBlocked()) {
                                val now = System.currentTimeMillis()
                                editingEventIsNew = true
                                editingEvent = ShareEvent(
                                    id = newEventId(),
                                    type = EventTypes.GENERAL,
                                    templateId = defaultTemplateFor(EventTypes.GENERAL),
                                    language = if (arabic) "ar" else "en",
                                    // new events: weekday only; the date line is opt-in
                                    showDate = false,
                                    createdAt = 0L,
                                    updatedAt = now
                                )
                            }
                        },
                        onManageTemplates = { if (!storageBlocked()) showTemplateManager = true },
                        onPreview = { previewEventId = it.id },
                        onEdit = {
                            if (!storageBlocked()) {
                                editingEventIsNew = false
                                editingEvent = it
                            }
                        },
                        onDuplicate = {
                            if (!storageBlocked()) {
                                editingEventIsNew = true
                                editingEvent = it.copy(id = newEventId(), createdAt = 0L, updatedAt = System.currentTimeMillis())
                            }
                        },
                        onDelete = { if (!storageBlocked()) deleteEventId = it.id }
                    )
                }

                if (currentPage == ShareAddPage.SavedLocations) {
                    items(filtered, key = { it.id }) { place ->
                        PlaceCard(
                            place = place,
                            arabic = arabic,
                            onOpenExternal = {
                                openMapLink(context, place.url, arabic)
                            },
                            onShareText = {
                                shareOptionsPlace = place
                            },
                            onShareImages = {
                                sharePlace(context, place, arabic, includeImages = true, text = placeText(place))
                            },
                            onDetails = {
                                detailsPlace = place
                            },
                            onEdit = {
                                if (storageBlocked()) return@PlaceCard
                                startEdit(place)
                            },
                            onDelete = {
                                if (storageBlocked()) return@PlaceCard
                                deletePlaceId = place.id
                            },
                            onCopyLink = {
                                copyText(context, place.url)
                                Toast.makeText(context, if (arabic) "تم نسخ الرابط" else "Link Copied", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }
        }
    }
}


@Composable
fun DashboardMainContent(
    arabic: Boolean,
    places: List<Place>,
    categories: List<Category>,
    search: String,
    filteredCount: Int,
    onSearchChange: (String) -> Unit,
    onAdd: () -> Unit,
    onOpenSavedLocations: () -> Unit
) {
    OutlinedTextField(
        value = search,
        onValueChange = onSearchChange,
        label = { Text(if (arabic) "بحث سريع عن موقع" else "Quick Search Locations") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )

    Spacer(Modifier.height(10.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = onAdd,
            modifier = Modifier.weight(1f)
        ) {
            Text(if (arabic) "+ إضافة موقع" else "+ Add Location")
        }

        OutlinedButton(
            onClick = onOpenSavedLocations,
            modifier = Modifier.weight(1f)
        ) {
            Text(if (arabic) "المحفوظة" else "Saved")
        }
    }

    if (search.isNotBlank()) {
        Spacer(Modifier.height(8.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.20f))
        ) {
            Text(
                text = if (arabic)
                    "نتائج البحث الحالية: $filteredCount — افتح المواقع المحفوظة لإدارتها."
                else
                    "Current search results: $filteredCount — open Saved Locations to manage them.",
                modifier = Modifier.padding(12.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
        }
    }

    Spacer(Modifier.height(12.dp))

    StatisticsCard(
        arabic = arabic,
        places = places,
        categories = categories
    )

    Spacer(Modifier.height(12.dp))

    DashboardNavigationCard(
        arabic = arabic,
        totalPlaces = places.size,
        filteredCount = filteredCount,
        onOpenSavedLocations = onOpenSavedLocations
    )
}

@Composable
fun DashboardNavigationCard(
    arabic: Boolean,
    totalPlaces: Int,
    filteredCount: Int,
    onOpenSavedLocations: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(10.dp, RoundedCornerShape(26.dp)),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f),
                            MaterialTheme.colorScheme.surface
                        )
                    )
                )
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (arabic) "المواقع المحفوظة" else "Saved Locations",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        if (arabic)
                            "القائمة الكاملة والفلاتر والكروت في صفحة مستقلة لتقليل الازدحام."
                        else
                            "The full list, filters, and cards now live on a dedicated page to reduce clutter.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text("📍", style = MaterialTheme.typography.headlineMedium)
            }

            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCard(if (arabic) "الإجمالي" else "Total", totalPlaces.toString(), "📌", Modifier.weight(1f))
                MetricCard(if (arabic) "مطابقة البحث" else "Search Match", filteredCount.toString(), "🔎", Modifier.weight(1f))
            }

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = onOpenSavedLocations,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp)
            ) {
                Text(if (arabic) "فتح المواقع المحفوظة" else "Open Saved Locations")
            }
        }
    }
}

@Composable
fun SavedLocationsHeader(
    arabic: Boolean,
    search: String,
    onSearchChange: (String) -> Unit,
    onAdd: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                if (arabic) "المواقع المحفوظة" else "Saved Locations",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (arabic)
                    "إدارة كل العناوين، البحث، الفلترة، المشاركة، والتعديل."
                else
                    "Manage all saved places, search, filter, share, and edit.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = search,
                onValueChange = onSearchChange,
                label = { Text(if (arabic) "بحث داخل المواقع" else "Search Locations") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onAdd,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp)
            ) {
                Text(if (arabic) "+ إضافة موقع" else "+ Add Location")
            }
        }
    }
}

@Composable
fun HeroHeaderCard(arabic: Boolean, totalPlaces: Int) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(20.dp, RoundedCornerShape(36.dp)),
        shape = RoundedCornerShape(36.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
                            MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f),
                            MaterialTheme.colorScheme.surface
                        )
                    )
                )
                .padding(horizontal = 22.dp, vertical = 24.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                BrandLogo(size = 104)
                Spacer(Modifier.height(16.dp))
                Text(
                    "ShareAdd",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (arabic) "احفظ أماكنك، نظمها، وشاركها بسهولة" else "Save. Organize. Share places beautifully.",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.86f),
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))
                Surface(
                    shape = RoundedCornerShape(50.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                ) {
                    Text(
                        text = if (arabic) "العناوين المحفوظة: $totalPlaces" else "Saved places: $totalPlaces",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun StatisticsCard(
    arabic: Boolean,
    places: List<Place>,
    categories: List<Category>
) {
    val total = places.size
    val favorites = places.count { it.favorite }
    val pinned = places.count { it.pinned }
    val topRated = places.count { it.rating == 5 }
    val categoryData = categories.map { cat ->
        cat to places.count { it.categoryAr == cat.ar && it.categoryEn == cat.en }
    }.filter { it.second > 0 }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(12.dp, RoundedCornerShape(28.dp)),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            MaterialTheme.colorScheme.surface
                        )
                    )
                )
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        if (arabic) "لوحة الإحصائيات" else "Analytics Dashboard",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        if (arabic) "نظرة سريعة على بياناتك" else "A clean snapshot of your saved places",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                    )
                }
                Text("📊", style = MaterialTheme.typography.headlineLarge)
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCard(if (arabic) "الكل" else "All", total.toString(), "📍", Modifier.weight(1f))
                MetricCard(if (arabic) "مفضلة" else "Favorites", favorites.toString(), "❤️", Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCard(if (arabic) "مثبتة" else "Pinned", pinned.toString(), "📌", Modifier.weight(1f))
                MetricCard(if (arabic) "5 نجوم" else "5 Stars", topRated.toString(), "⭐", Modifier.weight(1f))
            }
            Spacer(Modifier.height(18.dp))
            if (total == 0) {
                EmptyChartMessage(arabic)
            } else {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    DonutChart(arabic, total, categoryData, Modifier.weight(0.9f))
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1.1f)) {
                        Text(if (arabic) "توزيع التصنيفات" else "Category Split", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        CategoryLegend(arabic, total, categoryData)
                    }
                }
                Spacer(Modifier.height(18.dp))
                Text(if (arabic) "أداء التصنيفات" else "Category Performance", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                CategoryBarChart(arabic, total, categoryData)
                Spacer(Modifier.height(18.dp))
                Text(if (arabic) "توزيع التقييمات" else "Rating Distribution", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                RatingBarChart(arabic, places)
            }
        }
    }
}

@Composable
fun MetricCard(title: String, value: String, emoji: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.background.copy(alpha = 0.55f)) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(emoji, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.ExtraBold)
                Text(title, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
fun EmptyChartMessage(arabic: Boolean) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.background.copy(alpha = 0.5f), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("📈", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                if (arabic) "أضف أول عنوان وستظهر الرسوم البيانية هنا" else "Add your first address and charts will appear here",
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

fun chartColor(index: Int): Color {
    val colors = listOf(
        Color(0xFF9D7CFF), Color(0xFF00E5C0), Color(0xFFFFC857),
        Color(0xFFFF6B9D), Color(0xFF4D96FF), Color(0xFF7AE582), Color(0xFFFF8A5B)
    )
    return colors[index % colors.size]
}

@Composable
fun DonutChart(arabic: Boolean, total: Int, data: List<Pair<Category, Int>>, modifier: Modifier = Modifier) {
    Box(modifier = modifier.height(170.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(150.dp)) {
            val strokeWidth = size.minDimension * 0.16f
            val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
            val topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f)
            drawArc(
                color = Color.LightGray.copy(alpha = 0.18f), startAngle = 0f, sweepAngle = 360f,
                useCenter = false, topLeft = topLeft, size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
            var startAngle = -90f
            data.forEachIndexed { index, item ->
                val sweep = (item.second.toFloat() / total.toFloat()) * 360f
                drawArc(
                    color = chartColor(index), startAngle = startAngle, sweepAngle = sweep,
                    useCenter = false, topLeft = topLeft, size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
                startAngle += sweep
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(total.toString(), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.ExtraBold)
            Text(if (arabic) "عنوان" else "Places", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun CategoryLegend(arabic: Boolean, total: Int, data: List<Pair<Category, Int>>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        data.take(5).forEachIndexed { index, item ->
            val percent = ((item.second.toFloat() / total.toFloat()) * 100f).toInt()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(12.dp).background(chartColor(index), CircleShape))
                Spacer(Modifier.width(8.dp))
                Text("${categoryName(item.first, arabic)}  $percent%", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
fun CategoryBarChart(arabic: Boolean, total: Int, data: List<Pair<Category, Int>>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        data.forEachIndexed { index, item ->
            ChartBarRow(categoryName(item.first, arabic), item.second, total, chartColor(index))
        }
    }
}

@Composable
fun RatingBarChart(arabic: Boolean, places: List<Place>) {
    val max = places.size.coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (rating in 5 downTo 1) {
            val count = places.count { it.rating == rating }
            ChartBarRow("⭐".repeat(rating), count, max, chartColor(5 - rating))
        }
        ChartBarRow(if (arabic) "بدون تقييم" else "Unrated", places.count { it.rating == 0 }, max, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
    }
}

@Composable
fun ChartBarRow(label: String, value: Int, max: Int, color: Color) {
    val fraction = if (max <= 0) 0f else (value.toFloat() / max.toFloat()).coerceIn(0f, 1f)
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontWeight = FontWeight.SemiBold)
            Text(value.toString(), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(5.dp))
        Box(modifier = Modifier.fillMaxWidth().height(12.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), RoundedCornerShape(50.dp))) {
            Box(modifier = Modifier.fillMaxWidth(fraction.coerceAtLeast(if (value > 0) 0.06f else 0f)).fillMaxHeight().background(color, RoundedCornerShape(50.dp)))
        }
    }
}

@Composable
fun FilterAndSortSection(
    arabic: Boolean,
    categories: List<Category>,
    filterCategory: Category?,
    onlyFavorite: Boolean,
    onlyPinned: Boolean,
    sortMode: String,
    onFilterCategoryChange: (Category?) -> Unit,
    onFavoriteChange: (Boolean) -> Unit,
    onPinnedChange: (Boolean) -> Unit,
    onSortChange: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                if (arabic) "فلترة وترتيب" else "Filter & Sort",
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(8.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(
                        selected = filterCategory == null,
                        onClick = { onFilterCategoryChange(null) },
                        label = { Text(if (arabic) "الكل" else "All") }
                    )
                }

                items(categories) { cat ->
                    FilterChip(
                        selected = filterCategory?.ar == cat.ar && filterCategory.en == cat.en,
                        onClick = { onFilterCategoryChange(cat) },
                        label = { Text(categoryName(cat, arabic)) }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = onlyFavorite,
                    onClick = { onFavoriteChange(!onlyFavorite) },
                    label = { Text(if (arabic) "❤️ مفضلة" else "❤️ Favorites") }
                )

                FilterChip(
                    selected = onlyPinned,
                    onClick = { onPinnedChange(!onlyPinned) },
                    label = { Text(if (arabic) "📌 مثبتة" else "📌 Pinned") }
                )
            }

            Spacer(Modifier.height(8.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(
                        selected = sortMode == "pinned_newest",
                        onClick = { onSortChange("pinned_newest") },
                        label = { Text(if (arabic) "الأحدث" else "Newest") }
                    )
                }
                item {
                    FilterChip(
                        selected = sortMode == "updated",
                        onClick = { onSortChange("updated") },
                        label = { Text(if (arabic) "آخر تعديل" else "Updated") }
                    )
                }
                item {
                    FilterChip(
                        selected = sortMode == "rating",
                        onClick = { onSortChange("rating") },
                        label = { Text(if (arabic) "الأعلى تقييمًا" else "Top Rated") }
                    )
                }
                item {
                    FilterChip(
                        selected = sortMode == "favorite",
                        onClick = { onSortChange("favorite") },
                        label = { Text(if (arabic) "المفضلة" else "Favorites") }
                    )
                }
            }
        }
    }
}

@Composable
fun TopQuickControls(
    arabic: Boolean,
    darkMode: Boolean,
    onMenu: () -> Unit,
    onToggleArabic: () -> Unit,
    onToggleDark: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Surface(
            modifier = Modifier
                .height(58.dp)
                .width(86.dp),
            shape = RoundedCornerShape(50.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f),
            tonalElevation = 6.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f))
        ) {
            TextButton(
                onClick = onMenu,
                shape = RoundedCornerShape(50.dp),
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                Text(
                    "☰",
                    modifier = Modifier.semantics { contentDescription = if (arabic) "فتح القائمة" else "Open menu" },
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Surface(
            modifier = Modifier.height(58.dp),
            shape = RoundedCornerShape(50.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f),
            tonalElevation = 6.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TextButton(
                    onClick = onToggleArabic,
                    shape = RoundedCornerShape(50.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = if (arabic) "عربي" else "EN",
                        modifier = Modifier.semantics {
                            contentDescription = if (arabic) "اللغة العربية، اضغط للتبديل إلى الإنجليزية" else "English, tap to switch to Arabic"
                        },
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(28.dp)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.26f))
                )

                TextButton(
                    onClick = onToggleDark,
                    shape = RoundedCornerShape(50.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = if (darkMode) "☀️" else "🌙",
                        modifier = Modifier.semantics {
                            contentDescription = if (darkMode)
                                if (arabic) "التبديل إلى الوضع الفاتح" else "Switch to light mode"
                            else
                                if (arabic) "التبديل إلى الوضع الداكن" else "Switch to dark mode"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun EmptyAddressesCard(arabic: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "📍",
                style = MaterialTheme.typography.headlineLarge
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = if (arabic) "لا توجد عناوين محفوظة بعد" else "No Saved Addresses Yet",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(6.dp))

            Text(
                text = if (arabic)
                    "اضغط + إضافة عنوان أو شارك موقعًا من Google Maps إلى ShareAdd."
                else
                    "Tap + Add Address or share a location from Google Maps to ShareAdd.",
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun DrawerContent(
    arabic: Boolean,
    currentPage: ShareAddPage,
    lastBackupAt: Long,
    onDashboard: () -> Unit,
    onSavedLocations: () -> Unit,
    onEvents: () -> Unit,
    onChangePin: () -> Unit,
    onCategories: () -> Unit,
    onAbout: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onLock: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .padding(18.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            )
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                BrandLogo(size = 78)

                Spacer(Modifier.height(12.dp))

                Text(
                    "ShareAdd",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    if (arabic) "احفظ أماكنك وشاركها بسهولة" else "Save places. Share beautifully.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        Text(
            if (arabic) "القائمة" else "Menu",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(10.dp))

        DrawerButton(
            text = if (arabic) "🏠 الرئيسية" else "🏠 Dashboard",
            selected = currentPage == ShareAddPage.Dashboard,
            onClick = onDashboard
        )
        DrawerButton(
            text = if (arabic) "📍 المواقع المحفوظة" else "📍 Saved Locations",
            selected = currentPage == ShareAddPage.SavedLocations,
            onClick = onSavedLocations
        )
        DrawerButton(
            text = if (arabic) "🎟️ المناسبات والدعوات" else "🎟️ Events & Invitations",
            selected = currentPage == ShareAddPage.Events,
            onClick = onEvents
        )
        DrawerButton(if (arabic) "🗂️ التصنيفات" else "🗂️ Categories", onCategories)
        DrawerButton(if (arabic) "🔐 تغيير الرمز" else "🔐 Change PIN", onChangePin)

        Spacer(Modifier.height(10.dp))

        Text(
            if (arabic) "النسخ والبيانات" else "Backup & Data",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(Modifier.height(8.dp))

        DrawerButton(if (arabic) "☁️ تصدير نسخة احتياطية" else "☁️ Export Backup File", onExport)
        DrawerButton(if (arabic) "📥 استيراد نسخة احتياطية" else "📥 Import Backup File", onImport)

        if (lastBackupAt > 0L) {
            Text(
                if (arabic) "آخر تصدير ناجح للملف: ${formatDate(lastBackupAt, arabic)}" else "Last successful export: ${formatDate(lastBackupAt, arabic)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            )
        }

        Text(
            if (arabic)
                "يمكنك اختيار Google Drive من نافذة أندرويد عند التصدير أو الاستيراد."
            else
                "You can choose Google Drive from the Android file picker when exporting or importing.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )

        Spacer(Modifier.weight(1f))

        DrawerButton(if (arabic) "ℹ️ عن ShareAdd" else "ℹ️ About ShareAdd", onAbout)
        DrawerButton(if (arabic) "🔒 قفل التطبيق" else "🔒 Lock App", onLock)

        Spacer(Modifier.height(12.dp))

        Text(
            if (arabic) "تصميم وتطوير المهندس علي حسين الحنابي" else "Designed & developed by Eng. Ali Hussain Al-Hanabi",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun DrawerButton(text: String, onClick: () -> Unit, selected: Boolean = false) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected)
                MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
        ),
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
            else MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
        )
    ) {
        Text(
            text = text,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Start,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun ImportModeRow(
    selected: Boolean,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (selected)
            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        else
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
            else MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = selected, onClick = onClick)

            Spacer(Modifier.width(8.dp))

            Column {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun AboutShareAddDialog(
    arabic: Boolean,
    onClose: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Button(
                    onClick = onClose,
                    shape = RoundedCornerShape(50.dp)
                ) {
                    Text(if (arabic) "تم" else "Done")
                }
            }
        },
        title = {
            Text(
                text = if (arabic) "عن‎ ShareAdd" else "About ShareAdd",
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    BrandLogo(size = 72)

                    Spacer(Modifier.height(14.dp))

                    Text(
                        text = "ShareAdd",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )

                    Spacer(Modifier.height(6.dp))

                    Text(
                        text = if (arabic)
                            "احفظ أماكنك، نظمها، وشاركها بسهولة"
                        else
                            "Save. Organize. Share places beautifully.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    Spacer(Modifier.height(18.dp))

                    HorizontalDivider()

                    Spacer(Modifier.height(18.dp))

                    Text(
                        text = if (arabic) "تصميم وتطوير" else "Designed & developed by",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.secondary,
                        textAlign = TextAlign.Center
                    )

                    Spacer(Modifier.height(4.dp))

                    Text(
                        text = if (arabic)
                            "المهندس علي حسين الحنابي"
                        else
                            "Eng. Ali Hussain Al-Hanabi",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )

                    Spacer(Modifier.height(12.dp))

                    Text(
                        text = if (arabic)
                            "تطبيق شخصي لإدارة الأماكن والعناوين ومشاركتها بشكل عملي وأنيق."
                        else
                            "A personal app for managing and sharing places in a practical, elegant way.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    )
}

@Composable
fun AddPlaceDialog(
    arabic: Boolean,
    categories: List<Category>,
    placeName: String,
    title: String,
    url: String,
    desc: String,
    privateNotes: String,
    rating: Int,
    favorite: Boolean,
    pinned: Boolean,
    category: Category,
    images: List<String>,
    editing: Boolean,
    onPlaceNameChange: (String) -> Unit,
    onTitleChange: (String) -> Unit,
    onUrlChange: (String) -> Unit,
    onDescChange: (String) -> Unit,
    onPrivateNotesChange: (String) -> Unit,
    onRatingChange: (Int) -> Unit,
    onFavoriteChange: (Boolean) -> Unit,
    onPinnedChange: (Boolean) -> Unit,
    onCategoryChange: (Category) -> Unit,
    onChooseImages: () -> Unit,
    onRemoveImage: (String) -> Unit,
    onManageCategories: () -> Unit,
    onDismiss: () -> Unit,
    onDismissRequest: () -> Unit,
    onSave: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(
                if (editing)
                    if (arabic) "تعديل العنوان" else "Edit Address"
                else
                    if (arabic) "إضافة عنوان جديد" else "Add New Address"
            )
        },
        text = {
            LazyColumn {
                item {
                    OutlinedTextField(
                        value = placeName,
                        onValueChange = onPlaceNameChange,
                        label = { Text(if (arabic) "اسم العنوان: البيت 🏠 / المكتب 🏢" else "Address Name: Home 🏠 / Office 🏢") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = title,
                        onValueChange = onTitleChange,
                        label = { Text(if (arabic) "العنوان المختصر 😊" else "Short Address 😊") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = url,
                        onValueChange = onUrlChange,
                        label = { Text(if (arabic) "رابط Google Maps" else "Google Maps Link") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                    )

                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = desc,
                        onValueChange = onDescChange,
                        label = { Text(if (arabic) "الوصف ${desc.length}/250 😊" else "Description ${desc.length}/250 😊") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2
                    )

                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = privateNotes,
                        onValueChange = onPrivateNotesChange,
                        label = { Text(if (arabic) "ملاحظات خاصة" else "Private Notes") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2
                    )

                    Spacer(Modifier.height(12.dp))

                    Text(if (arabic) "التقييم" else "Rating")

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            FilterChip(
                                selected = rating == 0,
                                onClick = { onRatingChange(0) },
                                label = { Text(if (arabic) "بدون" else "None") }
                            )
                        }

                        items(listOf(1, 2, 3, 4, 5)) { star ->
                            FilterChip(
                                selected = rating == star,
                                onClick = { onRatingChange(star) },
                                label = { Text("⭐".repeat(star)) }
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = favorite,
                            onClick = { onFavoriteChange(!favorite) },
                            label = { Text(if (arabic) "❤️ مفضلة" else "❤️ Favorite") }
                        )

                        FilterChip(
                            selected = pinned,
                            onClick = { onPinnedChange(!pinned) },
                            label = { Text(if (arabic) "📌 تثبيت" else "📌 Pin") }
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    Text(if (arabic) "التصنيف" else "Category")

                    Spacer(Modifier.height(8.dp))

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(categories) { item ->
                            FilterChip(
                                selected = category.ar == item.ar && category.en == item.en,
                                onClick = { onCategoryChange(item) },
                                label = { Text(categoryName(item, arabic)) }
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = onManageCategories,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (arabic) "إدارة التصنيفات (${categories.size}/15)" else "Manage Categories (${categories.size}/15)")
                    }

                    Spacer(Modifier.height(12.dp))

                    Button(
                        onClick = onChooseImages,
                        enabled = images.size < 5,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (arabic) "اختيار الصور ${images.size}/5" else "Choose Images ${images.size}/5")
                    }

                    if (images.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            itemsIndexed(images) { index, uri ->
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    ImagePreview(
                                        uri, 92,
                                        contentDescription = if (arabic) "صورة المكان ${index + 1} من ${images.size}" else "Place image ${index + 1} of ${images.size}"
                                    )
                                    TextButton(onClick = { onRemoveImage(uri) }) {
                                        Text(
                                            if (arabic) "حذف" else "Remove",
                                            modifier = Modifier.semantics {
                                                contentDescription = if (arabic) "حذف الصورة ${index + 1}" else "Remove image ${index + 1}"
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onSave) {
                Text(if (arabic) "حفظ" else "Save")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(if (arabic) "إلغاء" else "Cancel")
            }
        }
    )
}

@Composable
fun PlaceDetailsDialog(
    place: Place,
    arabic: Boolean,
    onClose: () -> Unit,
    onOpenMap: () -> Unit,
    onShare: () -> Unit,
    onCopyLink: () -> Unit,
    onEdit: () -> Unit
) {
    val categoryText = if (arabic) place.categoryAr else place.categoryEn
    val ratingText = if (place.rating == 0) "-" else "${place.rating}/5"
    val statusText = listOfNotNull(
        if (place.favorite) if (arabic) "مفضلة" else "Favorite" else null,
        if (place.pinned) if (arabic) "مثبتة" else "Pinned" else null
    ).joinToString(" • ")

    AlertDialog(
        onDismissRequest = onClose,
        shape = RoundedCornerShape(30.dp),
        containerColor = Color(0xFF2E2A35),
        title = {
            Text(
                text = if (arabic) "تفاصيل المكان" else "Place Details",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF423D49), RoundedCornerShape(26.dp))
                            .padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (place.images.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(190.dp)
                                    .background(Color(0xFF211D29), RoundedCornerShape(24.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                ImagePreview(place.images.first(), 180, contentDescription = if (arabic) "صورة المكان 1" else "Place image 1")
                            }
                            Spacer(Modifier.height(14.dp))
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(84.dp)
                                    .background(Color(0xFF7C4DFF), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("📍", style = MaterialTheme.typography.headlineLarge)
                            }
                            Spacer(Modifier.height(12.dp))
                        }

                        Text(
                            text = place.placeName.ifBlank { if (arabic) "مكان بدون اسم" else "Unnamed Place" },
                            color = Color(0xFFB695FF),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.ExtraBold,
                            textAlign = TextAlign.Center
                        )

                        Spacer(Modifier.height(6.dp))

                        Text(
                            text = place.title.ifBlank { if (arabic) "لا يوجد عنوان مختصر" else "No short address" },
                            color = Color(0xFFE7E0EE),
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center
                        )

                        Spacer(Modifier.height(14.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            PremiumDetailChip(
                                text = "🏷️ $categoryText",
                                modifier = Modifier.weight(1f)
                            )
                            PremiumDetailChip(
                                text = "⭐ $ratingText",
                                modifier = Modifier.weight(1f)
                            )
                        }

                        if (statusText.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            PremiumDetailChip(
                                text = statusText,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                item {
                    PremiumDetailSection(
                        title = if (arabic) "الوصف" else "Description",
                        body = place.description.ifBlank { if (arabic) "لا يوجد وصف" else "No description" }
                    )
                }

                item {
                    PremiumDetailSection(
                        title = if (arabic) "ملاحظات خاصة" else "Private Notes",
                        body = place.privateNotes.ifBlank { if (arabic) "لا توجد ملاحظات خاصة" else "No private notes" }
                    )
                }

                item {
                    PremiumDetailSection(
                        title = if (arabic) "رابط الخريطة" else "Map Link",
                        body = place.url.ifBlank { "-" }
                    )
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        PremiumMiniInfo(
                            title = if (arabic) "أضيف" else "Created",
                            value = formatDate(place.createdAt, arabic),
                            modifier = Modifier.weight(1f)
                        )
                        PremiumMiniInfo(
                            title = if (arabic) "آخر تعديل" else "Updated",
                            value = formatDate(place.updatedAt, arabic),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                if (place.images.size > 1) {
                    item {
                        Text(
                            text = if (arabic) "صور المكان" else "Place Images",
                            color = Color(0xFFB695FF),
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            itemsIndexed(place.images.drop(1)) { index, uri ->
                                ImagePreview(uri, 104, contentDescription = if (arabic) "صورة المكان ${index + 2}" else "Place image ${index + 2}")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onOpenMap,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9F82FF))
                    ) {
                        Text(if (arabic) "الخريطة" else "Map")
                    }
                    Button(
                        onClick = onShare,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00CDB8))
                    ) {
                        Text(if (arabic) "مشاركة" else "Share")
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onCopyLink,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Text(if (arabic) "نسخ" else "Copy")
                    }
                    OutlinedButton(
                        onClick = onEdit,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Text(if (arabic) "تعديل" else "Edit")
                    }
                    OutlinedButton(
                        onClick = onClose,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Text(if (arabic) "إغلاق" else "Close")
                    }
                }
            }
        }
    )
}

@Composable
fun PremiumDetailChip(
    text: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(Color(0xFF332E3B), RoundedCornerShape(18.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color(0xFFEFE7FF),
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun PremiumDetailSection(
    title: String,
    body: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF393440), RoundedCornerShape(22.dp))
            .padding(16.dp)
    ) {
        Text(
            text = title,
            color = Color(0xFF19E0C6),
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = body,
            color = Color(0xFFE9E3EE),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
fun PremiumMiniInfo(
    title: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(Color(0xFF393440), RoundedCornerShape(20.dp))
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            color = Color(0xFF19E0C6),
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = value,
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun ChangePinDialog(
    arabic: Boolean,
    onClose: () -> Unit,
    onSuccess: () -> Unit
) {
    val context = LocalContext.current

    var oldPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    // PIN-02: without an existing PIN this dialog creates one; with a PIN the current PIN is always required
    val pinExists = remember { hasPin(context) }

    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Text(
                if (pinExists)
                    if (arabic) "تغيير رمز الدخول" else "Change PIN"
                else
                    if (arabic) "إنشاء رمز دخول" else "Create PIN"
            )
        },
        text = {
            Column {
                if (pinExists) OutlinedTextField(
                    value = oldPin,
                    onValueChange = {
                        if (it.length <= 6 && it.all { c -> c.isDigit() }) oldPin = it
                    },
                    label = { Text(if (arabic) "الرمز الحالي" else "Current PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (pinExists) Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = newPin,
                    onValueChange = {
                        if (it.length <= 6 && it.all { c -> c.isDigit() }) newPin = it
                    },
                    label = {
                        Text(
                            if (pinExists)
                                if (arabic) "الرمز الجديد - اتركه فاضي لحذف القفل" else "New PIN - Leave Empty To Remove Lock"
                            else
                                if (arabic) "الرمز الجديد (4 أرقام أو أكثر)" else "New PIN (4 digits or more)"
                        )
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = confirmPin,
                    onValueChange = {
                        if (it.length <= 6 && it.all { c -> c.isDigit() }) confirmPin = it
                    },
                    label = { Text(if (arabic) "تأكيد الرمز الجديد" else "Confirm New PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (error.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(error, color = Color(0xFFFF6B6B))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    when {
                        pinExists && !checkPin(context, oldPin) -> {
                            error = if (arabic) "الرمز الحالي غير صحيح" else "Current PIN Is Incorrect"
                        }
                        pinExists && newPin.isBlank() && confirmPin.isBlank() -> {
                            clearPin(context)
                            onSuccess()
                        }
                        !pinExists && newPin.length < 4 -> {
                            error = if (arabic) "الرمز لازم يكون 4 أرقام أو أكثر" else "PIN must be at least 4 digits"
                        }
                        newPin.length < 4 -> {
                            error = if (arabic)
                                "الرمز الجديد لازم يكون 4 أرقام أو أكثر، أو اتركه فاضي لحذف القفل"
                            else
                                "New PIN Must Be At Least 4 Digits, Or Leave It Empty To Remove Lock"
                        }
                        newPin != confirmPin -> {
                            error = if (arabic) "تأكيد الرمز غير مطابق" else "PIN Confirmation Does Not Match"
                        }
                        else -> {
                            savePin(context, newPin)
                            onSuccess()
                        }
                    }
                }
            ) {
                Text(if (arabic) "حفظ" else "Save")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onClose) {
                Text(if (arabic) "إلغاء" else "Cancel")
            }
        }
    )
}

@Composable
fun CategoryManagerDialog(
    arabic: Boolean,
    categories: List<Category>,
    newCategoryAr: String,
    newCategoryEn: String,
    editingOldCategory: Category?,
    editingCategoryAr: String,
    editingCategoryEn: String,
    onNewCategoryArChange: (String) -> Unit,
    onNewCategoryEnChange: (String) -> Unit,
    onEditingCategoryArChange: (String) -> Unit,
    onEditingCategoryEnChange: (String) -> Unit,
    onStartEdit: (Category) -> Unit,
    onCancelEdit: () -> Unit,
    onAdd: () -> Unit,
    onSaveEdit: () -> Unit,
    onDelete: (Category) -> Unit,
    onClose: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Text(if (arabic) "إدارة التصنيفات" else "Manage Categories")
        },
        text = {
            LazyColumn {
                item {
                    Text(if (arabic) "الحد الأقصى 15 تصنيف" else "Maximum 15 Categories")
                    Spacer(Modifier.height(10.dp))

                    OutlinedTextField(
                        value = newCategoryAr,
                        onValueChange = onNewCategoryArChange,
                        label = { Text(if (arabic) "اسم التصنيف بالعربي" else "Category Arabic Name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = newCategoryEn,
                        onValueChange = onNewCategoryEnChange,
                        label = { Text(if (arabic) "اسم التصنيف بالإنجليزي" else "Category English Name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(Modifier.height(8.dp))

                    Button(
                        onClick = onAdd,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (arabic) "إضافة تصنيف" else "Add Category")
                    }

                    Spacer(Modifier.height(14.dp))
                }

                items(categories) { item ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            if (editingOldCategory?.ar == item.ar && editingOldCategory.en == item.en) {
                                OutlinedTextField(
                                    value = editingCategoryAr,
                                    onValueChange = onEditingCategoryArChange,
                                    label = { Text(if (arabic) "الاسم العربي الجديد" else "New Arabic Name") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )

                                Spacer(Modifier.height(8.dp))

                                OutlinedTextField(
                                    value = editingCategoryEn,
                                    onValueChange = onEditingCategoryEnChange,
                                    label = { Text(if (arabic) "الاسم الإنجليزي الجديد" else "New English Name") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )

                                Spacer(Modifier.height(8.dp))

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = onSaveEdit,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(if (arabic) "حفظ" else "Save")
                                    }

                                    OutlinedButton(
                                        onClick = onCancelEdit,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(if (arabic) "إلغاء" else "Cancel")
                                    }
                                }
                            } else {
                                Text(
                                    "${item.ar} / ${item.en}",
                                    fontWeight = FontWeight.Bold
                                )

                                Spacer(Modifier.height(8.dp))

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = { onStartEdit(item) },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(if (arabic) "تعديل" else "Edit")
                                    }

                                    OutlinedButton(
                                        onClick = { onDelete(item) },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(if (arabic) "حذف" else "Delete")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onClose) {
                Text(if (arabic) "إغلاق" else "Close")
            }
        }
    )
}

fun categoryName(category: Category, arabic: Boolean): String {
    return if (arabic) category.ar else category.en
}

/** Query words that are expanded to alternatives; a word matches when ANY alternative is present (SRCH-01). */
private val SEARCH_SYNONYMS: Map<String, List<String>> = mapOf(
    "مطعم" to listOf("مطعم", "مطاعم", "restaurant"),
    "كافيه" to listOf("كافيه", "مطاعم", "cafe", "coffee"),
    "كوفي" to listOf("كوفي", "مطاعم", "cafe", "coffee"),
    "فندق" to listOf("فندق", "أماكن", "hotel"),
    "مكتب" to listOf("مكتب", "عمل", "work", "office")
)

/** An explicit rating request: a number 1-5 next to a star word or ⭐ (e.g. "5 نجوم", "4 stars", "⭐3", "3⭐"). */
private val RATING_QUERY = Regex("""(?:(\d)\s*(?:نجوم|نجمة|نجمات|stars?|⭐)|⭐\s*(\d))""")

private fun normalizeDigits(text: String): String = buildString(text.length) {
    for (c in text) {
        append(
            when (c) {
                in '٠'..'٩' -> '0' + (c - '٠')
                in '۰'..'۹' -> '0' + (c - '۰')
                else -> c
            }
        )
    }
}

fun matchesSmartSearch(place: Place, query: String): Boolean {
    var cleanQuery = normalizeDigits(query.trim().lowercase())
    if (cleanQuery.isBlank()) return true

    val searchableText = normalizeDigits(
        listOf(
            place.placeName,
            place.title,
            place.url,
            place.description,
            place.privateNotes,
            place.categoryAr,
            place.categoryEn,
            if (place.favorite) "favorite مفضلة مفضل" else "",
            if (place.pinned) "pinned مثبت مثبتة" else "",
            if (place.rating == 5) "top rated five stars ممتاز" else ""
        ).joinToString(" ").lowercase()
    )

    // SRCH-01: a number is a rating filter only when the query says so ("Floor 2" / "بوابة 3" stay plain text)
    RATING_QUERY.find(cleanQuery)?.let { match ->
        val wanted = (match.groupValues[1].ifEmpty { match.groupValues[2] }).toIntOrNull()
        if (wanted != null && wanted in 1..5) {
            if (place.rating != wanted) return false
            cleanQuery = cleanQuery.removeRange(match.range)
        }
    }

    val tokens = cleanQuery
        .replace("⭐", " ")
        .split(Regex("""\s+"""))
        .map { it.trim() }
        .filter { it.isNotBlank() }

    // every word must match; a word with synonyms matches when any of its alternatives is present
    return tokens.all { token ->
        val alternatives = SEARCH_SYNONYMS[token] ?: listOf(token)
        alternatives.any { searchableText.contains(it) }
    }
}

fun suggestCategoryFromText(
    url: String,
    description: String,
    categories: List<Category>,
    current: Category
): Category {
    val text = "$url $description".lowercase()

    fun findCategory(vararg keywords: String): Category? {
        return categories.firstOrNull { cat ->
            val catText = "${cat.ar} ${cat.en}".lowercase()
            keywords.any { key -> catText.contains(key.lowercase()) }
        }
    }

    return when {
        listOf("restaurant", "restaurants", "مطعم", "مطاعم", "cafe", "coffee", "food", "dining").any { text.contains(it) } ->
            findCategory("مطاعم", "restaurants") ?: current

        listOf("hotel", "mall", "park", "place", "places", "فندق", "مول", "حديقة", "مكان", "أماكن").any { text.contains(it) } ->
            findCategory("أماكن", "places") ?: current

        listOf("office", "work", "company", "business", "مكتب", "عمل", "شركة").any { text.contains(it) } ->
            findCategory("عمل", "work") ?: current

        else -> current
    }
}

fun sharePlacesList(context: Context, places: List<Place>, arabic: Boolean, title: String) {
    val header = if (arabic) {
        "قائمة من ShareAdd\n\n" +
                "📍 $title\n" +
                "عدد الأماكن: ${places.size}\n\n"
    } else {
        "A list from ShareAdd\n\n" +
                "📍 $title\n" +
                "Places count: ${places.size}\n\n"
    }

    val body = places.mapIndexed { index, place ->
        val categoryText = if (arabic) place.categoryAr else place.categoryEn
        val ratingText = if (place.rating == 0) "-" else "${place.rating}/5"
        val noteLine = if (place.description.isNotBlank()) "📝 ${place.description}\n" else ""

        "${index + 1}. ${place.placeName}\n" +
                "📍 ${place.title}\n" +
                "🗂 $categoryText\n" +
                "⭐ $ratingText\n" +
                noteLine +
                "🗺 ${place.url}"
    }.joinToString("\n\n────────────\n\n")

    val footer = if (arabic) {
        "\n\nتمت المشاركة عبر ShareAdd\nتصميم وتطوير المهندس علي حسين الحنابي"
    } else {
        "\n\nShared via ShareAdd\nDesigned & developed by Eng. Ali Hussain Al-Hanabi"
    }

    val intent = Intent(Intent.ACTION_SEND)
    intent.type = "text/plain"
    intent.putExtra(Intent.EXTRA_TEXT, header + body + footer)

    context.startActivity(
        Intent.createChooser(
            intent,
            if (arabic) "مشاركة القائمة عبر" else "Share List With"
        )
    )
}

/** Text of a place from the built-in detailed / short place template (nothing is added outside the template). */
fun buildShareText(place: Place, arabic: Boolean, detailed: Boolean): String {
    val id = if (detailed) BUILTIN_PLACE_DETAILED else BUILTIN_PLACE_SHORT
    return placeShareText(place, arabic, placeShareTemplate(emptyList(), arabic, id).body)
}

fun sharePlace(
    context: Context,
    place: Place,
    arabic: Boolean,
    includeImages: Boolean,
    detailed: Boolean = true,
    /** the exact text to share (from the share dialog); null = the built-in template text */
    text: String? = null
) {
    @Suppress("NAME_SHADOWING") val text = text ?: buildShareText(place, arabic, detailed)

    if (includeImages && place.images.isNotEmpty()) {
        val uriList = ArrayList<Uri>()
        place.images.mapNotNull { shareAddImageUri(context, it) }.forEach { uriList.add(it) }

        if (uriList.isNotEmpty()) {
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE)
            intent.type = "image/*"
            intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uriList as ArrayList<out Parcelable>)
            intent.putExtra(Intent.EXTRA_TEXT, text)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

            context.startActivity(Intent.createChooser(intent, if (arabic) "مشاركة عبر" else "Share With"))
            return
        }

        Toast.makeText(
            context,
            if (arabic) "تعذر العثور على الصور، تمت مشاركة النص فقط" else "Images were not available, shared text only",
            Toast.LENGTH_SHORT
        ).show()
    }

    val intent = Intent(Intent.ACTION_SEND)
    intent.type = "text/plain"
    intent.putExtra(Intent.EXTRA_TEXT, text)

    context.startActivity(Intent.createChooser(intent, if (arabic) "مشاركة عبر" else "Share With"))
}

@Composable
fun BrandLogo(size: Int = 76) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .shadow(16.dp, RoundedCornerShape(26.dp))
            .background(Color(0xFF07080D), RoundedCornerShape(26.dp)),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(7.dp)
                .background(Color(0xFF151823), RoundedCornerShape(22.dp)),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size((size * 0.62f).dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color(0xFF9D7CFF),
                                Color(0xFF7A4CFF)
                            )
                        ),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "📍",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Text(
                        text = "SA",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size((size * 0.30f).dp)
                    .background(Color(0xFF00E5C0), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "↗",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun PlaceCard(
    place: Place,
    arabic: Boolean,
    onOpenExternal: () -> Unit,
    onShareText: () -> Unit,
    onShareImages: () -> Unit,
    onDetails: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onCopyLink: () -> Unit
) {
    val categoryText = if (arabic) place.categoryAr else place.categoryEn
    var showMore by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                if (place.images.isNotEmpty()) {
                    ImagePreview(place.images.first(), 82, contentDescription = if (arabic) "صورة المكان" else "Place image")
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            place.placeName,
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )

                        if (place.pinned) {
                            Text("📌")
                            Spacer(Modifier.width(4.dp))
                        }

                        if (place.favorite) {
                            Text("❤️")
                        }
                    }

                    if (place.title.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            place.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.13f),
                            shape = RoundedCornerShape(50)
                        ) {
                            Text(
                                "🏷️ $categoryText",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (place.rating > 0) {
                            Text(
                                "⭐ ${place.rating}/5",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.secondary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            if (place.description.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    place.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                    maxLines = 2
                )
            }

            Spacer(Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onOpenExternal,
                    modifier = Modifier.weight(1.25f)
                ) {
                    Text(if (arabic) "📍 الخرائط" else "📍 Map")
                }

                FilledTonalButton(
                    onClick = onShareText,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (arabic) "مشاركة" else "Share")
                }

                Box {
                    OutlinedButton(onClick = { showMore = true }) {
                        Text(if (arabic) "المزيد" else "More")
                    }

                    DropdownMenu(
                        expanded = showMore,
                        onDismissRequest = { showMore = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(if (arabic) "التفاصيل" else "Details") },
                            onClick = {
                                showMore = false
                                onDetails()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(if (arabic) "تعديل" else "Edit") },
                            onClick = {
                                showMore = false
                                onEdit()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(if (arabic) "نسخ الرابط" else "Copy Link") },
                            onClick = {
                                showMore = false
                                onCopyLink()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(if (arabic) "مشاركة مع الصور" else "Share With Images") },
                            onClick = {
                                showMore = false
                                onShareImages()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(if (arabic) "حذف" else "Delete") },
                            onClick = {
                                showMore = false
                                onDelete()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ImagePreview(uri: String, size: Int, contentDescription: String? = null) {
    val context = LocalContext.current
    val targetPx = with(LocalDensity.current) { size.dp.roundToPx() }

    // PERF-01: decoded off the main thread at display size (cached), not at full resolution
    val bitmap by produceState(initialValue = ImageThumbnails.cached(uri, targetPx), uri, targetPx) {
        if (value == null) {
            value = withContext(Dispatchers.IO) { ImageThumbnails.load(context, uri, targetPx) }
        }
    }

    val shown = bitmap
    if (shown != null) {
        Card(shape = RoundedCornerShape(18.dp)) {
            Image(
                bitmap = shown.asImageBitmap(),
                contentDescription = contentDescription,
                modifier = Modifier.size(size.dp),
                contentScale = ContentScale.Crop
            )
        }
    }
}

fun extractUrl(text: String): String {
    val regex = Regex("(https?://\\S+)")
    return regex.find(text)?.value ?: ""
}

fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("ShareAdd", text))
}

/** I18N-01: formatted for the app language (not the device locale); digits stay Western like the rest of the app. */
fun formatDate(time: Long, arabic: Boolean): String {
    if (time <= 0L) return "-"
    val locale = if (arabic) java.util.Locale.forLanguageTag("ar-u-nu-latn") else java.util.Locale.ENGLISH
    val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", locale)
    return date.format(java.util.Date(time))
}

fun saveBoolean(context: Context, key: String, value: Boolean) {
    context.getSharedPreferences("shareadd_settings", Context.MODE_PRIVATE)
        .edit()
        .putBoolean(key, value)
        .apply()
}

fun loadBoolean(context: Context, key: String, default: Boolean): Boolean {
    return context.getSharedPreferences("shareadd_settings", Context.MODE_PRIVATE)
        .getBoolean(key, default)
}

fun hasPin(context: Context): Boolean {
    return context.getSharedPreferences("shareadd_security", Context.MODE_PRIVATE)
        .contains("pin_hash")
}

fun savePin(context: Context, pin: String) {
    context.getSharedPreferences("shareadd_security", Context.MODE_PRIVATE)
        .edit()
        .putString("pin_hash", sha256(pin))
        .apply()
}

fun clearPin(context: Context) {
    context.getSharedPreferences("shareadd_security", Context.MODE_PRIVATE)
        .edit()
        .remove("pin_hash")
        .apply()
}

fun checkPin(context: Context, pin: String): Boolean {
    val saved = context.getSharedPreferences("shareadd_security", Context.MODE_PRIVATE)
        .getString("pin_hash", "")
    return saved == sha256(pin)
}

fun sha256(text: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
    return bytes.joinToString("") { "%02x".format(it) }
}

fun defaultCategories(): List<Category> {
    return listOf(
        Category("مطاعم", "Restaurants"),
        Category("أماكن", "Places"),
        Category("عمل", "Work"),
        Category("مفضلة", "Favorites"),
        Category("أخرى", "Other")
    )
}

fun categoryFromLegacy(value: String): Category {
    return when (value.trim()) {
        "مطاعم", "Restaurants" -> Category("مطاعم", "Restaurants")
        "أماكن", "Places" -> Category("أماكن", "Places")
        "عمل", "Work" -> Category("عمل", "Work")
        "مفضلة", "Favorites" -> Category("مفضلة", "Favorites")
        "أخرى", "Other" -> Category("أخرى", "Other")
        else -> Category(value.ifBlank { "أخرى" }, value.ifBlank { "Other" })
    }
}

fun saveCategories(context: Context, categories: List<Category>) {
    val array = JSONArray()

    categories.take(15).forEach {
        val obj = JSONObject()
        obj.put("ar", it.ar)
        obj.put("en", it.en)
        array.put(obj)
    }

    context.getSharedPreferences("shareadd_categories", Context.MODE_PRIVATE)
        .edit()
        .putString("categories", array.toString())
        .apply()
}

/** Parses stored categories; returns null when the stored text cannot be read completely (corrupt). */
fun parseCategoriesStrict(text: String): List<Category>? {
    return try {
        val array = JSONArray(text)
        val result = mutableListOf<Category>()

        for (i in 0 until array.length()) {
            val item = array.get(i)

            if (item is JSONObject) {
                val ar = item.optString("ar").trim()
                val en = item.optString("en").trim()

                if (ar.isNotBlank() && en.isNotBlank() && result.size < 15) {
                    result.add(Category(ar, en))
                }
            } else {
                if (result.size < 15) {
                    result.add(categoryFromLegacy(item.toString()))
                }
            }
        }

        if (result.isEmpty()) defaultCategories() else result
    } catch (_: Exception) {
        null
    }
}

/** DATA-01: a read failure is reported as not-ok; the stored text is left untouched and a private recovery copy is kept. */
fun loadCategoriesChecked(context: Context): StoreLoadResult<List<Category>> {
    val text = try {
        context.getSharedPreferences("shareadd_categories", Context.MODE_PRIVATE).getString("categories", null)
    } catch (_: Exception) {
        return StoreLoadResult(defaultCategories(), ok = false)
    }

    if (text.isNullOrBlank()) return StoreLoadResult(defaultCategories(), ok = true)

    val parsed = parseCategoriesStrict(text)
        ?: return StoreLoadResult(defaultCategories(), ok = false, recoveryFile = saveRecoveryCopy(context, "categories", text))
    return StoreLoadResult(parsed, ok = true)
}

fun savePlaces(context: Context, places: List<Place>) {
    context.getSharedPreferences("shareadd_data", Context.MODE_PRIVATE)
        .edit()
        .putString("places", placesToJson(places))
        .apply()
}

/** DATA-01: distinguishes "no data / valid empty list" from "stored data could not be read". */
fun loadPlacesChecked(context: Context): StoreLoadResult<List<Place>> {
    val text = try {
        context.getSharedPreferences("shareadd_data", Context.MODE_PRIVATE).getString("places", null)
    } catch (_: Exception) {
        return StoreLoadResult(emptyList(), ok = false)
    }

    if (text.isNullOrBlank()) return StoreLoadResult(emptyList(), ok = true)

    val parsed = parsePlacesStrict(text)
        ?: return StoreLoadResult(emptyList(), ok = false, recoveryFile = saveRecoveryCopy(context, "places", text))
    val ids = assignPlaceIds(parsed)
    return StoreLoadResult(ids.places, ok = true, generatedIds = ids.generated, repairedIds = ids.repaired)
}

fun placesToJson(places: List<Place>): String {
    val array = JSONArray()

    places.forEach { place ->
        val obj = JSONObject()
        obj.put("id", place.id)
        obj.put("placeName", place.placeName)
        obj.put("title", place.title)
        obj.put("url", place.url)
        obj.put("description", place.description)
        obj.put("privateNotes", place.privateNotes)
        obj.put("categoryAr", place.categoryAr)
        obj.put("categoryEn", place.categoryEn)
        obj.put("rating", place.rating)
        obj.put("favorite", place.favorite)
        obj.put("pinned", place.pinned)
        obj.put("createdAt", place.createdAt)
        obj.put("updatedAt", place.updatedAt)

        val imageArray = JSONArray()
        place.images.forEach { imageArray.put(it) }

        obj.put("images", imageArray)
        array.put(obj)
    }

    return array.toString()
}

/** Parses the stored places; returns null if any record cannot be read (no partial list is ever returned). */
fun parsePlacesStrict(text: String): List<Place>? {
    val result = mutableListOf<Place>()

    try {
        val array = JSONArray(text)

        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val imageArray = obj.optJSONArray("images") ?: JSONArray()
            val images = mutableListOf<String>()

            for (j in 0 until imageArray.length()) {
                images.add(imageArray.getString(j))
            }

            val oldTitle = obj.optString("title")
            val categoryAr = obj.optString("categoryAr")
            val categoryEn = obj.optString("categoryEn")

            val legacyCategory = if (categoryAr.isBlank() || categoryEn.isBlank()) {
                categoryFromLegacy(obj.optString("category"))
            } else {
                Category(categoryAr, categoryEn)
            }

            val now = System.currentTimeMillis()

            result.add(
                Place(
                    id = obj.optString("id"),
                    placeName = obj.optString("placeName", oldTitle),
                    title = oldTitle,
                    url = obj.optString("url"),
                    description = obj.optString("description"),
                    privateNotes = obj.optString("privateNotes"),
                    categoryAr = legacyCategory.ar,
                    categoryEn = legacyCategory.en,
                    images = images,
                    rating = obj.optInt("rating", 0),
                    favorite = obj.optBoolean("favorite", false),
                    pinned = obj.optBoolean("pinned", false),
                    createdAt = obj.optLong("createdAt", now),
                    updatedAt = obj.optLong("updatedAt", now)
                )
            )
        }
    } catch (_: Exception) {
        return null
    }

    return result
}


fun placeImportKey(place: Place): String {
    val cleanUrl = place.url.trim().lowercase()
    if (cleanUrl.isNotBlank()) return "url:$cleanUrl"

    val name = place.placeName.ifBlank { place.title }.trim().lowercase()
    val category = "${place.categoryAr}|${place.categoryEn}".trim().lowercase()
    return "place:$name|$category"
}

fun getShareAddImagesDir(context: Context): File {
    return File(context.filesDir, "shareadd_images").apply { mkdirs() }
}

fun copyImageToPrivateStorage(context: Context, uri: Uri): String? {
    return try {
        val mimeType = context.contentResolver.getType(uri).orEmpty()
        val extension = when {
            mimeType.contains("png") -> "png"
            mimeType.contains("webp") -> "webp"
            else -> "jpg"
        }

        val imageFile = File(
            getShareAddImagesDir(context),
            "shareadd_${System.currentTimeMillis()}_${UUID.randomUUID()}.$extension"
        )

        context.contentResolver.openInputStream(uri)?.use { input ->
            imageFile.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: return null

        imageFile.absolutePath
    } catch (_: Exception) {
        null
    }
}

data class ImageMigrationResult(
    val places: List<Place>,
    val changed: Boolean,
    val failedCount: Int = 0
)

fun isShareAddPrivateImage(context: Context, imageRef: String): Boolean {
    return try {
        val file = File(imageRef)
        file.isAbsolute && isTrustedImageRef(context, imageRef) && file.exists()
    } catch (_: Exception) {
        false
    }
}

/**
 * Returns the private-store reference for an image, or null when it could not be copied.
 * SEC-02: absolute paths outside the private store are never copied (they are not trusted image references).
 */
fun migrateImageRefToPrivateStorage(context: Context, imageRef: String): String? {
    if (imageRef.isBlank()) return null

    if (isShareAddPrivateImage(context, imageRef)) {
        return imageRef
    }

    return try {
        val file = File(imageRef)
        if (file.isAbsolute) {
            null
        } else if (isTrustedImageRef(context, imageRef)) {
            copyImageToPrivateStorage(context, Uri.parse(imageRef))
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }
}

/** DATA-06: a reference that cannot be copied is kept as it was (never dropped), and counted as not migrated. */
fun migratePlacesImagesToPrivateStorage(context: Context, places: List<Place>): ImageMigrationResult {
    var changed = false
    var failed = 0

    val migratedPlaces = places.map { place ->
        val migratedImages = place.images.map { imageRef ->
            if (isShareAddPrivateImage(context, imageRef)) return@map imageRef

            val migrated = migrateImageRefToPrivateStorage(context, imageRef)
            if (migrated == null) {
                failed++
                imageRef
            } else {
                if (migrated != imageRef) changed = true
                migrated
            }
        }

        if (migratedImages != place.images) {
            place.copy(images = migratedImages, updatedAt = System.currentTimeMillis())
        } else {
            place
        }
    }

    return ImageMigrationResult(migratedPlaces, changed, failed)
}

/** SEC-02: opens only trusted image references (private store files, or content:// URIs). */
fun openShareAddImageInputStream(context: Context, imageRef: String) = try {
    if (!isTrustedImageRef(context, imageRef)) {
        null
    } else {
        val file = File(imageRef)
        if (file.isAbsolute) {
            if (file.exists()) file.inputStream() else null
        } else {
            context.contentResolver.openInputStream(Uri.parse(imageRef))
        }
    }
} catch (_: Exception) {
    null
}

/** SEC-02: only trusted image references can be turned into share URIs. */
fun shareAddImageUri(context: Context, imageRef: String): Uri? {
    return try {
        if (!isTrustedImageRef(context, imageRef)) return null

        val file = File(imageRef)
        if (file.isAbsolute) {
            if (!file.exists()) return null

            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } else {
            // a legacy content:// reference is shared only if it can actually be read
            val uri = Uri.parse(imageRef)
            val readable = context.contentResolver.openInputStream(uri)?.use { true } ?: false
            if (readable) uri else null
        }
    } catch (_: Exception) {
        null
    }
}

fun appVersionName(context: Context): String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
} catch (_: Exception) {
    ""
}

fun appVersionCode(context: Context): Long = try {
    context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
} catch (_: Exception) {
    0L
}

fun saveLastBackup(context: Context, time: Long) {
    context.getSharedPreferences("shareadd_settings", Context.MODE_PRIVATE).edit().putLong("lastBackupAt", time).apply()
}

fun loadLastBackup(context: Context): Long =
    context.getSharedPreferences("shareadd_settings", Context.MODE_PRIVATE).getLong("lastBackupAt", 0L)

/** LIFE-01 savers for the editor draft (plain strings only). */
val CategorySaver = listSaver<Category, String>(
    save = { listOf(it.ar, it.en) },
    restore = { Category(it[0], it[1]) }
)

val StringListSaver = listSaver<List<String>, String>(
    save = { it },
    restore = { it.toList() }
)
