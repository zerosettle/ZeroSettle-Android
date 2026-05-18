package com.zerosettle.sdk.billing

import com.android.billingclient.api.BillingClient
import com.google.common.truth.Truth.assertThat
import com.zerosettle.sdk.models.Price
import com.zerosettle.sdk.models.Product
import com.zerosettle.sdk.models.ProductType
import org.junit.Test

/**
 * Regression: PlayBillingManager.queryProductDetails used to default productType to
 * SUBS, so [PlayBillingCoordinator.purchaseViaPlayBilling] queried Play for a
 * subscription regardless of the product's actual type — one-time products
 * (CONSUMABLE / NON_CONSUMABLE) never matched and the SDK surfaced
 * `ProductNotFound`. The fix maps `Product.type` → `BillingClient.ProductType`
 * via [playBillingProductType] before calling `queryProductDetails`.
 */
class PlayBillingProductTypeMappingTest {

    private fun product(type: ProductType): Product = Product(
        id = "ref_id",
        displayName = "Test Product",
        productDescription = "desc",
        type = type,
        webPrice = Price(amountCents = 100, currencyCode = "USD"),
    )

    @Test fun consumable_mapsTo_inapp() {
        assertThat(playBillingProductType(product(ProductType.CONSUMABLE)))
            .isEqualTo(BillingClient.ProductType.INAPP)
    }

    @Test fun nonConsumable_mapsTo_inapp() {
        assertThat(playBillingProductType(product(ProductType.NON_CONSUMABLE)))
            .isEqualTo(BillingClient.ProductType.INAPP)
    }

    @Test fun nonRenewingSubscription_mapsTo_inapp() {
        // Non-renewing subscriptions are modeled as INAPP on Play (no auto-renew).
        assertThat(playBillingProductType(product(ProductType.NON_RENEWING_SUBSCRIPTION)))
            .isEqualTo(BillingClient.ProductType.INAPP)
    }

    @Test fun autoRenewableSubscription_mapsTo_subs() {
        assertThat(playBillingProductType(product(ProductType.AUTO_RENEWABLE_SUBSCRIPTION)))
            .isEqualTo(BillingClient.ProductType.SUBS)
    }

    @Test fun billingClientProductType_literalValues() {
        // Sanity: confirm the literal string values match the Play BillingClient
        // constants so future renames of the constants don't silently break the
        // contract with the SDK.
        assertThat(BillingClient.ProductType.INAPP).isEqualTo("inapp")
        assertThat(BillingClient.ProductType.SUBS).isEqualTo("subs")
    }
}
