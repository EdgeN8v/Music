package com.example.music.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.music.data.CacheSettings
import com.example.music.data.Changelog
import com.example.music.data.MusicMode
import com.example.music.data.MusicSource
import com.example.music.data.ServerConfig
import com.example.music.data.SettingsRepository
import com.example.music.data.SongRepository
import com.example.music.data.SubsonicClient
import com.example.music.data.UsbLibrarySource
import com.example.music.playback.AudioCache
import com.example.music.ui.theme.AppThemeMode
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed class ConnectionStatus {
    data object Idle : ConnectionStatus()
    data object Testing : ConnectionStatus()
    data object Success : ConnectionStatus()
    data class Error(val message: String) : ConnectionStatus()
}

/** Small (i) button that shows [text] in a dialog on tap — keeps explanatory copy off the screen until asked for. */
@Composable
private fun InfoIconButton(text: String) {
    var show by remember { mutableStateOf(false) }
    IconButton(onClick = { show = true }, modifier = Modifier.size(22.dp)) {
        Icon(Icons.Filled.Info, contentDescription = "说明", modifier = Modifier.size(16.dp))
    }
    if (show) {
        AlertDialog(
            onDismissRequest = { show = false },
            confirmButton = { TextButton(onClick = { show = false }) { Text("知道了") } },
            text = { Text(text, style = MaterialTheme.typography.bodyMedium) }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    currentTheme: AppThemeMode,
    onThemeChange: (AppThemeMode) -> Unit,
    initiallyConfigured: Boolean = false
) {
    val context = LocalContext.current
    val settingsRepository = remember { SettingsRepository(context) }
    val scope = rememberCoroutineScope()

    var serverUrl by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<ConnectionStatus>(ConnectionStatus.Idle) }
    val cacheSettings by settingsRepository.cacheSettings.collectAsState(initial = CacheSettings())
    val musicMode by MusicSource.mode.collectAsState()
    val usbPresent by MusicSource.usbPresent.collectAsState()
    val usbTreeUri by MusicSource.usbTreeUri.collectAsState()
    val isUsbActive by MusicSource.isUsbActive.collectAsState()

    val usbAccessLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        UsbLibrarySource.takePersistableAccess(context, uri)
    }

    // Starts correctly collapsed/expanded from the first frame (based on
    // whether a server's already configured, passed in from MainActivity's
    // synchronous startup read) — previously this always started expanded
    // and snapped shut a moment later once the async DataStore read landed,
    // which looked like an unwanted collapse animation on every visit.
    var connectionExpanded by remember { mutableStateOf(!initiallyConfigured) }

    // Load whatever was previously saved, and stay in sync if it's written
    // elsewhere (DataStore only emits again when we save, not per keystroke).
    LaunchedEffect(Unit) {
        settingsRepository.config.collect { config ->
            serverUrl = config.url
            username = config.username
            password = config.password
        }
    }

    val versionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) {
            null
        }
    }
    var showChangelog by remember { mutableStateOf(false) }
    var clearRandomResultText by remember { mutableStateOf<String?>(null) }
    var moodBackupResultText by remember { mutableStateOf<String?>(null) }

    // The mood label file (see SettingsRepository.moodLabels) is the only
    // place Energetic/Calm data lives — export/import moves that JSON blob
    // through whatever document picker/share target the user picks, so
    // switching or losing the phone doesn't mean re-marking every song from
    // scratch, and the initial bulk classification (tools/export_mood_labels.py)
    // comes in through this same import button.
    val moodExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val json = settingsRepository.exportMoodLabelsJson()
                context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                moodBackupResultText = "已导出"
            } catch (e: Exception) {
                moodBackupResultText = "导出失败：${e.message}"
            }
        }
    }
    val moodImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val json = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: throw IllegalStateException("读取不到文件内容")
                // NonCancellable: this screen's scope dies if the user taps
                // away to another tab before this finishes — the import
                // itself must always complete once started, same reasoning
                // as SongRepository.setMoodLabel.
                withContext(NonCancellable) {
                    settingsRepository.importMoodLabelsJson(json)
                    SongRepository.reapplyMoodLabels(context)
                }
                moodBackupResultText = "已导入，标记已生效"
            } catch (e: Exception) {
                moodBackupResultText = "导入失败：${e.message}"
            }
        }
    }

    // Polled rather than pushed — SimpleCache doesn't expose a usage flow,
    // and this only needs to be roughly right while the screen is visible.
    var usedBytes by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            usedBytes = AudioCache.currentUsageBytes()
            delay(3000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                actions = {
                    if (versionName != null) {
                        IconButton(onClick = { showChangelog = true }) {
                            Icon(Icons.Filled.Info, contentDescription = "版本 $versionName，查看更新内容")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            Text("Theme", style = MaterialTheme.typography.titleMedium)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)) {
                val options = listOf(
                    AppThemeMode.SYSTEM to "系统",
                    AppThemeMode.LIGHT to "浅色",
                    AppThemeMode.DARK to "深色"
                )
                options.forEachIndexed { index, (mode, label) ->
                    SegmentedButton(
                        selected = currentTheme == mode,
                        onClick = { onThemeChange(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size)
                    ) {
                        Text(label)
                    }
                }
            }

            Row(
                modifier = Modifier.padding(top = 4.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Text("音乐来源", style = MaterialTheme.typography.titleMedium)
                InfoIconButton("自动：插 U 盘就用 U 盘，没插就用网络。也可以在下面手动固定成只用网络或只用 U 盘。")
            }
            SingleChoiceSegmentedButtonRow(modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)) {
                val options = listOf(
                    MusicMode.AUTO to "自动",
                    MusicMode.NETWORK to "网络",
                    MusicMode.USB to "U盘"
                )
                options.forEachIndexed { index, (mode, label) ->
                    SegmentedButton(
                        selected = musicMode == mode,
                        onClick = { scope.launch { settingsRepository.saveMusicMode(mode) } },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size)
                    ) {
                        Text(label)
                    }
                }
            }
            Text(
                when {
                    usbTreeUri == null && !usbPresent -> "未检测到 U 盘"
                    usbTreeUri == null && usbPresent -> "🔌 检测到 U 盘，还没授权"
                    isUsbActive -> "🔌 U 盘模式"
                    else -> "网络模式"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)) {
                Button(onClick = { usbAccessLauncher.launch(UsbLibrarySource.createAccessIntent(context)) }) {
                    Text(if (usbTreeUri == null) "授权访问 U 盘" else "更换 / 重新授权 U 盘")
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { connectionExpanded = !connectionExpanded },
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Navidrome / Subsonic", style = MaterialTheme.typography.titleMedium)
                    if (!connectionExpanded) {
                        Text(
                            if (serverUrl.isNotBlank()) serverUrl else "未配置",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Icon(
                    if (connectionExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (connectionExpanded) "收起" else "展开"
                )
            }

            AnimatedVisibility(visible = connectionExpanded) {
                Column {
                    Text(
                        "连接到你部署在 NAS 上的 Navidrome",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it; status = ConnectionStatus.Idle },
                        label = { Text("Server URL，如 http://1.2.3.4:4533") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp)
                    )
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it; status = ConnectionStatus.Idle },
                        label = { Text("Username") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; status = ConnectionStatus.Idle },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                    )

                    Row(
                        modifier = Modifier.padding(top = 20.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Button(onClick = {
                            val config = ServerConfig(serverUrl, username, password)
                            status = ConnectionStatus.Testing
                            scope.launch {
                                settingsRepository.save(config)
                                when (val result = SubsonicClient.ping(config)) {
                                    is SubsonicClient.ApiResult.Success -> status = ConnectionStatus.Success
                                    is SubsonicClient.ApiResult.Failure -> status = ConnectionStatus.Error(result.message)
                                }
                            }
                        }) {
                            Text("保存并测试连接")
                        }

                        Row(modifier = Modifier.padding(start = 16.dp)) {
                            when (val s = status) {
                                is ConnectionStatus.Testing -> CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                                is ConnectionStatus.Success -> Text("连接成功 ✓", color = MaterialTheme.colorScheme.primary)
                                is ConnectionStatus.Error -> Text("连接失败：${s.message}", color = MaterialTheme.colorScheme.error)
                                is ConnectionStatus.Idle -> {}
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 28.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text("离线缓存", style = MaterialTheme.typography.titleMedium)
                    InfoIconButton(
                        "听过的歌会缓存在手机上，下次播放不用再走一遍网络。修改上限后需要重启 App 生效。\n\n" +
                            "「清理随机歌曲缓存」只清理既不是收藏、也没打 Energetic/Calm 标签的歌（也就是纯随机听到的）；激情/平静/收藏分类的缓存不会被清掉。"
                    )
                }
                Switch(
                    checked = cacheSettings.enabled,
                    onCheckedChange = { scope.launch { settingsRepository.saveCacheEnabled(it) } }
                )
            }

            if (cacheSettings.enabled) {
                var sliderGb by remember(cacheSettings.limitMb) {
                    mutableStateOf(cacheSettings.limitMb / 1000f)
                }
                val usedGb = usedBytes?.let { it / 1024f / 1024f / 1024f }

                Text(
                    "上限 ${"%.0f".format(sliderGb)}GB" +
                        (usedGb?.let { "　·　已缓存 ${"%.1f".format(it)}GB" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp)
                )
                Slider(
                    value = sliderGb,
                    onValueChange = { sliderGb = it },
                    onValueChangeFinished = {
                        scope.launch { settingsRepository.saveCacheLimitMb((sliderGb * 1000).toInt()) }
                    },
                    valueRange = 5f..20f,
                    steps = 14
                )

                OutlinedButton(
                    onClick = {
                        val removed = AudioCache.clearUnprotected(SongRepository.library.value)
                        usedBytes = AudioCache.currentUsageBytes()
                        clearRandomResultText = if (removed > 0) "已清理 $removed 首随机歌曲的缓存" else "没有可清理的随机歌曲缓存"
                    },
                    modifier = Modifier.padding(top = 12.dp)
                ) {
                    Text("清理随机歌曲缓存")
                }
                clearRandomResultText?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                modifier = Modifier.padding(top = 28.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Text("激情/平静标记", style = MaterialTheme.typography.titleMedium)
                InfoIconButton(
                    "每首歌的「激情/平静」现在只认这一份标记文件，不再看歌曲文件本身的 genre 标签——" +
                        "在 Library 长按一首歌改的，都是改这份文件。它只存在这台手机上，不会同步到别的设备，" +
                        "导出一份存到网盘/NAS/邮箱等地方，换手机后再导入就不用重新标一遍；" +
                        "首次用 tools/export_mood_labels.py 批量分类出来的结果，也是从这里导入进来。"
                )
            }
            Row(modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = { moodExportLauncher.launch("mood_labels.json") }) {
                    Text("导出到文件")
                }
                OutlinedButton(
                    onClick = { moodImportLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/*")) },
                    modifier = Modifier.padding(start = 12.dp)
                ) {
                    Text("从文件导入")
                }
            }
            moodBackupResultText?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }

    if (showChangelog) {
        AlertDialog(
            onDismissRequest = { showChangelog = false },
            confirmButton = {
                Button(onClick = { showChangelog = false }) { Text("关闭") }
            },
            title = { Text("更新日志") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Changelog.entries.forEach { entry ->
                        Text(
                            "v${entry.version}",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        entry.notes.forEach { note ->
                            Text("· $note", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        )
    }
}
