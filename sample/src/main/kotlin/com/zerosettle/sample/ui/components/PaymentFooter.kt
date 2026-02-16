package com.zerosettle.sample.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zerosettle.sample.ui.theme.Blue
import com.zerosettle.sample.ui.theme.BlueDark
import com.zerosettle.sample.ui.theme.Green
import com.zerosettle.sample.ui.theme.Indigo
import com.zerosettle.sample.ui.theme.IndigoDark
import com.zerosettle.sample.ui.theme.Purple
import com.zerosettle.sdk.model.ZSProduct

enum class PaymentMethod { PLAY_STORE, WEB_CHECKOUT }

@Composable
fun PaymentFooter(
    selectedProduct: ZSProduct?,
    isProcessing: Boolean,
    processingMethod: PaymentMethod?,
    isWebCheckoutEnabled: Boolean,
    onPlayStorePurchase: () -> Unit,
    onWebCheckoutPurchase: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)),
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            // Selected product info
            selectedProduct?.let { product ->
                Column(modifier = Modifier.padding(bottom = 10.dp)) {
                    Text(
                        text = product.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        // Play Store price
                        if (product.playStoreAvailable) {
                            product.playStorePrice?.let { psPrice ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        text = "\u25B6",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        text = psPrice.formatted,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        // Web price (only shown when web checkout is available for this product)
                        if (isWebCheckoutEnabled && product.webPrice != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    Icons.Filled.CreditCard,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = Green,
                                )
                                Text(
                                    text = product.webPrice?.formatted ?: "—",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Medium,
                                    color = Green,
                                )
                            }
                        }
                    }
                }
            }

            // Payment buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Play Store button
                val playStoreDisabled = selectedProduct == null || isProcessing ||
                    !(selectedProduct?.playStoreAvailable ?: false)

                Button(
                    onClick = onPlayStorePurchase,
                    enabled = !playStoreDisabled,
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .alpha(if (playStoreDisabled) 0.5f else 1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Blue,
                        contentColor = Color.White,
                        disabledContainerColor = Blue.copy(alpha = 0.5f),
                        disabledContentColor = Color.White.copy(alpha = 0.7f),
                    ),
                ) {
                    if (isProcessing && processingMethod == PaymentMethod.PLAY_STORE) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color.White,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            Icons.Filled.ShoppingCart,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Play Store",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                // Web Checkout button (only shown when product has a web price)
                if (isWebCheckoutEnabled && selectedProduct?.webPrice != null) {
                    val webDisabled = selectedProduct == null || isProcessing

                    Button(
                        onClick = onWebCheckoutPurchase,
                        enabled = !webDisabled,
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .alpha(if (webDisabled) 0.5f else 1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Purple,
                            contentColor = Color.White,
                            disabledContainerColor = Purple.copy(alpha = 0.5f),
                            disabledContentColor = Color.White.copy(alpha = 0.7f),
                        ),
                    ) {
                        if (isProcessing && processingMethod == PaymentMethod.WEB_CHECKOUT) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color.White,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                Icons.Filled.CreditCard,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Pay with Card",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}
