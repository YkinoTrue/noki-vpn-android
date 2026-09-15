package com.noki.vpn.data

object PlanCatalogPolicy {
    data class PriceLabel(
        val primary: String,
        val discount: String?,
    )

    fun visiblePlans(
        plans: List<PlanSummary>,
        cycle: BillingCycle,
    ): List<PlanSummary> {
        val visible = plans.filter { plan ->
            plan.tier.equals("free", ignoreCase = true) ||
                when (cycle) {
                    BillingCycle.MONTHLY -> plan.yearlyMonthlyPriceRub == null
                    BillingCycle.YEARLY -> plan.yearlyMonthlyPriceRub != null
                }
        }
        return visible.ifEmpty { plans }
    }

    fun monthlyPriceRub(
        plan: PlanSummary,
        cycle: BillingCycle,
    ): Int {
        return if (cycle == BillingCycle.YEARLY) {
            plan.yearlyMonthlyPriceRub ?: plan.monthlyPriceRub
        } else {
            plan.monthlyPriceRub
        }.coerceAtLeast(0)
    }

    fun yearTotalRub(
        plan: PlanSummary,
        cycle: BillingCycle,
    ): Int? {
        if (cycle != BillingCycle.YEARLY) return null
        return if (plan.yearlyMonthlyPriceRub != null) {
            plan.monthlyPriceRub.coerceAtLeast(0)
        } else {
            (monthlyPriceRub(plan, cycle) * 12).coerceAtLeast(0)
        }
    }

    fun priceLabel(
        plan: PlanSummary,
        cycle: BillingCycle,
        language: AppLanguage,
    ): PriceLabel {
        val yearlyTotal = yearTotalRub(plan, cycle)
        if (cycle == BillingCycle.YEARLY && yearlyTotal != null) {
            return PriceLabel(
                primary = "${priceAmount(yearlyTotal, language)} / ${yearLabel(language)}",
                discount = if (yearlyTotal > 0 && plan.yearlyMonthlyPriceRub != null) "-15%" else null,
            )
        }
        return PriceLabel(
            primary = "${priceAmount(monthlyPriceRub(plan, cycle), language)} / ${monthLabel(language)}",
            discount = null,
        )
    }

    fun checkoutTotalLabel(
        plan: PlanSummary,
        cycle: BillingCycle,
        language: AppLanguage,
    ): String = priceAmount(
        yearTotalRub(plan, cycle) ?: monthlyPriceRub(plan, cycle),
        language,
    )

    private fun priceAmount(
        value: Int,
        language: AppLanguage,
    ): String {
        return if (language == AppLanguage.RU) {
            "${value} ₽"
        } else {
            "${value} rub"
        }
    }

    private fun monthLabel(language: AppLanguage): String =
        if (language == AppLanguage.RU) "месяц" else "month"

    private fun yearLabel(language: AppLanguage): String =
        if (language == AppLanguage.RU) "год" else "year"
}
