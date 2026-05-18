package io.zerosettle.justone.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape

private val Sage = Color(0xFF6CA358)
private val SageBright = Color(0xFF7FBF63)
private val LightBg = Color(0xFFFAFAF7)
private val DarkBg = Color(0xFF0F0F10)

private val LightColors = lightColorScheme(
    primary = Sage,
    secondary = SageBright,
    background = LightBg,
    surface = Color(0xFFFFFFFF),
)

private val DarkColors = darkColorScheme(
    primary = SageBright,
    secondary = Sage,
    background = DarkBg,
    surface = Color(0xFF1A1A1C),
)

/** Card corners bumped to 16dp (consumer-soft); buttons stay Material default. */
private val JustOneShapes = Shapes(
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
)

@Composable
fun JustOneTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = JustOneShapes,
        content = content,
    )
}
