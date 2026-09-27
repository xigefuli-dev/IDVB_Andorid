package com.idvb.android.ui.screens

import android.widget.Toast
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForwardIos
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PanToolAlt
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.SettingsSuggest
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.ViewModule
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.idvb.android.BuildConfig
import com.idvb.android.R
import com.idvb.android.ui.theme.Deep
import com.idvb.android.ui.theme.Ink
import com.idvb.android.ui.theme.InkSoft
import com.idvb.android.ui.theme.Paper
import com.idvb.android.ui.theme.SignalGreen
import com.idvb.android.ui.theme.SignalGreenDeep

private data class SettingEntry(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val color: Color,
)

private val productEntries = listOf(
    SettingEntry("关于", "版本、许可与项目信息", Icons.Outlined.Info, SignalGreenDeep),
    SettingEntry("检查更新", "获取最新版本", Icons.Outlined.SystemUpdate, SignalGreenDeep),
)

private val featureEntries = listOf(
    SettingEntry("通用", "外观、后台运行与基础选项", Icons.Outlined.SettingsSuggest, Ink),
    SettingEntry("视觉", "识别、地图与悬浮层显示", Icons.Outlined.Palette, Ink),
    SettingEntry("操作", "快捷操作、按键与无障碍", Icons.Outlined.PanToolAlt, Ink),
    SettingEntry("模板", "管理创建地图时使用的楼层模板", Icons.Outlined.ViewModule, Ink),
)

@Composable
fun SettingsScreen(
    onOpenGeneral: () -> Unit = {},
    onOpenVision: () -> Unit = {},
    onOpenTemplates: () -> Unit = {},
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(26.dp))
        Text(
            "设置",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier,
        )
        Spacer(Modifier.height(28.dp))

        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                modifier = Modifier.size(96.dp),
                shape = RoundedCornerShape(2.dp),
                color = Deep,
                tonalElevation = 0.dp,
            ) {
                Image(
                    painter = painterResource(R.drawable.idvb_logo),
                    contentDescription = "IDVB 图标",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.padding(10.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Identity Vision Bridge",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Normal,
            )
            Text(
                BuildConfig.VERSION_NAME,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(28.dp))
        SettingSection("IDVB", productEntries) {
            Toast.makeText(context, it.title + "：暂未实现", Toast.LENGTH_SHORT).show()
        }
        Spacer(Modifier.height(22.dp))
        SettingSection("功能设置", featureEntries) {
            when (it.title) {
                "通用" -> onOpenGeneral()
                "视觉" -> onOpenVision()
                "模板" -> onOpenTemplates()
                else -> Toast.makeText(context, it.title + "设置：暂未实现", Toast.LENGTH_SHORT).show()
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun SettingSection(
    label: String,
    entries: List<SettingEntry>,
    onClick: (SettingEntry) -> Unit,
) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = SignalGreenDeep,
        modifier = Modifier.padding(bottom = 10.dp),
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(2.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f),
        tonalElevation = 0.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            entries.forEachIndexed { index, entry ->
                SettingRow(entry = entry, onClick = { onClick(entry) })
                if (index != entries.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 70.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f),
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingRow(entry: SettingEntry, onClick: () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    val neutralEntry = entry.color == Ink
    val iconBackground = if (neutralEntry && darkTheme) Color(0xFF343945) else entry.color
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(42.dp),
            shape = RoundedCornerShape(2.dp),
            color = iconBackground,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    entry.icon,
                    contentDescription = null,
                    tint = if (neutralEntry) SignalGreen else Paper,
                    modifier = Modifier.size(23.dp),
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f).padding(start = 14.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(entry.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                entry.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.AutoMirrored.Outlined.ArrowForwardIos,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(16.dp),
        )
    }
}
