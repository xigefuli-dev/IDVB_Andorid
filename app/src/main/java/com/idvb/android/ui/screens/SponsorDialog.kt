package com.idvb.android.ui.screens

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.idvb.android.R
import com.idvb.android.ui.theme.AfdianPurple

private const val AFDIAN_URL = "https://afdian.com/a/xigefuli?utm_source=copylink&utm_medium=link"

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun copyAfdianUrlToClipboard(context: Context, showToast: Boolean = true) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("爱发电赞助链接", AFDIAN_URL))
    if (showToast) {
        Toast.makeText(context, "赞助链接已复制，可前往浏览器打开", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun SponsorDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 400.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    Image(
                        painter = painterResource(R.drawable.sponsor_poster),
                        contentDescription = "IDVB 宣传海报",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1024f / 394f)
                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)),
                    )
                    Column(
                        modifier = Modifier.padding(20.dp),
                    ) {
                        Text(
                            text = "如果 IDVB 对您有所帮助，欢迎通过赞助支持项目持续开发。您的每一份支持，都会让 IDVB 变得更好。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            lineHeight = 22.sp,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "提示：若遇国产定制系统拦截外部跳转，可点击「复制链接」并在浏览器中打开。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 16.sp,
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                onClick = onDismiss,
                                shape = RoundedCornerShape(2.dp),
                            ) {
                                Text(
                                    "返回",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            OutlinedButton(
                                onClick = {
                                    copyAfdianUrlToClipboard(context)
                                },
                                shape = RoundedCornerShape(2.dp),
                            ) {
                                Text("复制链接")
                            }
                            Button(
                                onClick = {
                                    val activity = context.findActivity()
                                    val targetContext = activity ?: context
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(AFDIAN_URL)).apply {
                                        if (activity == null) {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                    }
                                    try {
                                        targetContext.startActivity(intent)
                                        onDismiss()
                                    } catch (e: Exception) {
                                        copyAfdianUrlToClipboard(context, showToast = false)
                                        Toast.makeText(
                                            context,
                                            "无法打开浏览器，已自动复制赞助链接",
                                            Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = AfdianPurple,
                                    contentColor = Color.White,
                                ),
                                shape = RoundedCornerShape(2.dp),
                            ) {
                                Text("前往赞助")
                            }
                        }
                    }
                }
            }
        }
    }
}
