package com.zerosettle.sdk.billing

import com.google.common.truth.Truth.assertThat
import com.zerosettle.sdk.models.Product
import com.zerosettle.sdk.models.ProductType
import org.junit.Test

/**
 * Pins the finalize-time product-type lookup that drives consume-vs-acknowledge
 * routing ([isConsumable]).
 *
 * The key passed at finalize time is a **Play Console SKU** (`Purchase.products[0]`).
 * The forward purchase path resolves that SKU as `product.playProductId ?: product.id`
 * ([PlayBillingCoordinator.purchaseViaPlayBilling]). So the reverse lookup MUST prefer
 * a [Product.playProductId] match — matching only [Product.id] returns `null` whenever
 * `playProductId != id` (the normal case), routing a consumable to `acknowledge`
 * instead of `consume` and trapping the user in `ITEM_ALREADY_OWNED` on re-purchase.
 *
 * The `bugCase_*` test below also asserts the OLD `id`-only logic fails the bug case,
 * demonstrating the regression this fix closes.
 */
class ProductTypeForPlaySkuTest {

    private fun product(
        id: String,
        playProductId: String?,
        type: ProductType,
    ): Product = Product(
        id = id,
        displayName = id,
        productDescription = "",
        type = type,
        playProductId = playProductId,
    )

    @Test fun playProductIdMatch_returnsType_whenPlaySkuDiffersFromId() {
        // Bug case: SKU passed at finalize == the Play SKU, which is NOT the
        // ZeroSettle Product.id. The lookup must match on playProductId.
        val products = listOf(
            product(id = "coins_100", playProductId = "com.app.coins100", type = ProductType.CONSUMABLE),
        )
        assertThat(productTypeForPlaySku(products, "com.app.coins100"))
            .isEqualTo(ProductType.CONSUMABLE)
    }

    @Test fun bugCase_idOnlyLookup_failsToResolvePlaySku() {
        // Demonstrates the pre-fix behavior: the old ZeroSettle.kt lambda
        // (`firstOrNull { it.id == pid }?.type`) returns null for the Play SKU,
        // which is exactly why isConsumable returned false and acknowledge ran.
        val products = listOf(
            product(id = "coins_100", playProductId = "com.app.coins100", type = ProductType.CONSUMABLE),
        )
        val oldIdOnlyLookup: (String) -> ProductType? = { pid ->
            products.firstOrNull { it.id == pid }?.type
        }
        assertThat(oldIdOnlyLookup("com.app.coins100")).isNull()
        // The new lookup fixes it:
        assertThat(productTypeForPlaySku(products, "com.app.coins100"))
            .isEqualTo(ProductType.CONSUMABLE)
    }

    @Test fun idFallback_returnsType_whenPlayProductIdNull() {
        // Fallback: products whose SKU == id (no separate playProductId set).
        val products = listOf(
            product(id = "com.app.pro", playProductId = null, type = ProductType.NON_CONSUMABLE),
        )
        assertThat(productTypeForPlaySku(products, "com.app.pro"))
            .isEqualTo(ProductType.NON_CONSUMABLE)
    }

    @Test fun playProductIdEqualsId_resolvesConsistently() {
        // Sanity: when playProductId == id, both branches point at the same
        // product — the result is unambiguous.
        val products = listOf(
            product(id = "com.app.sub", playProductId = "com.app.sub", type = ProductType.AUTO_RENEWABLE_SUBSCRIPTION),
        )
        assertThat(productTypeForPlaySku(products, "com.app.sub"))
            .isEqualTo(ProductType.AUTO_RENEWABLE_SUBSCRIPTION)
    }

    @Test fun unknownSku_returnsNull() {
        val products = listOf(
            product(id = "coins_100", playProductId = "com.app.coins100", type = ProductType.CONSUMABLE),
        )
        assertThat(productTypeForPlaySku(products, "com.app.nonexistent")).isNull()
    }

    @Test fun emptyCatalog_returnsNull() {
        assertThat(productTypeForPlaySku(emptyList(), "com.app.coins100")).isNull()
    }
}
