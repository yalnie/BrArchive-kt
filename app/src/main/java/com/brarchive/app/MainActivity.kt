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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppScreen(this)
                }
            }
        }
    }

    fun requestStoragePermissionAndroid11Plus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                startActivity(intent)
            }
        }
    }
}

// 检查是否拥有存储权限（兼容各个安卓版本）
fun checkStoragePermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager() // Android 11+ 检查
    } else {
        // Android 10 及以下检查
        val read = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE)
        val write = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        read == PackageManager.PERMISSION_GRANTED && write == PackageManager.PERMISSION_GRANTED
    }
}

@Composable
fun AppScreen(activity: MainActivity) {
    val coroutineScope = rememberCoroutineScope()
    var inputPath by remember { mutableStateOf("/storage/emulated/0/Download/") }
    var outputPath by remember { mutableStateOf("") }
    var recursive by remember { mutableStateOf(false) }
    var dedup by remember { mutableStateOf(true) }
    var deleteSource by remember { mutableStateOf(false) }
    val logs = remember { mutableStateListOf("欢迎使用 brarchive-kt 安卓版！") }

    fun log(msg: String) { logs.add(msg) }

    // 动态权限状态与生命周期监听（用户去设置页返回后自动刷新 UI）
    var hasPermission by remember { mutableStateOf(checkStoragePermission(activity)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasPermission = checkStoragePermission(activity)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 安卓 10 及以下的传统弹窗权限请求
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasPermission = checkStoragePermission(activity)
    }

    Column(modifier = Modifier.padding(16.dp).fillMaxSize()) {
        if (!hasPermission) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("需要「存储访问权限」才能在手机中打包/解包文件。")
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            activity.requestStoragePermissionAndroid11Plus()
                        } else {
                            permissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.READ_EXTERNAL_STORAGE,
                                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                                )
                            )
                        }
                    }) {
                        Text("点击授予权限")
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        OutlinedTextField(
            value = inputPath,
            onValueChange = { inputPath = it },
            label = { Text("输入路径 (文件或目录)") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = outputPath,
            onValueChange = { outputPath = it },
            label = { Text("输出路径 (可选)") },
            modifier = Modifier.fillMaxWidth()
        )

        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(checked = recursive, onCheckedChange = { recursive = it })
            Text("递归目录")
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(checked = dedup, onCheckedChange = { dedup = it })
            Text("内容去重 (仅打包)")
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(checked = deleteSource, onCheckedChange = { deleteSource = it })
            Text("完成后删除源文件")
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Button(
                enabled = hasPermission,
                onClick = {
                    if (inputPath.isBlank()) { log("错误: 输入路径为空"); return@Button }
                    coroutineScope.launch {
                        log("开始打包...")
                        withContext(Dispatchers.IO) {
                            try {
                                val start = System.currentTimeMillis()
                                val path = File(inputPath)
                                val out = if (outputPath.isNotBlank()) File(outputPath) else null
                                if (recursive) {
                                    val root = File(out ?: path, "__brarchive")
                                    encodeRecursive(path, path, root, dedup, deleteSource) { log(it) }
                                } else {
                                    val outF = out ?: File(path.nameWithoutExtension + ".brarchive")
                                    encodeSingle(path, outF, dedup, deleteSource) { log(it) }
                                }
                                log(">> 打包完成，耗时: ${System.currentTimeMillis() - start}ms")
                            } catch (e: Exception) { log("错误: ${e.message}") }
                        }
                    }
                }
            ) { Text("打包 (Encode)") }

            Button(
                enabled = hasPermission,
                onClick = {
                    if (inputPath.isBlank()) { log("错误: 输入路径为空"); return@Button }
                    coroutineScope.launch {
                        log("开始解包...")
                        withContext(Dispatchers.IO) {
                            try {
                                val start = System.currentTimeMillis()
                                val path = File(inputPath)
                                val out = if (outputPath.isNotBlank()) File(outputPath) else null
                                if (recursive) {
                                    val root = File(path, "__brarchive")
                                    if (!root.exists()) throw Exception("未找到 __brarchive 目录")
                                    decodeRecursive(root, root, out ?: path, deleteSource) { log(it) }
                                } else {
                                    val outF = out ?: File(path.nameWithoutExtension)
                                    decodeSingle(path, outF, deleteSource) { log(it) }
                                }
                                log(">> 解包完成，耗时: ${System.currentTimeMillis() - start}ms")
                            } catch (e: Exception) { log("错误: ${e.message}") }
                        }
                    }
                }
            ) { Text("解包 (Decode)") }
        }

        Spacer(Modifier.height(16.dp))
        Text("运行日志:")
        Card(modifier = Modifier.fillMaxSize(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            LazyColumn(modifier = Modifier.padding(8.dp).fillMaxSize()) {
                items(logs) { msg ->
                    Text(text = msg, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

// ----------------- Android 版业务逻辑 -----------------

fun encodeSingle(path: File, out: File, dedup: Boolean, deleteSource: Boolean, log: (String) -> Unit) {
    if (!path.exists()) error("输入路径不存在")
    val entries = mutableMapOf<String, ByteArray>()
    if (path.isDirectory) {
        // 直接读取字节(Bytes)而不是文本(Text)
        path.listFiles()?.forEach { if (it.isFile) entries[it.name] = it.readBytes() }
    } else {
        entries[path.name] = path.readBytes()
    }
    out.writeBytes(BrArchive.serialize(entries, dedup))
    log(">> 生成: ${out.name}")
    if (deleteSource) if (path.isDirectory) path.deleteRecursively() else path.delete()
}

fun encodeRecursive(sourceRoot: File, current: File, archiveRoot: File, dedup: Boolean, deleteSource: Boolean, log: (String) -> Unit) {
    val subDirs = mutableListOf<File>()
    val files = mutableMapOf<String, ByteArray>()
    current.listFiles()?.forEach { f ->
        if (f.isDirectory) { 
            if (f.name != "__brarchive") subDirs.add(f) 
        } else if (f.isFile) {
            // 直接读取字节，不挑格式（图片、文本通吃）
            files[f.name] = f.readBytes()
        }
    }
    if (files.isNotEmpty()) {
        val rel = current.relativeTo(sourceRoot).path
        val outPath = if (rel.isEmpty()) File(archiveRoot, "${sourceRoot.name}.brarchive") else File(archiveRoot, "$rel.brarchive")
        outPath.parentFile?.mkdirs()
        outPath.writeBytes(BrArchive.serialize(files, dedup))
        log(">> 打包: ${outPath.name}")
        if (deleteSource) files.keys.forEach { File(current, it).delete() }
    }
    subDirs.forEach { encodeRecursive(sourceRoot, it, archiveRoot, dedup, deleteSource, log) }
}

fun decodeSingle(path: File, out: File, deleteSource: Boolean, log: (String) -> Unit) {
    if (!path.exists()) error("文件不存在")
    val archive = BrArchive.deserialize(path.readBytes())
    if (!out.exists()) out.mkdirs()
    for ((name, content) in archive) {
        val dest = File(out, name)
        dest.parentFile?.mkdirs()
        // 直接原封不动写入字节，防止损坏图片
        dest.writeBytes(content)
    }
    log("<< 解包: ${path.name} -> ${archive.size} 个文件")
    if (deleteSource) path.delete()
}

fun decodeRecursive(root: File, current: File, outRoot: File, deleteSource: Boolean, log: (String) -> Unit) {
    current.listFiles()?.forEach { f ->
        if (f.isDirectory) decodeRecursive(root, f, outRoot, deleteSource, log)
        else if (f.isFile && f.extension == "brarchive") {
            val rel = f.relativeTo(root).path.removeSuffix(".brarchive")
            val outDir = File(outRoot, rel)
            decodeSingle(f, outDir, deleteSource, log)
        }
    }
}