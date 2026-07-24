package com.brarchive.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext
import com.brarchive.app.ui.theme.AppTheme

enum class ThemeMode { LIGHT, SYSTEM, DARK }

class ThemePreferences(context: Context) {
    private val prefs = context.getSharedPreferences("app_theme_prefs", Context.MODE_PRIVATE)

    var themeMode: ThemeMode
        get() {
            val name = prefs.getString("theme_mode", ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name
            return try { ThemeMode.valueOf(name) } catch (e: Exception) { ThemeMode.SYSTEM }
        }
        set(value) = prefs.edit().putString("theme_mode", value.name).apply()

    var isDynamicColor: Boolean
        get() = prefs.getBoolean("dynamic_color", true)
        set(value) = prefs.edit().putBoolean("dynamic_color", value).apply()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val themePrefs = ThemePreferences(this)

        setContent {
            var dynamicColorEnabled by remember { mutableStateOf(themePrefs.isDynamicColor) }
            var themeMode by remember { mutableStateOf(themePrefs.themeMode) }

            val darkTheme = when (themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }

            DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT
                    ) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT
                    ) { darkTheme }
                )
                onDispose { }
            }

            AppTheme(
                darkTheme = darkTheme,
                dynamicColor = dynamicColorEnabled
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppScreen(
                        activity = this,
                        dynamicColorEnabled = dynamicColorEnabled,
                        onDynamicColorChange = { newValue ->
                            dynamicColorEnabled = newValue
                            themePrefs.isDynamicColor = newValue
                        },
                        themeMode = themeMode,
                        onThemeModeChange = { newMode ->
                            themeMode = newMode
                            themePrefs.themeMode = newMode
                        }
                    )
                }
            }
        }
    }
}

