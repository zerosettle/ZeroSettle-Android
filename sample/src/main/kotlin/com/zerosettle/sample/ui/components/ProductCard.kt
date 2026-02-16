package com.zerosettle.sample.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zerosettle.sample.ui.theme.Blue
import com.zerosettle.sample.ui.theme.Cyan
import com.zerosettle.sample.ui.theme.Green
import com.zerosettle.sample.ui.theme.Mint
import com.zerosettle.sample.ui.theme.Orange
import com.zerosettle.sample.ui.theme.Purple
import com.zerosettle.sample.ui.theme.Yellow
import com.zerosettle.sdk.model.ZSProduct
import com.zerosettle.sdk.model.ZSProductType

data class ProductVisuals(
    val icon: ImageVector,
    val accentColor: Color,
    val badge: String?,
    val features: List<String>,
)

fun getProductVisuals(product: ZSProduct, index: Int): ProductVisuals {
    return when (product.type) {
        ZSProductType.CONSUMABLE -> ProductVisuals(
            icon = listOf(Icons.Filled.Diamond, Icons.Filled.Diamond, Icons.Filled.Star)[index % 3],
            accentColor = listOf(Cyan, Blue, Purple)[index % 3],
            badge = listOf(null, "Popular", "Best Value")[index % 3],
            features = listOf("Digital currency", "Never expires"),
        )
        ZSProductType.NON_CONSUMABLE -> ProductVisuals(
            icon = Icons.Filled.LockOpen,
            accentColor = Green,
            badge = null,
            features = listOf("Permanent unlock", "One-time purchase"),
        )
        ZSProductType.AUTO_RENEWABLE_SUBSCRIPTION -> {
            val isYearly = product.id.contains("year", ignoreCase = true) ||
                product.displayName.contains("year", ignoreCase = true)
            val isWeekly = product.id.contains("week", ignoreCase = true) ||
                product.displayName.contains("week", ignoreCase = true)
            when {
                isYearly -> ProductVisuals(
                    icon = Icons.Filled.WorkspacePremium,
                    accentColor = Yellow,
                    badge = "Best Value",
                    features = listOf("Unlimited access", "Ad-free", "2 months free"),
                )
                isWeekly -> ProductVisuals(
                    icon = Icons.Filled.Star,
                    accentColor = Mint,
                    badge = null,
                    features = listOf("Unlimited access", "Cancel anytime"),
                )
                else -> ProductVisuals(
                    icon = Icons.Filled.Star,
                    accentColor = Orange,
                    badge = null,
                    features = listOf("Unlimited access", "Ad-free", "Priority support"),
                )
            }
        }
        ZSProductType.NON_RENEWING_SUBSCRIPTION -> ProductVisuals(
            icon = Icons.Filled.ShoppingBag,
            accentColor = Orange,
            badge = null,
            features = listOf("Limited access", "Non-renewing"),
        )
    }
}

@Composable
fun ProductCard(
    product: ZSProduct,
    visuals: ProductVisuals,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.02f else 1f,
        animationSpec = spring(
            dampingRatio = 0.7f,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "scale",
    )

    val borderColor by animateColorAsState(
        targetValue = if (isSelected) visuals.accentColor else Color.Transparent,
        animationSpec = spring(dampingRatio = 0.7f),
        label = "border",
    )

    val elevation by animateFloatAsState(
        targetValue = if (isSelected) 12f else 4f,
        animationSpec = spring(dampingRatio = 0.7f),
        label = "elevation",
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .scale(scale)
            .shadow(
                elevation = elevation.dp,
                shape = RoundedCornerShape(16.dp),
                ambientColor = Color.Black.copy(alpha = 0.1f),
                spotColor = Color.Black.copy(alpha = if (isSelected) 0.15f else 0.1f),
            )
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        ),
        border = BorderStroke(2.dp, borderColor),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // Left content
            Column(modifier = Modifier.weight(1f)) {
                // Header: Icon + Name + Badge
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        imageVector = visuals.icon,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = visuals.accentColor,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = product.displayName,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            visuals.badge?.let { badge ->
                                Text(
                                    text = badge,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = visuals.accentColor,
                                    modifier = Modifier
                                        .background(
                                            visuals.accentColor.copy(alpha = 0.15f),
                                            RoundedCornerShape(6.dp),
                                        )
                                        .padding(horizontal = 8.dp, vertical = 3.dp),
                                )
                            }
                        }

                        Spacer(Modifier.height(4.dp))

                        if (product.webPrice != null) {
                            // Dual pricing: Play Store (strikethrough) + web price
                            if (product.playStoreAvailable) {
                                product.playStorePrice?.let { psPrice ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Text(
                                            text = "\u25B6",
                                            fontSize = 8.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Text(
                                            text = psPrice.formatted,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textDecoration = TextDecoration.LineThrough,
                                        )
                                    }
                                }
                            }

                            Row(
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = product.webPrice?.formatted ?: "—",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                                product.savingsPercent?.let { savings ->
                                    if (savings > 0) {
                                        Text(
                                            text = "Save $savings%",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Green,
                                            modifier = Modifier
                                                .background(
                                                    Green.copy(alpha = 0.12f),
                                                    RoundedCornerShape(4.dp),
                                                )
                                                .padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                            }
                        } else {
                            // Play Store-only pricing
                            product.playStorePrice?.let { psPrice ->
                                Text(
                                    text = psPrice.formatted,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Description
                Text(
                    text = product.productDescription,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(12.dp))

                // Features
                visuals.features.forEach { feature ->
                    Row(
                        modifier = Modifier.padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = visuals.accentColor,
                        )
                        Text(
                            text = feature,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                // Promotion
                product.promotion?.let { promo ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "PROMO: ${promo.displayName} \u2014 ${promo.promotionalPrice.formatted}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Orange,
                        modifier = Modifier
                            .background(
                                Orange.copy(alpha = 0.1f),
                                RoundedCornerShape(4.dp),
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            // Radio button
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .background(visuals.accentColor, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = "Selected",
                            modifier = Modifier.size(14.dp),
                            tint = Color.White,
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .background(Color.Transparent, CircleShape)
                            .padding(2.dp)
                            .background(
                                Color.Gray.copy(alpha = 0.3f),
                                CircleShape,
                            )
                            .padding(2.dp)
                            .background(
                                MaterialTheme.colorScheme.surface,
                                CircleShape,
                            ),
                    )
                }
            }
        }
    }
}
