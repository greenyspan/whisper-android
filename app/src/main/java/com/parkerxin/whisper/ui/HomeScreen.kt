package com.parkerxin.whisper.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.parkerxin.whisper.data.Models

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: TranscribeViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            viewModel.selectFile(it, it.lastPathSegment ?: "未知文件")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("本地音频转文字") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 1. File selection
            FileSelectionCard(
                fileName = uiState.fileName,
                onSelectFile = { filePickerLauncher.launch(arrayOf("audio/*", "video/*")) },
            )

            // 2. Options
            OptionsCard(
                selectedModel = uiState.selectedModel,
                selectedLanguage = uiState.selectedLanguage,
                outputFormat = uiState.outputFormat,
                onModelChange = viewModel::selectModel,
                onLanguageChange = viewModel::selectLanguage,
                onFormatChange = viewModel::selectOutputFormat,
            )

            // 3. Start button
            Button(
                onClick = viewModel::startTranscribe,
                enabled = uiState.state == AppState.IDLE && uiState.selectedFile != null,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("开始转写", style = MaterialTheme.typography.titleMedium)
            }

            // 4. Progress / Status
            AnimatedVisibility(
                visible = uiState.state != AppState.IDLE && uiState.state != AppState.DONE,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                ProgressCard(uiState)
            }

            // 5. Error
            AnimatedVisibility(visible = uiState.state == AppState.ERROR) {
                ErrorCard(uiState.errorMessage ?: "未知错误") {
                    viewModel.reset()
                }
            }

            // 6. Result
            AnimatedVisibility(visible = uiState.state == AppState.DONE) {
                ResultCard(
                    result = uiState.result,
                    outputPath = uiState.outputPath,
                    onNewTask = { viewModel.reset() },
                )
            }
        }
    }
}

@Composable
private fun FileSelectionCard(fileName: String, onSelectFile: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.AudioFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = if (fileName.isEmpty()) "选择音频或视频文件" else fileName,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (fileName.isEmpty())
                    MaterialTheme.colorScheme.onSurfaceVariant
                else
                    MaterialTheme.colorScheme.onSurface,
            )
            IconButton(onClick = onSelectFile) {
                Icon(
                    Icons.Filled.FolderOpen,
                    contentDescription = "选择文件",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun OptionsCard(
    selectedModel: String,
    selectedLanguage: String,
    outputFormat: OutputFormat,
    onModelChange: (String) -> Unit,
    onLanguageChange: (String) -> Unit,
    onFormatChange: (OutputFormat) -> Unit,
) {
    var modelExpanded by remember { mutableStateOf(false) }
    var langExpanded by remember { mutableStateOf(false) }
    var formatExpanded by remember { mutableStateOf(false) }

    val modelDisplay = Models.all.first { it.key == selectedModel }.displayName

    val languages = mapOf(
        "auto" to "自动检测",
        "zh" to "中文",
        "en" to "英文",
        "ja" to "日文",
        "ko" to "韩文",
        "de" to "德文",
        "fr" to "法文",
        "es" to "西班牙文",
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("转写选项", style = MaterialTheme.typography.titleSmall)

            // Model
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "模型",
                    modifier = Modifier.width(64.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Box(modifier = Modifier.weight(1f)) {
                    OutlinedButton(
                        onClick = { modelExpanded = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(modelDisplay, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.Filled.ArrowDropDown, null)
                    }
                    DropdownMenu(
                        expanded = modelExpanded,
                        onDismissRequest = { modelExpanded = false },
                    ) {
                        Models.all.forEach { model ->
                            DropdownMenuItem(
                                text = { Text(model.displayName) },
                                onClick = {
                                    onModelChange(model.key)
                                    modelExpanded = false
                                },
                            )
                        }
                    }
                }
            }

            // Language
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "语言",
                    modifier = Modifier.width(64.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Box(modifier = Modifier.weight(1f)) {
                    OutlinedButton(
                        onClick = { langExpanded = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            languages[selectedLanguage] ?: selectedLanguage,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Icon(Icons.Filled.ArrowDropDown, null)
                    }
                    DropdownMenu(
                        expanded = langExpanded,
                        onDismissRequest = { langExpanded = false },
                    ) {
                        languages.forEach { (key, name) ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = {
                                    onLanguageChange(key)
                                    langExpanded = false
                                },
                            )
                        }
                    }
                }
            }

            // Output format
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "输出",
                    modifier = Modifier.width(64.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Box(modifier = Modifier.weight(1f)) {
                    OutlinedButton(
                        onClick = { formatExpanded = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(outputFormat.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.Filled.ArrowDropDown, null)
                    }
                    DropdownMenu(
                        expanded = formatExpanded,
                        onDismissRequest = { formatExpanded = false },
                    ) {
                        OutputFormat.entries.forEach { fmt ->
                            DropdownMenuItem(
                                text = { Text(fmt.label) },
                                onClick = {
                                    onFormatChange(fmt)
                                    formatExpanded = false
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgressCard(uiState: AppUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                uiState.statusMessage,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )

            when (uiState.state) {
                AppState.DOWNLOADING_MODEL -> {
                    LinearProgressIndicator(
                        progress = { uiState.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "${(uiState.progress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                AppState.TRANSCRIBING -> {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                else -> {}
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Error,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "转写失败",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(message, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onRetry) {
                Text("重新开始")
            }
        }
    }
}

@Composable
private fun ResultCard(
    result: com.parkerxin.whisper.whisper.TranscribeResult?,
    outputPath: String?,
    onNewTask: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "转写完成",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }

            if (result != null) {
                Text(
                    "共 ${result.segments.size} 个片段",
                    style = MaterialTheme.typography.bodySmall,
                )

                // Preview
                Text(
                    result.fullText.take(300).let {
                        if (result.fullText.length > 300) "$it…" else it
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                )

                Text(
                    "保存位置: $outputPath",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onNewTask) {
                    Text("新转写")
                }
            }
        }
    }
}
