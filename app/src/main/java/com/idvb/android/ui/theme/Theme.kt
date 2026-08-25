package com.idvb.android.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Paper = Color(0xFFF7F6F0)
val PaperTwo = Color(0xFFE9ECE3)
val Ink = Color(0xFF1E2422)
val InkSoft = Color(0xFF4A514D)
val Deep = Color(0xFF17191E)
val DeepTwo = Color(0xFF24272E)
val SignalGreen = Color(0xFF8EE85D)
val SignalGreenDeep = Color(0xFF579A3B)
val SignalGreenSoft = Color(0xFFC7EFAD)

private val LightColors = lightColorScheme(
    primary = Color(0xFF20301F), onPrimary = Color.White, primaryContainer = Color(0xFFB9E99C), onPrimaryContainer = Color(0xFF172414),
    secondary = Color(0xFF397E2E), onSecondary = Color.White, secondaryContainer = Color(0xFFD6F3C3),
    onSecondaryContainer = Color(0xFF152511), background = Paper, onBackground = Color(0xFF171C19), surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF171C19), surfaceVariant = PaperTwo, onSurfaceVariant = Color(0xFF515A54),
    outline = Color(0xFF6F7972), outlineVariant = Color(0xFFC9CFC8),
    inverseSurface = Deep, inverseOnSurface = Paper, error = Color(0xFF9F332F),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9AF16A), onPrimary = Color(0xFF10220A), primaryContainer = Color(0xFF355F29),
    onPrimaryContainer = Color(0xFFF3FFEc), secondary = Color(0xFF8EE85D), background = Color(0xFF0D0F12),
    onBackground = Color(0xFFF2F3F5), surface = Color(0xFF1A1D22), onSurface = Color(0xFFF4F5F6),
    surfaceVariant = Color(0xFF252932), onSurfaceVariant = Color(0xFFC5C9D0), outline = Color(0xFF9298A2),
    outlineVariant = Color(0xFF404650), inverseSurface = Color(0xFFE9EBEF), inverseOnSurface = Color(0xFF181B20),
)

private val IDVBTypography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal, fontSize = 52.sp, lineHeight = 52.sp, letterSpacing = (-1.8).sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal, fontSize = 38.sp, lineHeight = 40.sp, letterSpacing = (-1).sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal, fontSize = 32.sp, lineHeight = 35.sp, letterSpacing = (-.6).sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal, fontSize = 27.sp, lineHeight = 31.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 1.2.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 1.5.sp),
)

@Composable
fun IDVBTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = IDVBTypography,
        shapes = MaterialTheme.shapes.copy(
            small = RoundedCornerShape(2.dp), medium = RoundedCornerShape(3.dp),
            large = RoundedCornerShape(4.dp), extraLarge = RoundedCornerShape(6.dp),
        ),
        content = content,
    )
}
