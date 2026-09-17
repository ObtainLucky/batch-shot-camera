/**
 * AboutScreen.kt - 关于页
 *
 * 展示项目信息、作者与仓库入口，并提供"检查更新"（更新通道为 GitHub Releases）。
 *
 * 关于 GitHub 的限流：匿名调用公开接口按 IP 每小时 60 次，而手机常在运营商 NAT
 * 后面与大量用户共用出口 IP，额度很容易用尽。所以这里**不做自动检查**，
 * 只在用户点击时发请求，并在 10 分钟内提醒"刚查过"，避免连点把额度耗光。
 *
 * @author qihao
 * @since 2.1.0
 */
package com.qihao.filtercamera.presentation.about

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 项目信息常量（更新通道、作者、仓库） */
private const val REPO_URL = "https://github.com/ObtainLucky/batch-shot-camera"
private const val RELEASES_URL = "$REPO_URL/releases"
private const val ISSUES_URL = "$REPO_URL/issues"
private const val AUTHOR_URL = "https://github.com/ObtainLucky"
private const val UPSTREAM_URL = "https://github.com/Pangu-Immortal/FilterCamera"

/**
 * 关于页入口
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onNavigateBack: () -> Unit,
    viewModel: AboutViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showForceDialog by remember { mutableStateOf(false) }
    // 用户在弹窗里点了"再查一次"才真正发请求
    var forceCheckRequested by remember { mutableStateOf(false) }

    // 一次性提示
    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    // 刚查过还点：先问一句，避免连点把 GitHub 匿名额度耗光
    LaunchedEffect(forceCheckRequested) {
        if (forceCheckRequested) {
            forceCheckRequested = false
            viewModel.checkForUpdate(force = true)
        }
    }

    val openUrl: (String) -> Unit = { url ->
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("关于") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---- 项目信息 ----
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "批次拍摄相机",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "按批次/工单组织拍摄：自动命名、自动归档到对应目录，" +
                            "支持工作模式、U 数分组、多轮次与信息水印。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    InfoRow("版本", viewModel.currentVersion)
                    InfoRow("包名", "com.qihao.filtercamera")
                    InfoRow("基于", "FilterCamera（MIT 协议）")
                }
            }

            // ---- 检查更新 ----
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("检查更新", fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "更新通道为 GitHub Releases。GitHub 公开接口对匿名调用" +
                            "按 IP 限流（每小时 60 次），所以不会自动检查，10 分钟内也" +
                            "只查一次。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    uiState.lastCheckAt.takeIf { it > 0 }?.let { at ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "上次检查：" + SimpleDateFormat(
                                "yyyy-MM-dd HH:mm", Locale.getDefault()
                            ).format(Date(at)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = {
                                if (viewModel.isCacheFresh()) showForceDialog = true
                                else viewModel.checkForUpdate()
                            },
                            enabled = !uiState.checking
                        ) {
                            if (uiState.checking) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("检查中…")
                            } else {
                                Text("检查更新")
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { openUrl(RELEASES_URL) }) {
                            Text("所有发行版")
                        }
                    }

                    // 结果
                    uiState.update?.let { info ->
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(12.dp))
                        if (info.hasUpdate) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Download,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "发现新版本 ${info.latestVersion}",
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            if (info.publishedAt.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "发布于 ${
                                        info.publishedAt.substringBefore('T')
                                    }",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (info.assetName.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "安装包 ${info.assetName}" +
                                        if (info.assetSizeBytes > 0) {
                                            "（${"%.1f".format(info.assetSizeBytes / 1048576.0)} MB）"
                                        } else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Row {
                                // 优先直达安装包；链接失效时用户还能进发行版页
                                Button(
                                    onClick = {
                                        openUrl(
                                            info.assetUrl.ifBlank { info.releaseUrl }
                                        )
                                    }
                                ) { Text("下载更新") }
                                Spacer(Modifier.width(8.dp))
                                OutlinedButton(onClick = { openUrl(info.releaseUrl) }) {
                                    Text("查看说明")
                                }
                            }
                            if (info.notes.isNotBlank()) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = info.notes.take(600),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("已是最新版本（${info.latestVersion}）")
                            }
                        }
                    }
                }
            }

            // ---- 作者与仓库 ----
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    LinkRow(Icons.Default.Code, "项目仓库", REPO_URL, openUrl)
                    LinkRow(Icons.Default.Download, "发行版 / 更新", RELEASES_URL, openUrl)
                    LinkRow(Icons.Default.OpenInNew, "问题反馈", ISSUES_URL, openUrl)
                    LinkRow(Icons.Default.Person, "作者 ObtainLucky", AUTHOR_URL, openUrl)
                    LinkRow(Icons.Default.Person, "上游项目 Pangu-Immortal", UPSTREAM_URL, openUrl)
                }
            }

            // ---- 说明 ----
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("说明", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "本项目基于 FilterCamera 改造（MIT 协议），改动集中在批次拍摄" +
                            "相关的命名、归档与工作流；相机原有的预览、滤镜、水印等能力保留。\n\n" +
                            "更新检查只读取 GitHub 的公开发行版信息，不上传任何内容；" +
                            "照片与批次数据始终保存在本机。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }

    // 强制检查的确认
    if (showForceDialog) {
        AlertDialog(
            onDismissRequest = { showForceDialog = false },
            title = { Text("刚检查过，仍要再查？") },
            text = {
                Text(
                    "GitHub 公开接口对匿名调用按 IP 限流（每小时 60 次），" +
                        "剩余额度可用约 ${viewModel.secondsUntilCacheExpires()} 秒后过期。" +
                        "频繁检查可能导致接口暂时不可用。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showForceDialog = false
                    forceCheckRequested = true
                }) { Text("再查一次") }
            },
            dismissButton = {
                TextButton(onClick = { showForceDialog = false }) { Text("取消") }
            }
        )
    }
}

/** 信息行：左标签右值 */
@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace
        )
    }
}

/** 可点击的链接行 */
@Composable
private fun LinkRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    url: String,
    onOpen: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(url) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = url,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        Icon(
            Icons.Default.OpenInNew,
            contentDescription = "打开",
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
