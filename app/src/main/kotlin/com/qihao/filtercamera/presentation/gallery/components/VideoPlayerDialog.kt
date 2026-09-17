/**
 * VideoPlayerDialog.kt - 应用内视频播放器
 *
 * 相册里的视频以前只有一个装饰性的播放图标：点了没反应，缩略图也是空白
 * （AsyncImage 拿到 video 类型的 Uri 无法解码）。这里补上真正的播放能力。
 *
 * 用系统自带的 VideoView 而不是引入 ExoPlayer：本项目只需要"能看"，不想为了
 * 一个播放器再背一个 media3 依赖。VideoView 由代码创建，没有 XML 布局。
 *
 * @author qihao
 * @since 2.0.0
 */
package com.qihao.filtercamera.presentation.gallery.components

import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * 全屏视频播放弹窗
 *
 * @param uri 视频Uri（content:// 或 file:// 都可以，VideoView 两者都吃）
 * @param title 顶部显示的文件名
 * @param onDismiss 关闭回调
 */
@Composable
fun VideoPlayerDialog(
    uri: Uri,
    title: String,
    onDismiss: () -> Unit
) {
    // 拿住 VideoView 引用，弹窗关闭时停止播放 —— 否则声音会在界面消失后继续响
    val videoViewRef = remember { mutableStateOf<VideoView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            videoViewRef.value?.let { view ->
                runCatching {
                    view.stopPlayback()
                }
            }
            videoViewRef.value = null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { ctx ->
                        VideoView(ctx).apply {
                            setVideoURI(uri)
                            // 用系统媒体控制器提供播放/暂停/进度条
                            val controller = MediaController(ctx)
                            controller.setAnchorView(this)
                            setMediaController(controller)
                            setOnPreparedListener { player ->
                                player.isLooping = false
                                start()                                  // 打开即播
                            }
                        }.also { videoViewRef.value = it }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // 顶部：文件名 + 关闭
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = title,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "关闭",
                            tint = Color.White
                        )
                    }
                }
            }
        }
    }
}
