package com.oneproxyclick.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object OpcColors {
    val Background = Color(0xFF0E1116)
    val Surface = Color(0xFF161B22)
    val SurfaceVariant = Color(0xFF21262D)
    val Border = Color(0xFF30363D)
    val Text = Color(0xFFE6EDF3)
    val TextDim = Color(0xFF8B949E)
    val Orange = Color(0xFFFF8C1A)
    val Green = Color(0xFF3FB950)
    val Blue = Color(0xFF58A6FF)
    val Yellow = Color(0xFFD29922)
    val Red = Color(0xFFF85149)
    val Purple = Color(0xFFBC8CFF)
}

private val scheme = darkColorScheme(
    primary = OpcColors.Orange,
    onPrimary = Color(0xFF1A0E00),
    primaryContainer = Color(0xFF3D2200),
    onPrimaryContainer = OpcColors.Orange,
    secondary = OpcColors.Green,
    onSecondary = Color(0xFF00210A),
    tertiary = OpcColors.Blue,
    background = OpcColors.Background,
    onBackground = OpcColors.Text,
    surface = OpcColors.Surface,
    onSurface = OpcColors.Text,
    surfaceVariant = OpcColors.SurfaceVariant,
    onSurfaceVariant = OpcColors.TextDim,
    surfaceContainer = OpcColors.Surface,
    surfaceContainerHigh = OpcColors.SurfaceVariant,
    outline = OpcColors.Border,
    outlineVariant = OpcColors.Border,
    error = OpcColors.Red,
)

private val typography = Typography().let { base ->
    base.copy(
        titleLarge = base.titleLarge.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 20.sp),
        titleMedium = base.titleMedium.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold),
        labelSmall = base.labelSmall.copy(fontFamily = FontFamily.Monospace),
    )
}

val MonoSmall = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)

/**
 * High-contrast Switch colours for the dark theme. The M3 defaults make an OFF switch almost
 * invisible on #161B22 surfaces; these keep the thumb and track clearly visible in both states.
 * Use for every Switch in the app.
 */
@Composable
fun opcSwitchColors(): SwitchColors = SwitchDefaults.colors(
    checkedThumbColor = Color(0xFFFFFFFF),
    checkedTrackColor = OpcColors.Orange,
    checkedBorderColor = OpcColors.Orange,
    checkedIconColor = OpcColors.Orange,
    uncheckedThumbColor = Color(0xFFE2E8F0),
    uncheckedTrackColor = Color(0xFF3A3F4B),
    uncheckedBorderColor = Color(0xFF8A93A2),
    uncheckedIconColor = Color(0xFF3A3F4B),
    disabledCheckedThumbColor = Color(0xFFB0B7C3),
    disabledCheckedTrackColor = Color(0xFF7A4A1A),
    disabledUncheckedThumbColor = Color(0xFF8A93A2),
    disabledUncheckedTrackColor = Color(0xFF2A2E36),
    disabledUncheckedBorderColor = Color(0xFF5A6270),
)

@Composable
fun OneProxyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}

fun methodColor(method: String): Color = when (method.uppercase()) {
    "GET" -> OpcColors.Blue
    "POST" -> OpcColors.Green
    "PUT", "PATCH" -> OpcColors.Yellow
    "DELETE" -> OpcColors.Red
    "CONNECT" -> OpcColors.Purple
    else -> OpcColors.TextDim
}

fun statusColorOf(code: Int): Color = when (code) {
    in 100..199 -> OpcColors.Purple
    in 200..299 -> OpcColors.Green
    in 300..399 -> OpcColors.Blue
    in 400..499 -> OpcColors.Yellow
    in 500..599 -> OpcColors.Red
    else -> OpcColors.Red
}
