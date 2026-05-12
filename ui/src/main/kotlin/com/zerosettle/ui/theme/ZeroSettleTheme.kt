package com.zerosettle.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

/**
 * Per-component styling for the ZeroSettle Compose components. By default everything
 * derives from `MaterialTheme.colorScheme` / `MaterialTheme.typography`; the host app
 * can override any field per [ZeroSettleTheme] call site or per component parameter.
 */
public data class ZeroSettleStyles(
    val offerAccentColor: Color,
    val offerSurfaceColor: Color,
    val offerTitleStyle: TextStyle?,
    val offerBodyStyle: TextStyle?,
    val offerCtaStyle: TextStyle?,
)

/** ZeroSettle UI defaults that are usable outside of composition. */
public object ZeroSettleDefaults {
    /** ZeroSettle brand green (`#6CA358`) — the default offer-tip accent. */
    public fun offerAccentColor(): Color = Color(0xFF6CA358)
}

internal val LocalZeroSettleStyles = staticCompositionLocalOf<ZeroSettleStyles> {
    error("LocalZeroSettleStyles not provided — wrap UI in ZeroSettleTheme { }")
}

/**
 * Wrap ZeroSettle UI components in this. Inherits the surrounding `MaterialTheme` and
 * layers ZeroSettle-specific styling on top.
 *
 * ```
 * ZeroSettleTheme {
 *     ZeroSettleOfferTip(offerManager = mgr)
 * }
 * ```
 *
 * **Naming-collision resolution:** a plain top-level `@Composable fun ZeroSettleTheme`
 * (not an `object` with a `@Composable operator invoke` — that pattern is fragile
 * across Compose compiler versions). The brand-green constant lives on
 * [ZeroSettleDefaults] so it's reachable without composition.
 */
@Composable
public fun ZeroSettleTheme(
    styles: ZeroSettleStyles? = null,
    content: @Composable () -> Unit,
) {
    val resolved = styles ?: ZeroSettleStyles(
        offerAccentColor = ZeroSettleDefaults.offerAccentColor(),
        offerSurfaceColor = MaterialTheme.colorScheme.surfaceVariant,
        offerTitleStyle = MaterialTheme.typography.titleMedium,
        offerBodyStyle = MaterialTheme.typography.bodyMedium,
        offerCtaStyle = MaterialTheme.typography.labelLarge,
    )
    CompositionLocalProvider(LocalZeroSettleStyles provides resolved, content = content)
}
