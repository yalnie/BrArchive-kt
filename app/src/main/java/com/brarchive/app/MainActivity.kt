package com.brarchive.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
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

    fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.addCategory("android.intent.category.DEFAULT")
                intent.data = Uri.parse(String.format("package:%s", applicationContext.packageName))
                startActivity(intent)
            } catch (e: Exception) {
                val intent = Intent()
                intent.action = Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
                startActivity(intent)
            }
        }
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
    val logs = remember { mutableStateListOf<String>("欢迎使用 brarchive-kt 安卓版！") }

    fun log(msg: String) { logs.add(msg) }

    // 检查是否有所有文件访问权限（Android 11+）
    val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        true // 旧版本在此省略动态请求代码，一般可以直接读写
    }

    Column(modifier = Modifier.padding(16.dp).fillMaxSize()) {
        if (!hasPermission) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("需要「所有文件访问权限」才能在手机存储中打包/解包文件。")
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { activity.requestStoragePermission() }) {
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
            Button(onClick = {
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
            }) { Text("打包 (Encode)") }

            Button(onClick = {
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
            }) { Text("解包 (Decode)") }
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
    val entries = mutableMapOf<String, String>()
    if (path.isDirectory) {
        path.listFiles()?.forEach { if (it.isFile) entries[it.name] = it.readText(Charsets.UTF_8) }
    } else {
        entries[path.name] = path.readText(Charsets.UTF_8)
    }
    out.writeBytes(BrArchive.serialize(entries, dedup))
    log(">> 生成: ${out.name}")
    if (deleteSource) if (path.isDirectory) path.deleteRecursively() else path.delete()
}

fun encodeRecursive(sourceRoot: File, current: File, archiveRoot: File, dedup: Boolean, deleteSource: Boolean, log: (String) -> Unit) {
    val subDirs = mutableListOf<File>()
    val files = mutableMapOf<String, String>()
    current.listFiles()?.forEach { f ->
        if (f.isDirectory) { if (f.name != "__brarchive") subDirs.add(f) }
        else if (f.isFile) try { files[f.name] = f.readText(Charsets.UTF_8) } catch (e: Exception) { log("跳过非UTF-8: ${f.name}") }
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
        dest.writeText(content, Charsets.UTF_8)
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