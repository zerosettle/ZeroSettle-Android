package com.zerosettle.sdk.billing

import com.google.common.truth.Truth.assertThat
import com.zerosettle.sdk.models.ProductType
import org.junit.Test

/**
 * Pins the consume-vs-acknowledge routing decision. The contract:
 *
 *  - CONSUMABLE → consume (Play requires consumeAsync to allow repurchase;
 *    leaving a consumable acknowledged-but-not-consumed traps the user in
 *    "you already own this item" → BillingResponseCode.ITEM_ALREADY_OWNED).
 *  - NON_CONSUMABLE / AUTO_RENEWABLE_SUBSCRIPTION / NON_RENEWING_SUBSCRIPTION
 *    → acknowledge (the standard 3-day window mark-as-processed call).
 *  - Unknown product (lookup returns null) → acknowledge. Safe default
 *    because acknowledge is a no-op against an already-acknowledged
 *    purchase, while consume would burn a non-consumable.
 *
 * Pure-function test against [isConsumable]; integration with
 * [PurchaseSyncProcessor] / [PlayBillingManager] is exercised by manual
 * device verification.
 */
class PurchaseFinalizeRoutingTest {

    @Test fun consumable_routesToConsume() {
        val lookup: (String) -> ProductType? = { ProductType.CONSUMABLE }
        assertThat(isConsumable("pid_consumable", lookup)).isTrue()
    }

    @Test fun nonConsumable_routesToAcknowledge() {
        val lookup: (String) -> ProductType? = { ProductType.NON_CONSUMABLE }
        assertThat(isConsumable("pid_non_consumable", lookup)).isFalse()
    }

    @Test fun autoRenewableSubscription_routesToAcknowledge() {
        val lookup: (String) -> ProductType? = { ProductType.AUTO_RENEWABLE_SUBSCRIPTION }
        assertThat(isConsumable("pid_sub_auto", lookup)).isFalse()
    }

    @Test fun nonRenewingSubscription_routesToAcknowledge() {
        val lookup: (String) -> ProductType? = { ProductType.NON_RENEWING_SUBSCRIPTION }
        assertThat(isConsumable("pid_sub_non_renew", lookup)).isFalse()
    }

    @Test fun unknownProduct_routesToAcknowledge() {
        // Lookup miss is the safe-default branch: acknowledge is a no-op on
        // already-acknowledged purchases, whereas consuming a non-consumable
        // (if the catalog hadn't loaded yet, say) would be destructive.
        val lookup: (String) -> ProductType? = { null }
        assertThat(isConsumable("pid_unknown", lookup)).isFalse()
    }
}
