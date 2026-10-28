package com.dooribeon.condition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dooribeon.global.ApiException;
import org.junit.jupiter.api.Test;

class BudgetRulesTests {
    private final BudgetRules rules = new BudgetRules();

    @Test
    void jeonseOnlyNeedsJeonseAmount() {
        var response = rules.check(new Budget(RentalType.JEONSE, 200000000L, null, null));
        assertThat(response.budgetComplete()).isTrue();
        assertThat(response.missingFields()).isEmpty();
    }

    @Test
    void missingJeonseAmountNeedsQuestion() {
        var response = rules.check(new Budget(RentalType.JEONSE, null, null, null));
        assertThat(response.budgetComplete()).isFalse();
        assertThat(response.missingFields()).containsExactly("budget.jeonseMaxWon");
        assertThat(response.questions()).hasSize(1);
    }

    @Test
    void rentWithoutDepositIsNotComplete() {
        var response = rules.check(new Budget(RentalType.MONTHLY_RENT, null, null, 600000L));
        assertThat(response.budget().monthlyRentMaxWon()).isEqualTo(600000L);
        assertThat(response.budgetComplete()).isFalse();
        assertThat(response.missingFields()).containsExactly("budget.depositMaxWon");
        assertThat(response.questions()).containsExactly("보증금은 최대 얼마까지 생각하고 있나요?");
    }

    @Test
    void missingMonthlyAmountAndBothMissingNeedFollowUp() {
        assertThat(rules.check(new Budget(RentalType.MONTHLY_RENT, null, 10000000L, null)).missingFields())
                .containsExactly("budget.monthlyRentMaxWon");
        assertThat(rules.check(new Budget(RentalType.MONTHLY_RENT, null, null, null)).missingFields())
                .containsExactly("budget.depositMaxWon", "budget.monthlyRentMaxWon");
    }

    @Test
    void explicitZeroDepositIsDifferentFromMissing() {
        assertThat(rules.check(new Budget(RentalType.MONTHLY_RENT, null, 0L, 500000L)).budgetComplete()).isTrue();
    }

    @Test
    void unknownTypeAsksTypeFirst() {
        var response = rules.check(new Budget(null, null, 10000000L, null));
        assertThat(response.budgetComplete()).isFalse();
        assertThat(response.missingFields()).containsExactly("budget.rentalType");
    }

    @Test
    void incompatibleTypesAndUnsafeMoneyAreRejected() {
        for (Budget budget : new Budget[] {
                new Budget(RentalType.JEONSE, 200000000L, null, 0L),
                new Budget(RentalType.MONTHLY_RENT, 200000000L, 10000000L, 500000L),
                new Budget(null, 200000000L, null, null),
                new Budget(RentalType.MONTHLY_RENT, null, -1L, 500000L),
                new Budget(RentalType.MONTHLY_RENT, null, 9007199254740992L, 500000L)
        }) {
            assertThatThrownBy(() -> rules.check(budget)).isInstanceOf(ApiException.class);
        }
    }
}
