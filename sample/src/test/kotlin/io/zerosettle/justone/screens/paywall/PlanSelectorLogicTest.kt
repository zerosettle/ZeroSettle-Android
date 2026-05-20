package io.zerosettle.justone.screens.paywall

import com.google.common.truth.Truth.assertThat
import com.zerosettle.sdk.models.BillingInterval
import com.zerosettle.sdk.models.Product
import com.zerosettle.sdk.models.ProductType
import org.junit.Test

class PlanSelectorLogicTest {

    // ── Fixture factory ──────────────────────────────────────────────────────

    private fun product(
        id: String,
        type: ProductType,
        interval: BillingInterval?,
    ) = Product(
        id = id,
        displayName = "Plan $id",
        productDescription = "desc",
        type = type,
        billingInterval = interval,
    )

    // ── subscriptionPlans ────────────────────────────────────────────────────

    @Test fun `subscriptionPlans keeps only AUTO_RENEWABLE_SUBSCRIPTION`() {
        val products = listOf(
            product("sub-weekly", ProductType.AUTO_RENEWABLE_SUBSCRIPTION, BillingInterval.WEEK),
            product("consumable", ProductType.CONSUMABLE, null),
            product("non-con", ProductType.NON_CONSUMABLE, null),
            product("non-ren", ProductType.NON_RENEWING_SUBSCRIPTION, null),
        )
        val plans = subscriptionPlans(products)
        assertThat(plans).hasSize(1)
        assertThat(plans.first().id).isEqualTo("sub-weekly")
    }

    @Test fun `subscriptionPlans sorts WEEK then MONTH then YEAR`() {
        val products = listOf(
            product("yearly", ProductType.AUTO_RENEWABLE_SUBSCRIPTION, BillingInterval.YEAR),
            product("weekly", ProductType.AUTO_RENEWABLE_SUBSCRIPTION, BillingInterval.WEEK),
            product("monthly", ProductType.AUTO_RENEWABLE_SUBSCRIPTION, BillingInterval.MONTH),
        )
        val plans = subscriptionPlans(products)
        assertThat(plans.map { it.id }).isEqualTo(listOf("weekly", "monthly", "yearly"))
    }

    @Test fun `subscriptionPlans puts null billingInterval last`() {
        val products = listOf(
            product("no-interval", ProductType.AUTO_RENEWABLE_SUBSCRIPTION, null),
            product("monthly", ProductType.AUTO_RENEWABLE_SUBSCRIPTION, BillingInterval.MONTH),
        )
        val plans = subscriptionPlans(products)
        assertThat(plans.map { it.id }).isEqualTo(listOf("monthly", "no-interval"))
    }

    // ── planIntervalLabel ────────────────────────────────────────────────────

    @Test fun `planIntervalLabel returns Weekly for WEEK`() {
        val p = product("w", ProductType.AUTO_RENEWABLE_SUBSCRIPTION, BillingInterval.WEEK)
        assertThat(planIntervalLabel(p)).isEqualTo("Weekly")
    }

    @Test fun `planIntervalLabel returns Monthly for MONTH`() {
        val p = product("m", ProductType.AUTO_RENEWABLE_SUBSCRIPTION, BillingInterval.MONTH)
        assertThat(planIntervalLabel(p)).isEqualTo("Monthly")
    }

    @Test fun `planIntervalLabel returns Yearly for YEAR`() {
        val p = product("y", ProductType.AUTO_RENEWABLE_SUBSCRIPTION, BillingInterval.YEAR)
        assertThat(planIntervalLabel(p)).isEqualTo("Yearly")
    }

    @Test fun `planIntervalLabel falls back to displayName when billingInterval is null`() {
        val p = product("custom-plan", ProductType.AUTO_RENEWABLE_SUBSCRIPTION, null)
        assertThat(planIntervalLabel(p)).isEqualTo(p.displayName)
    }

    // ── defaultPlanId ────────────────────────────────────────────────────────

    @Test fun `defaultPlanId returns null for empty list`() {
        assertThat(defaultPlanId(emptyList())).isNull()
    }

    @Test fun `defaultPlanId picks MONTH plan when present`() {
        val plans = listOf(
            product("weekly", ProductType.AUTO_RENEWABLE_SUBSCRIPTION, BillingInterval.WEEK),
            product("monthly", ProductType.AUTO_RENEWABLE_SUBSCRIPTION, BillingInterval.MONTH),
            product("yearly", ProductType.AUTO_RENEWABLE_SUBSCRIPTION, BillingInterval.YEAR),
        )
        assertThat(defaultPlanId(plans)).isEqualTo("monthly")
    }

    @Test fun `defaultPlanId falls back to first plan when no MONTH plan`() {
        val plans = listOf(
            product("weekly", ProductType.AUTO_RENEWABLE_SUBSCRIPTION, BillingInterval.WEEK),
            product("yearly", ProductType.AUTO_RENEWABLE_SUBSCRIPTION, BillingInterval.YEAR),
        )
        assertThat(defaultPlanId(plans)).isEqualTo("weekly")
    }

    @Test fun `defaultPlanId returns first when billingIntervals are all null`() {
        val plans = listOf(
            product("plan-a", ProductType.AUTO_RENEWABLE_SUBSCRIPTION, null),
            product("plan-b", ProductType.AUTO_RENEWABLE_SUBSCRIPTION, null),
        )
        assertThat(defaultPlanId(plans)).isEqualTo("plan-a")
    }
}