fun checkStoragePermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        val read = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE)
        val write = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        read == PackageManager.PERMISSION_GRANTED && write == PackageManager.PERMISSION_GRANTED
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(
    activity: MainActivity,
    dynamicColorEnabled: Boolean,
    onDynamicColorChange: (Boolean) -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var showMenu by remember { mutableStateOf(false) }
    var showLicenseDialog by remember { mutableStateOf(false) }
    var licenseText by remember { mutableStateOf("") }

    var inputPath by remember { mutableStateOf("/storage/emulated/0/Download/") }
    var outputPath by remember { mutableStateOf("") }
    var recursive by remember { mutableStateOf(false) }
    var dedup by remember { mutableStateOf(true) }
    var deleteSource by remember { mutableStateOf(false) }

    var isProcessing by remember { mutableStateOf(false) }
    var activeJob by remember { mutableStateOf<Job?>(null) }

    val initialWelcome = stringResource(R.string.welcome_log)
    val logs = remember { mutableStateListOf(initialWelcome) }
    fun log(msg: String) { logs.add(msg) }

    var hasPermission by remember { mutableStateOf(checkStoragePermission(activity)) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME || event == Lifecycle.Event.ON_START) {
                hasPermission = checkStoragePermission(activity)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val manageStorageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        hasPermission = checkStoragePermission(activity)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasPermission = checkStoragePermission(activity)
    }

    if (showLicenseDialog) {
        val licenseVertScroll = rememberScrollState()
        val licenseHorizScroll = rememberScrollState()
        AlertDialog(
            onDismissRequest = { showLicenseDialog = false },
            confirmButton = {
                TextButton(onClick = { showLicenseDialog = false }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            title = { Text(stringResource(R.string.license_title)) },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 450.dp)
                        .horizontalScroll(licenseHorizScroll)
                        .verticalScroll(licenseVertScroll)
                ) {
                    Text(
                        text = licenseText,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        softWrap = false
                    )
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    Box {
                        IconButton(onClick = { showMenu = !showMenu }) {
                            Icon(
                                painter = painterResource(R.drawable.more_vert_24px),
                                contentDescription = stringResource(R.string.menu)
                            )
                        }

                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            modifier = Modifier.width(230.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(R.string.theme_title),
                                        style = MaterialTheme.typography.bodyMedium
                                    )

                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        modifier = Modifier.height(38.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(2.dp),
                                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            val modes = listOf(
                                                ThemeMode.LIGHT to R.drawable.light_mode_24px,
                                                ThemeMode.SYSTEM to R.drawable.brightness_auto_24px,
                                                ThemeMode.DARK to R.drawable.dark_mode_24px
                                            )

                                            modes.forEach { (mode, iconRes) ->
                                                val isSelected = themeMode == mode
                                                Surface(
                                                    shape = CircleShape,
                                                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                                    modifier = Modifier
                                                        .size(34.dp)
                                                        .clickable { onThemeModeChange(mode) }
                                                ) {
                                                    Box(contentAlignment = Alignment.Center) {
                                                        Icon(
                                                            painter = painterResource(id = iconRes),
                                                            contentDescription = null,
                                                            modifier = Modifier.size(18.dp),
                                                            tint = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .toggleable(
                                                value = dynamicColorEnabled,
                                                role = Role.Switch,
                                                onValueChange = { onDynamicColorChange(it) }
                                            ),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = stringResource(R.string.dynamic_color),
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Switch(
                                            checked = dynamicColorEnabled,
                                            onCheckedChange = null,
                                            modifier = Modifier.graphicsLayer(scaleX = 0.85f, scaleY = 0.85f)
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            showMenu = false
                                            val intent = Intent(
                                                Intent.ACTION_VIEW,
                                                Uri.parse("https://github.com/yinghuajimew/BrArchive-kt")
                                            )
                                            context.startActivity(intent)
                                        }
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(R.string.github_title),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Icon(
                                        painter = painterResource(R.drawable.github_24px),
                                        contentDescription = "GitHub",
                                        modifier = Modifier.size(32.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            showMenu = false
                                            try {
                                                licenseText = context.assets.open("LICENSE")
                                                    .bufferedReader().use { it.readText() }
                                                showLicenseDialog = true
                                            } catch (e: Exception) {
                                                log("LICENSE file not found in assets.")
                                            }
                                        }
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(R.string.license_title),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Icon(
                                        painter = painterResource(R.drawable.license_24px),
                                        contentDescription = stringResource(R.string.license_title),
                                        modifier = Modifier.size(32.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            val isCompactHeight = maxHeight < 550.dp

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (isCompactHeight) Modifier.verticalScroll(scrollState) else Modifier)
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = {
                            focusManager.clearFocus()
                        })
                    }
                    .padding(16.dp)
            ) {
                if (!hasPermission) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = stringResource(R.string.permission_required),
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(
                                enabled = !isProcessing,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError
                                ),
                                onClick = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                        try {
                                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                                data = Uri.parse("package:${activity.packageName}")
                                            }
                                            manageStorageLauncher.launch(intent)
                                        } catch (e: Exception) {
                                            val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                            manageStorageLauncher.launch(intent)
                                        }
                                    } else {
                                        permissionLauncher.launch(
                                            arrayOf(
                                                Manifest.permission.READ_EXTERNAL_STORAGE,
                                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                                            )
                                        )
                                    }
                                }
                            ) {
                                Text(stringResource(R.string.grant_permission))
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }

                OutlinedTextField(
                    value = inputPath,
                    onValueChange = { inputPath = it },
                    label = { Text(stringResource(R.string.input_path_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isProcessing,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() })
                )

                OutlinedTextField(
                    value = outputPath,
                    onValueChange = { outputPath = it },
                    label = { Text(stringResource(R.string.output_path_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isProcessing,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() })
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = recursive,
                        onCheckedChange = { recursive = it },
                        enabled = !isProcessing
                    )
                    Text(stringResource(R.string.recursive))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = dedup,
                        onCheckedChange = { dedup = it },
                        enabled = !isProcessing
                    )
                    Text(stringResource(R.string.dedup))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = deleteSource,
                        onCheckedChange = { deleteSource = it },
                        enabled = !isProcessing
                    )
                    Text(stringResource(R.string.delete_source))
                }

                Spacer(Modifier.height(8.dp))

                if (isProcessing) {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        onClick = {
                            activeJob?.cancel()
                        }
                    ) {
                        Text(
                            text = stringResource(R.string.cancel_button),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 【修改点】：处理输入为空及输出目录智能追加文件名的逻辑
                        Button(
                            modifier = Modifier.weight(1f),
                            enabled = hasPermission,
                            onClick = {
                                focusManager.clearFocus()
                                if (inputPath.isBlank()) {
                                    log(context.getString(R.string.err_empty_input))
                                    return@Button
                                }
                                activeJob = coroutineScope.launch {
                                    isProcessing = true
                                    log(context.getString(R.string.start_encoding))
                                    try {
                                        withContext(Dispatchers.IO) {
                                            val start = System.currentTimeMillis()
                                            val path = File(inputPath)
                                            val out = if (outputPath.isNotBlank()) File(outputPath) else null
                                            if (recursive) {
                                                val root = out ?: File(path, "__brarchive")
                                                encodeRecursive(context, path, path, root, dedup, deleteSource) { log(it) }
                                            } else {
                                                // 修复：如果输出路径填的是文件夹，智能为其追加 .brarchive 文件名避免 EISDIR 错误
                                                val defaultName = "${path.nameWithoutExtension}.brarchive"
                                                val outF = if (out != null) {
                                                    if (out.isDirectory || outputPath.endsWith("/") || outputPath.endsWith("\\")) {
                                                        File(out, defaultName)
                                                    } else out
                                                } else File(path.parentFile, defaultName)

                                                encodeSingle(context, path, outF, dedup, deleteSource) { log(it) }
                                            }
                                            val elapsed = System.currentTimeMillis() - start
                                            log(context.getString(R.string.encode_complete, elapsed))
                                        }
                                    } catch (e: CancellationException) {
                                        log(context.getString(R.string.operation_cancelled))
                                    } catch (e: Exception) {
                                        log("${e.message}")
                                    } finally {
                                        isProcessing = false
                                        activeJob = null
                                    }
                                }
                            }
                        ) {
                            Text(
                                text = stringResource(R.string.encode_button),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Button(
                            modifier = Modifier.weight(1f),
                            enabled = hasPermission,
                            onClick = {
                                focusManager.clearFocus()
                                if (inputPath.isBlank()) {
                                    log(context.getString(R.string.err_empty_input))
                                    return@Button
                                }
                                activeJob = coroutineScope.launch {
                                    isProcessing = true
                                    log(context.getString(R.string.start_decoding))
                                    try {
                                        withContext(Dispatchers.IO) {
                                            val start = System.currentTimeMillis()
                                            val path = File(inputPath)
                                            val out = if (outputPath.isNotBlank()) File(outputPath) else null
                                            if (recursive) {
                                                val root = File(path, "__brarchive")
                                                if (!root.exists()) throw Exception(context.getString(R.string.err_not_found_brarchive))
                                                val outF = out ?: path
                                                decodeRecursive(context, root, root, outF, deleteSource) { log(it) }
                                            } else {
                                                // 修复：如果输出路径填的是文件夹，智能解包到该目录下
                                                val defaultName = path.nameWithoutExtension
                                                val outF = if (out != null) {
                                                    if (out.isDirectory || outputPath.endsWith("/") || outputPath.endsWith("\\")) {
                                                        File(out, defaultName)
                                                    } else out
                                                } else File(path.parentFile, defaultName)

                                                decodeSingle(context, path, outF, deleteSource) { log(it) }
                                            }
                                            val elapsed = System.currentTimeMillis() - start
                                            log(context.getString(R.string.decode_complete, elapsed))
                                        }
                                    } catch (e: CancellationException) {
                                        log(context.getString(R.string.operation_cancelled))
                                    } catch (e: Exception) {
                                        log("${e.message}")
                                    } finally {
                                        isProcessing = false
                                        activeJob = null
                                    }
                                }
                            }
                        ) {
                            Text(
                                text = stringResource(R.string.decode_button),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.run_log_header))
                Spacer(Modifier.height(4.dp))
                
                // 【修改点】：增加复制日志按钮的 Box 层
                Box(
                    modifier = if (isCompactHeight) {
                        Modifier.fillMaxWidth().height(200.dp)
                    } else {
                        Modifier.fillMaxWidth().weight(1f)
                    }
                ) {
                    Card(
                        modifier = Modifier.fillMaxSize(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        LazyColumn(modifier = Modifier.padding(8.dp).fillMaxSize()) {
                            items(logs) { msg ->
                                Text(text = msg, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }

                    // 复制日志按钮浮层
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .clickable {
                                val clip = android.content.ClipData.newPlainText("brarchive_logs", logs.joinToString("\n"))
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                cm.setPrimaryClip(clip)
                                android.widget.Toast.makeText(context, "已复制", android.widget.Toast.LENGTH_SHORT).show()
                            },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                    ) {
                        Text(
                            text = "复制",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

// 【修改点】：修复 encodeSingle 丢弃子目录的问题
suspend fun encodeSingle(context: Context, path: File, out: File, dedup: Boolean, deleteSource: Boolean, log: (String) -> Unit) {
    coroutineContext.ensureActive()
    if (!path.exists()) error(context.getString(R.string.err_input_not_exists))
    
    val entries = mutableMapOf<String, File>()
    if (path.isDirectory) {
        // walkTopDown() 会深度遍历所有层级的子目录
        path.walkTopDown().forEach { file ->
            coroutineContext.ensureActive()
            if (file.isFile) {
                // 使用相对于根目录的相对路径作为内部存储名，并把反斜杠规范化为正斜杠
                // 例如: "textures/blocks/dirt.png"
                val relativeName = file.relativeTo(path).path.replace("\\", "/")
                entries[relativeName] = file
            }
        }
    } else {
        entries[path.name] = path
    }
    
    if (entries.isEmpty()) error("目录为空，未找到任何可打包的文件")
    
    out.parentFile?.mkdirs()
    BrArchive.encode(entries, out, dedup)
    
    log(">> ${out.name}")
    if (deleteSource) if (path.isDirectory) path.deleteRecursively() else path.delete()
}

suspend fun encodeRecursive(context: Context, sourceRoot: File, current: File, archiveRoot: File, dedup: Boolean, deleteSource: Boolean, log: (String) -> Unit) {
    coroutineContext.ensureActive()
    val subDirs = mutableListOf<File>()
    val files = mutableMapOf<String, File>()
    current.listFiles()?.forEach { f ->
        coroutineContext.ensureActive()
        if (f.isDirectory) {
            if (f.name != "__brarchive") subDirs.add(f)
        } else if (f.isFile) {
            files[f.name] = f
        }
    }
    if (files.isNotEmpty()) {
        val rel = current.relativeTo(sourceRoot).path
        val outPath = if (rel.isEmpty()) File(archiveRoot, "${sourceRoot.name}.brarchive") else File(archiveRoot, "$rel.brarchive")
        outPath.parentFile?.mkdirs()
        BrArchive.encode(files, outPath, dedup)
        log(">> ${outPath.name}")
        if (deleteSource) files.values.forEach { it.delete() }
    }
    subDirs.forEach { encodeRecursive(context, sourceRoot, it, archiveRoot, dedup, deleteSource, log) }
}

suspend fun decodeSingle(context: Context, path: File, out: File, deleteSource: Boolean, log: (String) -> Unit) {
    coroutineContext.ensureActive()
    if (!path.exists()) error(context.getString(R.string.err_file_not_found))
    BrArchive.decode(path, out)
    log("<< ${path.name}")
    if (deleteSource) path.delete()
}

suspend fun decodeRecursive(context: Context, root: File, current: File, outRoot: File, deleteSource: Boolean, log: (String) -> Unit) {
    coroutineContext.ensureActive()
    current.listFiles()?.forEach { f ->
        coroutineContext.ensureActive()
        if (f.isDirectory) decodeRecursive(context, root, f, outRoot, deleteSource, log)
        else if (f.isFile && f.extension == "brarchive") {
            val rel = f.relativeTo(root).path.removeSuffix(".brarchive")
            val outDir = File(outRoot, rel)
            decodeSingle(context, f, outDir, deleteSource, log)
        }
    }
}