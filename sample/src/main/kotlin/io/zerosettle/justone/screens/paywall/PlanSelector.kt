package io.zerosettle.justone.screens.paywall

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.zerosettle.sdk.models.BillingInterval
import com.zerosettle.sdk.models.Product
import com.zerosettle.sdk.models.ProductType

// ── Pure helper functions ────────────────────────────────────────────────────

/**
 * Filters [products] to only [ProductType.AUTO_RENEWABLE_SUBSCRIPTION] entries
 * and sorts them by billing interval: WEEK → MONTH → YEAR, with null intervals last.
 */
fun subscriptionPlans(products: List<Product>): List<Product> =
    products
        .filter { it.type == ProductType.AUTO_RENEWABLE_SUBSCRIPTION }
        .sortedBy { it.billingInterval?.ordinal ?: Int.MAX_VALUE }

/**
 * Returns a human-readable interval label for [product].
 *
 * Maps [BillingInterval.WEEK] → "Weekly", [BillingInterval.MONTH] → "Monthly",
 * [BillingInterval.YEAR] → "Yearly". Falls back to [Product.displayName] when
 * [Product.billingInterval] is null (the backend does not yet emit this field,
 * so today every plan falls back to its display name).
 */
fun planIntervalLabel(product: Product): String = when (product.billingInterval) {
    BillingInterval.WEEK -> "Weekly"
    BillingInterval.MONTH -> "Monthly"
    BillingInterval.YEAR -> "Yearly"
    null -> product.displayName
}

/**
 * Returns the [Product.id] of the preferred default plan.
 *
 * Prefers the [BillingInterval.MONTH] plan; falls back to the first plan in
 * [plans]; returns `null` when [plans] is empty.
 */
fun defaultPlanId(plans: List<Product>): String? =
    plans.firstOrNull { it.billingInterval == BillingInterval.MONTH }?.id
        ?: plans.firstOrNull()?.id

// ── PlanSelector composable ──────────────────────────────────────────────────

/**
 * Renders a selectable list of subscription plans. Each row shows the interval
 * label (e.g. "Monthly") on the left and the formatted price on the right.
 * The selected row is highlighted with a primary-color border and a checkmark
 * icon; unselected rows use a muted outline.
 *
 * @param plans The subscription plans to display, already filtered and sorted
 *   (use [subscriptionPlans] to prepare this list).
 * @param selectedId The [Product.id] of the currently selected plan.
 * @param onSelect Called with the [Product.id] when the user taps a row.
 * @param modifier Optional [Modifier] applied to the outer [Column].
 */
@Composable
fun PlanSelector(
    plans: List<Product>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)

    Column(modifier = modifier) {
        plans.forEach { plan ->
            val isSelected = plan.id == selectedId
            val borderColor = if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            }

            val displayPrice = plan.webPrice ?: plan.playStorePrice ?: plan.appStorePrice
            val priceText = displayPrice?.formatted() ?: "—"

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clip(shape)
                    .border(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = borderColor,
                        shape = shape,
                    )
                    .clickable { onSelect(plan.id) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Leading icon
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                } else {
                    // Unselected: simple circle outline using border
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .border(
                                width = 1.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(50),
                            ),
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Text(
                    text = planIntervalLabel(plan),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )

                Text(
                    text = priceText,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}
