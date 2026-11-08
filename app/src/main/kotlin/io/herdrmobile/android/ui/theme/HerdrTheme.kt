package io.herdrmobile.android.ui.theme

import android.app.Activity
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.termux.terminal.TerminalColors
import java.util.Properties

/** atom-one-dark ANSI palette from herdr-app/defaults/themes/atom-one-dark.json. */
object AtomPalette {
    val darkAnsi = listOf("#454c59", "#e06c75", "#98c379", "#e5c07b", "#61afef", "#c678dd", "#56b6c2", "#abb2bf", "#767676", "#e06c75", "#98c379", "#e5c07b", "#61afef", "#c678dd", "#56b6c2", "#abb2bf")
    val lightAnsi = listOf("#383a42", "#e45649", "#50a14f", "#c18401", "#4078f2", "#a626a4", "#0184bc", "#a0a1a7", "#696c77", "#e45649", "#50a14f", "#c18401", "#4078f2", "#a626a4", "#0184bc", "#383a42")
    fun terminalProperties(dark: Boolean) = Properties().apply {
        (if (dark) darkAnsi else lightAnsi).forEachIndexed { index, hex -> setProperty("color$index", hex) }
        setProperty("foreground", if (dark) "#abb2bf" else "#383a42")
        setProperty("background", if (dark) "#21252b" else "#fafafa")
        setProperty("cursor", if (dark) "#abb2bf" else "#383a42")
    }
}

val LocalHerdrDark = staticCompositionLocalOf { true }

@Composable
fun statusColor(status: String): Color {
    val dark = LocalHerdrDark.current
    return when (status) {
        "blocked", "error" -> if (dark) Color(0xFFE06C75) else Color(0xFFB83F39)
        "working" -> if (dark) Color(0xFFE5C07B) else Color(0xFF936700)
        "done" -> if (dark) Color(0xFF61AFEF) else Color(0xFF3062C7)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

@Composable
fun HerdrTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (dark) darkColorScheme(
        primary = Color(0xFF61AFEF), onPrimary = Color(0xFF21252B), secondary = Color(0xFFC678DD),
        background = Color(0xFF21252B), surface = Color(0xFF282C34), surfaceVariant = Color(0xFF323844),
        onBackground = Color(0xFFABB2BF), onSurface = Color(0xFFABB2BF), onSurfaceVariant = Color(0xFF9199A8),
        outline = Color(0xFF454C59), error = Color(0xFFE06C75),
    ) else lightColorScheme(
        primary = Color(0xFF3062C7), secondary = Color(0xFF8D349C),
        background = Color(0xFFFAFAFA), surface = Color(0xFFFFFFFF), surfaceVariant = Color(0xFFE9EBEF),
        onBackground = Color(0xFF383A42), onSurface = Color(0xFF383A42), onSurfaceVariant = Color(0xFF626773),
        outline = Color(0xFFCDD0D7), error = Color(0xFFB83F39),
    )
    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark }
        }
    }
    SideEffect { TerminalColors.COLOR_SCHEME.updateWith(AtomPalette.terminalProperties(dark)) }
    CompositionLocalProvider(LocalHerdrDark provides dark) {
        MaterialTheme(colorScheme = colors, shapes = Shapes(small = RoundedCornerShape(7.dp), medium = RoundedCornerShape(10.dp), large = RoundedCornerShape(14.dp)), content = content)
    }
}
