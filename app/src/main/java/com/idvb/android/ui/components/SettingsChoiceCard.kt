package com.idvb.android.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.idvb.android.ui.theme.Deep
import com.idvb.android.ui.theme.SignalGreen
import com.idvb.android.ui.theme.SignalGreenDeep

/** Single-choice counterpart of the settings pages' bordered option cards. */
@Composable
fun SettingsChoiceCard(
    title: String,
    description: String,
    icon: ImageVector,
    selected: Boolean,
    enabled: Boolean = true,
    onSelect: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(2.dp),
        color = colors.surfaceVariant.copy(alpha = .55f),
        border = BorderStroke(1.dp, if (selected && enabled) SignalGreenDeep else colors.outlineVariant),
    ) {
        Row(
            Modifier.fillMaxWidth()
                .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
                .padding(horizontal = 14.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(Modifier.size(42.dp), shape = RoundedCornerShape(2.dp), color = Deep) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = if (enabled) SignalGreen else colors.outline, modifier = Modifier.size(23.dp))
                }
            }
            Column(Modifier.weight(1f).padding(start = 14.dp, end = 10.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                    color = if (enabled) colors.onSurface else colors.onSurfaceVariant)
                Text(description, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            RadioButton(selected = selected, enabled = enabled, onClick = null,
                colors = RadioButtonDefaults.colors(selectedColor = SignalGreenDeep))
        }
    }
}
