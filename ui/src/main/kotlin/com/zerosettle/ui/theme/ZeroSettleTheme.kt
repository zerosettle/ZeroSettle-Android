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

/**
 * Host-provided styles, or `null` when no [ZeroSettleTheme] wraps the call
 * site. ZeroSettle UI components MUST resolve styling through
 * [resolvedZeroSettleStyles] rather than reading this directly — that keeps
 * them drop-in (a missing [ZeroSettleTheme] degrades to Material-derived
 * defaults instead of crashing).
 */
internal val LocalZeroSettleStyles = staticCompositionLocalOf<ZeroSettleStyles?> { null }

/** Material-derived [ZeroSettleStyles] — the defaults when nothing overrides them. */
@Composable
internal fun defaultZeroSettleStyles(): ZeroSettleStyles = ZeroSettleStyles(
    offerAccentColor = ZeroSettleDefaults.offerAccentColor(),
    offerSurfaceColor = MaterialTheme.colorScheme.surfaceVariant,
    offerTitleStyle = MaterialTheme.typography.titleMedium,
    offerBodyStyle = MaterialTheme.typography.bodyMedium,
    offerCtaStyle = MaterialTheme.typography.labelLarge,
)

/**
 * The active [ZeroSettleStyles] for a ZeroSettle UI component — the
 * host-provided styles from an enclosing [ZeroSettleTheme], or
 * [defaultZeroSettleStyles] when there is no such wrapper. Components call
 * this so they render correctly whether or not the host opted into theming.
 */
@Composable
internal fun resolvedZeroSettleStyles(): ZeroSettleStyles =
    LocalZeroSettleStyles.current ?: defaultZeroSettleStyles()

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
    val resolved = styles ?: defaultZeroSettleStyles()
    CompositionLocalProvider(LocalZeroSettleStyles provides resolved, content = content)
}
