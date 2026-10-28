package com.dooribeon.condition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dooribeon.global.ApiException;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BudgetMergerTests {
    private final BudgetMerger merger = new BudgetMerger();
    private final BudgetRules rules = new BudgetRules();
    private final Budget monthly = new Budget(RentalType.MONTHLY_RENT, null, 10000000L, 500000L);

    @Test
    void fillsDepositWithoutErasingRentAndAcceptsExplicitZero() {
        Budget current = new Budget(RentalType.MONTHLY_RENT, null, null, 500000L);
        Budget result = merger.merge(current, patch(null, null, 0L, null, BudgetField.depositMaxWon));
        assertThat(result.depositMaxWon()).isZero();
        assertThat(result.monthlyRentMaxWon()).isEqualTo(500000L);
        assertThat(rules.check(result).budgetComplete()).isTrue();
    }

    @Test
    void unrelatedReplyIsNoChangeButExplicitNullClearsOnlySelectedAmount() {
        assertThat(merger.merge(monthly, patch(null, null, null, null))).isEqualTo(monthly);
        Budget cleared = merger.merge(monthly, patch(null, null, null, null, BudgetField.monthlyRentMaxWon));
        assertThat(cleared.depositMaxWon()).isEqualTo(10000000L);
        assertThat(cleared.monthlyRentMaxWon()).isNull();
        assertThat(rules.check(cleared).missingFields()).containsExactly("budget.monthlyRentMaxWon");
    }

    @Test
    void switchingLeaseTypesResetsOldAmountsAndUsesOnlyNewExplicitMoney() {
        Budget jeonse = merger.merge(monthly, patch(RentalType.JEONSE, 200000000L, null, null,
                BudgetField.rentalType, BudgetField.jeonseMaxWon));
        assertThat(jeonse).isEqualTo(new Budget(RentalType.JEONSE, 200000000L, null, null));
        Budget backToMonthly = merger.merge(jeonse, patch(RentalType.MONTHLY_RENT, null, null, 400000L,
                BudgetField.rentalType, BudgetField.monthlyRentMaxWon));
        assertThat(backToMonthly).isEqualTo(new Budget(RentalType.MONTHLY_RENT, null, null, 400000L));
        assertThat(rules.check(backToMonthly).missingFields()).containsExactly("budget.depositMaxWon");
    }

    @Test
    void confirmingMonthlyTypePreservesEarlierDepositButDoesNotConvertItToJeonse() {
        Budget unknown = new Budget(null, null, 10000000L, null);
        assertThat(merger.merge(unknown, patch(RentalType.MONTHLY_RENT, null, null, null, BudgetField.rentalType)))
                .isEqualTo(new Budget(RentalType.MONTHLY_RENT, null, 10000000L, null));
        assertThat(merger.merge(unknown, patch(RentalType.JEONSE, null, null, null, BudgetField.rentalType)))
                .isEqualTo(new Budget(RentalType.JEONSE, null, null, null));
    }

    @Test
    void rejectsUndeclaredChangesAndIncompatibleOrUnsafeAmounts() {
        assertThatThrownBy(() -> merger.merge(monthly, patch(null, null, 20000000L, null)))
                .isInstanceOf(ApiException.class);
        for (BudgetUpdate invalid : new BudgetUpdate[] {
                patch(null, -1L, null, null, BudgetField.jeonseMaxWon),
                patch(null, null, -1L, null, BudgetField.depositMaxWon),
                patch(null, null, null, 9007199254740992L, BudgetField.monthlyRentMaxWon)}) {
            assertThatThrownBy(() -> rules.check(merger.merge(monthly, invalid))).isInstanceOf(ApiException.class);
        }
    }

    static BudgetUpdate patch(RentalType type, Long jeonse, Long deposit, Long rent, BudgetField... fields) {
        return new BudgetUpdate(new Budget(type, jeonse, deposit, rent), Set.of(fields));
    }
}
