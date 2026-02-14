package com.zerosettle.sample.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// StoreFront-inspired color palette
val Indigo = Color(0xFF5C6BC0)
val IndigoDark = Color(0xFF3949AB)
val Purple = Color(0xFF7E57C2)
val PurpleDark = Color(0xFF5E35B1)
val Cyan = Color(0xFF00BCD4)
val Mint = Color(0xFF26A69A)
val Orange = Color(0xFFFF9800)
val OrangeWarm = Color(0xFFFFA726)
val Yellow = Color(0xFFFFCA28)
val Green = Color(0xFF4CAF50)
val GreenLight = Color(0xFF81C784)
val Blue = Color(0xFF2196F3)
val BlueDark = Color(0xFF1565C0)
val SurfaceLight = Color(0xFFF5F5F7)
val SurfaceDarkElevated = Color(0xFF1C1C1E)

private val LightColorScheme = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    secondary = Purple,
    onSecondary = Color.White,
    tertiary = Cyan,
    surface = Color.White,
    surfaceVariant = Color(0xFFF0F0F5),
    background = SurfaceLight,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
    onSurfaceVariant = Color(0xFF79747E),
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF9FA8DA),
    onPrimary = Color(0xFF1A237E),
    secondary = Color(0xFFB39DDB),
    onSecondary = Color(0xFF311B92),
    tertiary = Color(0xFF80DEEA),
    surface = Color(0xFF1C1C1E),
    surfaceVariant = Color(0xFF2C2C2E),
    background = Color(0xFF121212),
    onBackground = Color(0xFFE6E1E5),
    onSurface = Color(0xFFE6E1E5),
    onSurfaceVariant = Color(0xFFCAC4D0),
)

@Composable
fun ZeroSettleSampleTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
