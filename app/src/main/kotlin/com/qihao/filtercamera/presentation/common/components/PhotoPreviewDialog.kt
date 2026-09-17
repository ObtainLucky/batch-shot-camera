/**
 * PhotoPreviewDialog.kt - 全屏查看单张照片
 *
 * 拍摄清单里每一项右侧会带一张缩略图，点开用它核对"这张是不是拍错了"。
 * 现场是拿着手机逐项确认，所以这里只做最简单的事：整屏显示 + 关闭，
 * 不做缩放/滑动浏览（那属于相册页）。
 *
 * @author qihao
 * @since 2.1.0
 */
package com.qihao.filtercamera.presentation.common.components

import android.net.Uri
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest

/**
 * 全屏照片查看弹窗
 *
 * @param uri 照片 Uri
 * @param title 顶部标题（通常传文件名，便于确认拍的是哪一项）
 * @param onDismiss 关闭回调
 */
@Composable
fun PhotoPreviewDialog(
    uri: Uri,
    title: String,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                AsyncImage(
                    model = ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                        .data(uri)
                        .crossfade(true)
                        .build(),
                    contentDescription = title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )

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
