package app.tarana.pad.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** 与 radif 一致的深色调：近黑的紫灰底 + 紫色强调色 */
object TaranaColors {
    val Bg = Color(0xFF0E0D12)
    val Surface = Color(0xFF16151C)
    val SurfaceHi = Color(0xFF1F1D27)
    val Line = Color(0xFF2A2933)
    val Accent = Color(0xFF8E7CF0)
    val AccentDeep = Color(0xFF3A2D7A)
    val Text = Color(0xFFF2F1F6)
    val TextDim = Color(0xFFA09EAD)
    val TextFaint = Color(0xFF6C6A78)
}

private val scheme = darkColorScheme(
    primary = TaranaColors.Accent,
    onPrimary = Color.White,
    primaryContainer = TaranaColors.AccentDeep,
    onPrimaryContainer = Color.White,
    secondary = TaranaColors.Accent,
    background = TaranaColors.Bg,
    onBackground = TaranaColors.Text,
    surface = TaranaColors.Surface,
    onSurface = TaranaColors.Text,
    surfaceVariant = TaranaColors.SurfaceHi,
    onSurfaceVariant = TaranaColors.TextDim,
    surfaceContainer = TaranaColors.Surface,
    surfaceContainerHigh = TaranaColors.SurfaceHi,
    surfaceContainerHighest = TaranaColors.SurfaceHi,
    outline = TaranaColors.Line,
    outlineVariant = TaranaColors.Line,
)

private val base = Typography()
private val typography = base.copy(
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold, fontSize = 34.sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
)

@Composable
fun TaranaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography) {
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides TaranaColors.Text, content = content)
    }
}

val DimText = TextStyle(color = TaranaColors.TextDim)
