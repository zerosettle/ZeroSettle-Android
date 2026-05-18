package io.zerosettle.justone.screens.paywall

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zerosettle.sdk.models.Price
import com.zerosettle.sdk.models.Product
import java.text.NumberFormat
import java.util.Currency

fun Price.formatted(): String =
    NumberFormat.getCurrencyInstance().also {
        it.currency = Currency.getInstance(currencyCode)
    }.format(amountCents / 100.0)

@Composable
fun CheckoutSheetHeader(
    product: Product,
    modifier: Modifier = Modifier,
) {
    val displayPrice = product.webPrice ?: product.playStorePrice ?: product.appStorePrice

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = product.displayName,
            style = MaterialTheme.typography.headlineSmall,
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = product.productDescription,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (displayPrice != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = displayPrice.formatted(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
