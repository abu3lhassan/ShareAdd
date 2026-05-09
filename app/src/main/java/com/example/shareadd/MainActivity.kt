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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.io.File
import java.util.UUID

data class Category(
    val ar: String,
    val en: String
)

data class Place(
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

data class PendingImport(
    val rawJson: String,
    val previewPlaces: List<Place>
)

enum class ShareAddPage {
    Dashboard,
    SavedLocations
}

class MainActivity : FragmentActivity() {

    private val sharedTextState = mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sharedTextState.value = extractSharedText(intent)

        setContent {
            ShareAddApp(
                initialSharedText = sharedTextState.value,
                openBiometric = { onSuccess ->
                    openBiometric(onSuccess)
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

    private fun openBiometric(onSuccess: () -> Unit) {
        val manager = BiometricManager.from(this)
        val canUse = manager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )

        if (canUse != BiometricManager.BIOMETRIC_SUCCESS) return

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
            .setSubtitle("افتح التطبيق بالبصمة أو رمز الجهاز")
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
    openBiometric: (() -> Unit) -> Unit
) {
    val context = LocalContext.current

    var unlocked by remember { mutableStateOf(!hasPin(context)) }
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

    LaunchedEffect(darkMode) {
        saveBoolean(context, "darkMode", darkMode)
    }

    LaunchedEffect(arabic) {
        saveBoolean(context, "arabic", arabic)
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
                    onUnlock = { unlocked = true },
                    onBiometric = {
                        openBiometric { unlocked = true }
                    }
                )
            } else {
                ShareAddScreen(
                    initialSharedText = initialSharedText,
                    darkMode = darkMode,
                    arabic = arabic,
                    onToggleDark = { darkMode = !darkMode },
                    onToggleArabic = { arabic = !arabic },
                    onLock = { showLockOverlay = true }
                )

                if (showLockOverlay) {
                    LockScreen(
                        arabic = arabic,
                        allowCancel = true,
                        onCancel = { showLockOverlay = false },
                        onUnlock = { showLockOverlay = false },
                        onBiometric = {
                            openBiometric { showLockOverlay = false }
                        }
                    )
                }
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
    darkMode: Boolean,
    arabic: Boolean,
    onToggleDark: () -> Unit,
    onToggleArabic: () -> Unit,
    onLock: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    var places by remember { mutableStateOf(loadPlaces(context)) }
    var categories by remember { mutableStateOf(loadCategories(context)) }

    var placeName by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var url by remember { mutableStateOf(extractUrl(initialSharedText)) }
    var desc by remember { mutableStateOf(if (initialSharedText.isNotBlank()) initialSharedText.take(250) else "") }
    var privateNotes by remember { mutableStateOf("") }
    var rating by remember { mutableStateOf(0) }
    var favorite by remember { mutableStateOf(false) }
    var pinned by remember { mutableStateOf(false) }
    var category by remember { mutableStateOf(categories.firstOrNull() ?: defaultCategories().first()) }
    var search by remember { mutableStateOf("") }
    var images by remember { mutableStateOf(listOf<String>()) }
    var message by remember {
        mutableStateOf(
            if (initialSharedText.isNotBlank())
                if (arabic) "تم استلام رابط من المشاركة" else "Shared link received"
            else ""
        )
    }

    var showAddDialog by remember { mutableStateOf(false) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var showCategoryManager by remember { mutableStateOf(false) }
    var showPinChange by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showImportNotice by remember { mutableStateOf(false) }
    var currentPage by remember { mutableStateOf(ShareAddPage.Dashboard) }
    var pendingImport by remember { mutableStateOf<PendingImport?>(null) }
    var importMode by remember { mutableStateOf("skip") }
    var detailsPlace by remember { mutableStateOf<Place?>(null) }
    var shareOptionsPlace by remember { mutableStateOf<Place?>(null) }
    var deleteIndex by remember { mutableStateOf<Int?>(null) }

    var newCategoryAr by remember { mutableStateOf("") }
    var newCategoryEn by remember { mutableStateOf("") }
    var editingOldCategory by remember { mutableStateOf<Category?>(null) }
    var editingCategoryAr by remember { mutableStateOf("") }
    var editingCategoryEn by remember { mutableStateOf("") }

    var filterCategory by remember { mutableStateOf<Category?>(null) }
    var onlyFavorite by remember { mutableStateOf(false) }
    var onlyPinned by remember { mutableStateOf(false) }
    var sortMode by remember { mutableStateOf("pinned_newest") }

    LaunchedEffect(initialSharedText) {
        if (initialSharedText.isNotBlank()) {
            val extractedUrl = extractUrl(initialSharedText)

            editingIndex = null
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
            message = if (arabic) "تم استلام موقع من المشاركة" else "Location received from share"
            showAddDialog = true
        }
    }

    LaunchedEffect(places) {
        savePlaces(context, places)
    }

    LaunchedEffect(categories) {
        saveCategories(context, categories)
        if (categories.none { it.ar == category.ar && it.en == category.en } && categories.isNotEmpty()) {
            category = categories.first()
        }
    }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val copiedImages = uris.take(5).mapNotNull { uri ->
            copyImageToPrivateStorage(context, uri)
        }

        images = copiedImages
        message = if (copiedImages.isNotEmpty()) {
            if (arabic) "تم حفظ الصور داخل ShareAdd بشكل خاص" else "Images saved privately inside ShareAdd"
        } else if (uris.isNotEmpty()) {
            if (arabic) "تعذر إضافة الصور" else "Could not add images"
        } else {
            message
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(placesToJsonWithImages(context, places).toByteArray())
                }
                message = if (arabic) "تم تصدير ملف النسخة الاحتياطية بنجاح. يمكنك حفظه في Google Drive من نافذة أندرويد." else "Backup file exported successfully. You can save it to Google Drive from the Android file picker."
            } catch (_: Exception) {
                message = if (arabic) "فشل تصدير ملف النسخة الاحتياطية" else "Backup Export Failed"
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val text = context.contentResolver.openInputStream(uri)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    ?: "[]"

                val previewPlaces = jsonToPlacesForPreview(text)

                if (previewPlaces.isEmpty()) {
                    message = if (arabic) "ملف النسخة الاحتياطية فارغ أو غير صالح" else "Backup file is empty or invalid"
                } else {
                    pendingImport = PendingImport(text, previewPlaces)
                    importMode = "skip"
                }
            } catch (_: Exception) {
                message = if (arabic) "فشل قراءة ملف النسخة الاحتياطية" else "Could not read backup file"
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

    if (detailsPlace != null) {
        PlaceDetailsDialog(
            place = detailsPlace!!,
            arabic = arabic,
            onClose = { detailsPlace = null },
            onOpenMap = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(detailsPlace!!.url)))
            },
            onShare = {
                shareOptionsPlace = detailsPlace
            },
            onCopyLink = {
                copyText(context, detailsPlace!!.url)
                Toast.makeText(context, if (arabic) "تم نسخ الرابط" else "Link Copied", Toast.LENGTH_SHORT).show()
            },
            onEdit = {
                val place = detailsPlace!!
                val realIndex = places.indexOf(place)
                if (realIndex >= 0) {
                    editingIndex = realIndex
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
                    detailsPlace = null
                    showAddDialog = true
                }
            }
        )
    }

    if (shareOptionsPlace != null) {
        ShareOptionsDialog(
            place = shareOptionsPlace!!,
            arabic = arabic,
            onDismiss = { shareOptionsPlace = null },
            onShortShare = {
                sharePlace(context, shareOptionsPlace!!, arabic, includeImages = false, detailed = false)
                shareOptionsPlace = null
            },
            onDetailedShare = {
                sharePlace(context, shareOptionsPlace!!, arabic, includeImages = false, detailed = true)
                shareOptionsPlace = null
            },
            onCopyText = {
                copyText(context, buildShareText(shareOptionsPlace!!, arabic, detailed = true))
                Toast.makeText(context, if (arabic) "تم نسخ نص المشاركة" else "Share text copied", Toast.LENGTH_SHORT).show()
                shareOptionsPlace = null
            },
            onShareImages = {
                sharePlace(context, shareOptionsPlace!!, arabic, includeImages = true, detailed = true)
                shareOptionsPlace = null
            }
        )
    }

    if (deleteIndex != null) {
        AlertDialog(
            onDismissRequest = { deleteIndex = null },
            title = { Text(if (arabic) "تأكيد الحذف" else "Confirm Delete") },
            text = { Text(if (arabic) "هل تريد حذف هذا العنوان؟" else "Do you want to delete this address?") },
            confirmButton = {
                Button(
                    onClick = {
                        val index = deleteIndex
                        if (index != null && index in places.indices) {
                            places = places.filterIndexed { i, _ -> i != index }
                        }
                        deleteIndex = null
                    }
                ) {
                    Text(if (arabic) "حذف" else "Delete")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { deleteIndex = null }) {
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
        val importedPreview = pendingImport!!.previewPlaces
        val currentKeys = places.map { placeImportKey(it) }.toSet()
        val duplicateCount = importedPreview.count { placeImportKey(it) in currentKeys }
        val imageCount = importedPreview.sumOf { it.images.size }

        AlertDialog(
            onDismissRequest = { pendingImport = null },
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
                            Text(if (arabic) "الأماكن الحالية: ${places.size}" else "Current places: ${places.size}")
                            Text(if (arabic) "الأماكن داخل الملف: ${importedPreview.size}" else "Places in backup: ${importedPreview.size}")
                            Text(if (arabic) "المكررات المحتملة: $duplicateCount" else "Possible duplicates: $duplicateCount")
                            Text(if (arabic) "الصور داخل الملف: $imageCount" else "Images in backup: $imageCount")
                        }
                    }

                    Text(
                        if (arabic) "طريقة الاستيراد" else "Import method",
                        fontWeight = FontWeight.SemiBold
                    )

                    ImportModeRow(
                        selected = importMode == "skip",
                        title = if (arabic) "دمج آمن: تخطي المكرر" else "Safe merge: skip duplicates",
                        subtitle = if (arabic) "الأفضل حاليًا. يضيف الجديد فقط." else "Recommended. Adds new places only.",
                        onClick = { importMode = "skip" }
                    )

                    ImportModeRow(
                        selected = importMode == "merge",
                        title = if (arabic) "دمج الكل" else "Merge all",
                        subtitle = if (arabic) "يضيف كل ما في الملف حتى لو تكرر." else "Adds everything from the file, even duplicates.",
                        onClick = { importMode = "merge" }
                    )

                    ImportModeRow(
                        selected = importMode == "replace",
                        title = if (arabic) "استبدال الكل" else "Replace all",
                        subtitle = if (arabic) "يحذف القائمة الحالية ويستبدلها بالملف." else "Replaces your current list with this backup.",
                        onClick = { importMode = "replace" }
                    )

                    if (importMode == "replace") {
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
                        val finalImported = jsonToPlacesWithImages(context, pendingImport!!.rawJson)

                        places = when (importMode) {
                            "replace" -> finalImported
                            "merge" -> places + finalImported
                            else -> {
                                val existingKeys = places.map { placeImportKey(it) }.toMutableSet()
                                val newItems = finalImported.filter { existingKeys.add(placeImportKey(it)) }
                                places + newItems
                            }
                        }

                        val importedCount = when (importMode) {
                            "replace" -> finalImported.size
                            "merge" -> finalImported.size
                            else -> finalImported.count { placeImportKey(it) !in currentKeys }
                        }

                        pendingImport = null
                        message = if (arabic)
                            "تم تطبيق الاستيراد بنجاح. العناصر المضافة/المطبقة: $importedCount"
                        else
                            "Import applied successfully. Added/applied items: $importedCount"
                    }
                ) {
                    Text(if (arabic) "تأكيد الاستيراد" else "Confirm Import")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { pendingImport = null }) {
                    Text(if (arabic) "إلغاء" else "Cancel")
                }
            }
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
            editing = editingIndex != null,
            onPlaceNameChange = { placeName = it },
            onTitleChange = { title = it },
            onUrlChange = {
                url = it
                category = suggestCategoryFromText(it, desc, categories, category)
            },
            onDescChange = {
                if (it.length <= 250) {
                    desc = it
                    category = suggestCategoryFromText(url, it, categories, category)
                }
            },
            onPrivateNotesChange = { privateNotes = it },
            onRatingChange = {
                rating = it
                if (it == 5) favorite = true
            },
            onFavoriteChange = { favorite = it },
            onPinnedChange = { pinned = it },
            onCategoryChange = { category = it },
            onChooseImages = { imagePicker.launch(arrayOf("image/*")) },
            onRemoveImage = { removeUri ->
                images = images.filterNot { it == removeUri }
            },
            onManageCategories = { showCategoryManager = true },
            onDismiss = {
                showAddDialog = false
                editingIndex = null
            },
            onSave = {
                if (placeName.isNotBlank() && title.isNotBlank() && url.isNotBlank()) {
                    val fixedUrl = if (url.startsWith("http")) url else "https://$url"
                    val now = System.currentTimeMillis()

                    val newPlace = Place(
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
                        createdAt = editingIndex?.let { places.getOrNull(it)?.createdAt } ?: now,
                        updatedAt = now
                    )

                    val index = editingIndex
                    places = if (index != null && index in places.indices) {
                        places.mapIndexed { i, place -> if (i == index) newPlace else place }
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
                    editingIndex = null
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
        editingIndex = null
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
        showAddDialog = true
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                DrawerContent(
                    arabic = arabic,
                    currentPage = currentPage,
                    onDashboard = {
                        currentPage = ShareAddPage.Dashboard
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
                        showCategoryManager = true
                        scope.launch { drawerState.close() }
                    },
                    onAbout = {
                        showAboutDialog = true
                        scope.launch { drawerState.close() }
                    },
                    onExport = {
                        exportLauncher.launch("shareadd-backup-${System.currentTimeMillis()}.json")
                        scope.launch { drawerState.close() }
                    },
                    onImport = {
                        showImportNotice = true
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
                    } else {
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

                if (currentPage == ShareAddPage.SavedLocations) {
                    items(filtered) { place ->
                        val realIndex = places.indexOf(place)

                        PlaceCard(
                            place = place,
                            arabic = arabic,
                            onOpenExternal = {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(place.url)))
                            },
                            onShareText = {
                                shareOptionsPlace = place
                            },
                            onShareImages = {
                                sharePlace(context, place, arabic, includeImages = true)
                            },
                            onDetails = {
                                detailsPlace = place
                            },
                            onEdit = {
                                if (realIndex >= 0) {
                                    editingIndex = realIndex
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
                                    showAddDialog = true
                                }
                            },
                            onDelete = {
                                if (realIndex >= 0) deleteIndex = realIndex
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
    onDashboard: () -> Unit,
    onSavedLocations: () -> Unit,
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
    onSave: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
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
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (arabic) "اختيار الصور ${images.size}/5" else "Choose Images ${images.size}/5")
                    }

                    if (images.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(images) { uri ->
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    ImagePreview(uri, 92)
                                    TextButton(onClick = { onRemoveImage(uri) }) {
                                        Text(if (arabic) "حذف" else "Remove")
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
                                ImagePreview(place.images.first(), 180)
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
                            value = formatDate(place.createdAt),
                            modifier = Modifier.weight(1f)
                        )
                        PremiumMiniInfo(
                            title = if (arabic) "آخر تعديل" else "Updated",
                            value = formatDate(place.updatedAt),
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
                            items(place.images.drop(1)) { uri ->
                                ImagePreview(uri, 104)
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

    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Text(if (arabic) "تغيير رمز الدخول" else "Change PIN")
        },
        text = {
            Column {
                OutlinedTextField(
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

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = newPin,
                    onValueChange = {
                        if (it.length <= 6 && it.all { c -> c.isDigit() }) newPin = it
                    },
                    label = { Text(if (arabic) "الرمز الجديد - اتركه فاضي لحذف القفل" else "New PIN - Leave Empty To Remove Lock") },
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
                        !checkPin(context, oldPin) -> {
                            error = if (arabic) "الرمز الحالي غير صحيح" else "Current PIN Is Incorrect"
                        }
                        newPin.isBlank() && confirmPin.isBlank() -> {
                            clearPin(context)
                            onSuccess()
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

fun matchesSmartSearch(place: Place, query: String): Boolean {
    val cleanQuery = query.trim().lowercase()
    if (cleanQuery.isBlank()) return true

    val searchableText = listOf(
        place.placeName,
        place.title,
        place.url,
        place.description,
        place.privateNotes,
        place.categoryAr,
        place.categoryEn,
        if (place.favorite) "favorite مفضلة مفضل" else "",
        if (place.pinned) "pinned مثبت مثبتة" else "",
        "${place.rating} stars ${place.rating} نجوم ${place.rating} star",
        if (place.rating == 5) "top rated five stars 5 نجوم ممتاز" else ""
    ).joinToString(" ").lowercase()

    val normalized = cleanQuery
        .replace("⭐", " star ")
        .replace("نجمة", "نجوم")
        .replace("مطعم", "مطاعم restaurant restaurants")
        .replace("كافيه", "مطاعم cafe coffee")
        .replace("كوفي", "مطاعم cafe coffee")
        .replace("فندق", "أماكن hotel")
        .replace("مكتب", "عمل work office")

    val ratingWanted = Regex("""([0-9]+)[ ]*(نجوم|star|stars)?""")
        .find(normalized)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()

    if (ratingWanted != null && ratingWanted in 1..5 && place.rating != ratingWanted) {
        return false
    }

    val tokens = normalized
        .split(Regex("""[ ]+"""))
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .filterNot { it in listOf("نجوم", "star", "stars") }

    return tokens.all { token -> searchableText.contains(token) }
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

fun buildShareText(place: Place, arabic: Boolean, detailed: Boolean): String {
    val categoryText = if (arabic) place.categoryAr else place.categoryEn
    val ratingText = if (place.rating == 0) "-" else "${place.rating}/5"
    val noteLine = if (place.description.isNotBlank()) "📝 ${place.description}\n" else ""

    return if (arabic) {
        if (detailed) {
            "حفظت هذا المكان في ShareAdd:\n\n" +
                    "📍 ${place.placeName}\n" +
                    "⭐ $ratingText\n" +
                    "🗂 $categoryText\n" +
                    noteLine +
                    "🗺 ${place.url}\n\n" +
                    "تمت المشاركة عبر ShareAdd\n" +
                    "تصميم وتطوير المهندس علي حسين الحنابي"
        } else {
            "📍 ${place.placeName}\n" +
                    "⭐ $ratingText\n" +
                    "🗺 ${place.url}\n\n" +
                    "تمت المشاركة عبر ShareAdd"
        }
    } else {
        if (detailed) {
            "I saved this place on ShareAdd:\n\n" +
                    "📍 ${place.placeName}\n" +
                    "⭐ $ratingText\n" +
                    "🗂 $categoryText\n" +
                    noteLine +
                    "🗺 ${place.url}\n\n" +
                    "Shared via ShareAdd\n" +
                    "Designed & developed by Eng. Ali Hussain Al-Hanabi"
        } else {
            "📍 ${place.placeName}\n" +
                    "⭐ $ratingText\n" +
                    "🗺 ${place.url}\n\n" +
                    "Shared via ShareAdd"
        }
    }
}

fun sharePlace(
    context: Context,
    place: Place,
    arabic: Boolean,
    includeImages: Boolean,
    detailed: Boolean = true
) {
    val text = buildShareText(place, arabic, detailed)

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
fun ShareOptionsDialog(
    place: Place,
    arabic: Boolean,
    onDismiss: () -> Unit,
    onShortShare: () -> Unit,
    onDetailedShare: () -> Unit,
    onCopyText: () -> Unit,
    onShareImages: () -> Unit
) {
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
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.70f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "📍 ${place.placeName}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = if (arabic)
                                "اختر طريقة المشاركة المناسبة."
                            else
                                "Choose the best sharing style.",
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f)
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onDetailedShare,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (arabic) "مشاركة مفصلة" else "Detailed Share")
                    }

                    OutlinedButton(
                        onClick = onShortShare,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (arabic) "مشاركة مختصرة" else "Short Share")
                    }

                    OutlinedButton(
                        onClick = onCopyText,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (arabic) "نسخ نص المشاركة" else "Copy Share Text")
                    }

                    if (place.images.isNotEmpty()) {
                        OutlinedButton(
                            onClick = onShareImages,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (arabic) "مشاركة مع الصور" else "Share With Images")
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(if (arabic) "إغلاق" else "Close")
            }
        }
    )
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
                    ImagePreview(place.images.first(), 82)
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
fun ImagePreview(uri: String, size: Int) {
    val context = LocalContext.current

    val bitmap = remember(uri) {
        try {
            context.contentResolver.openInputStream(Uri.parse(uri))?.use {
                BitmapFactory.decodeStream(it)
            }
        } catch (_: Exception) {
            null
        }
    }

    if (bitmap != null) {
        Card(shape = RoundedCornerShape(18.dp)) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
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

fun formatDate(time: Long): String {
    if (time <= 0L) return "-"
    val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
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

fun loadCategories(context: Context): List<Category> {
    val text = context.getSharedPreferences("shareadd_categories", Context.MODE_PRIVATE)
        .getString("categories", null)

    if (text.isNullOrBlank()) return defaultCategories()

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
        defaultCategories()
    }
}

fun savePlaces(context: Context, places: List<Place>) {
    context.getSharedPreferences("shareadd_data", Context.MODE_PRIVATE)
        .edit()
        .putString("places", placesToJson(places))
        .apply()
}

fun loadPlaces(context: Context): List<Place> {
    val text = context.getSharedPreferences("shareadd_data", Context.MODE_PRIVATE)
        .getString("places", "[]") ?: "[]"

    return jsonToPlaces(text)
}

fun placesToJson(places: List<Place>): String {
    val array = JSONArray()

    places.forEach { place ->
        val obj = JSONObject()
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

fun jsonToPlaces(text: String): List<Place> {
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
    }

    return result
}


fun jsonToPlacesForPreview(text: String): List<Place> {
    val result = mutableListOf<Place>()

    try {
        val array = JSONArray(text)

        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val imageArray = obj.optJSONArray("images") ?: JSONArray()
            val images = mutableListOf<String>()

            for (j in 0 until imageArray.length()) {
                val item = imageArray.get(j)

                if (item is JSONObject) {
                    val oldUri = item.optString("uri", "")
                    val base64 = item.optString("base64", "")

                    when {
                        oldUri.isNotBlank() -> images.add(oldUri)
                        base64.isNotBlank() -> images.add("backup-image")
                    }
                } else {
                    images.add(item.toString())
                }
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

fun placesToJsonWithImages(context: Context, places: List<Place>): String {
    val array = JSONArray()

    places.forEach { place ->
        val obj = JSONObject()
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

        place.images.forEach { uriText ->
            val imageObj = JSONObject()
            imageObj.put("uri", uriText)

            try {
                val bytes = openShareAddImageInputStream(context, uriText)?.use {
                    it.readBytes()
                }

                if (bytes != null) {
                    imageObj.put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                }
            } catch (_: Exception) {
            }

            imageArray.put(imageObj)
        }

        obj.put("images", imageArray)
        array.put(obj)
    }

    return array.toString()
}

fun jsonToPlacesWithImages(context: Context, text: String): List<Place> {
    val result = mutableListOf<Place>()

    try {
        val array = JSONArray(text)

        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val imageArray = obj.optJSONArray("images") ?: JSONArray()
            val images = mutableListOf<String>()

            for (j in 0 until imageArray.length()) {
                val item = imageArray.get(j)

                if (item is JSONObject) {
                    val base64 = item.optString("base64", "")
                    val oldUri = item.optString("uri", "")

                    if (base64.isNotBlank()) {
                        val savedImage = saveBase64ImageToPrivateStorage(context, base64)
                        if (savedImage != null) {
                            images.add(savedImage)
                        }
                    } else if (oldUri.isNotBlank()) {
                        images.add(oldUri)
                    }
                } else {
                    images.add(item.toString())
                }
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
    }

    return result
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

fun saveBase64ImageToPrivateStorage(context: Context, base64: String): String? {
    return try {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        val imageFile = File(
            getShareAddImagesDir(context),
            "shareadd_restore_${System.currentTimeMillis()}_${UUID.randomUUID()}.jpg"
        )

        imageFile.outputStream().use {
            it.write(bytes)
        }

        imageFile.absolutePath
    } catch (_: Exception) {
        null
    }
}

fun openShareAddImageInputStream(context: Context, imageRef: String) = try {
    val file = File(imageRef)
    if (file.isAbsolute) {
        if (file.exists()) file.inputStream() else null
    } else {
        context.contentResolver.openInputStream(Uri.parse(imageRef))
    }
} catch (_: Exception) {
    null
}

fun shareAddImageUri(context: Context, imageRef: String): Uri? {
    return try {
        val file = File(imageRef)
        if (file.isAbsolute) {
            if (!file.exists()) return null

            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } else {
            Uri.parse(imageRef)
        }
    } catch (_: Exception) {
        null
    }
}
