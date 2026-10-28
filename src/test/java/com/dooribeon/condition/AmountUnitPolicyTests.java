package com.dooribeon.condition;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AmountUnitPolicyTests {
    private final Budget rent = new Budget(RentalType.MONTHLY_RENT, null, null, 1000000L);

    @Test
    void numbersRequireConfirmationRegardlessOfSizeIncludingZeroAndKoreanScale() {
        for (String text : new String[] {"100", "1000000", "1,000,000", "0", "100만", "천만", "1000000이야."}) {
            assertThat(AmountUnitPolicy.isBareAmountWithoutWon(text)).as(text).isTrue();
        }
    }

    @Test
    void explicitWonUnitsAreAcceptedInCompactSpacedAndRawWonForms() {
        for (String text : new String[] {"100만원", "100만 원", "1000000원", "1,000,000 원", "백만 원", "0원"}) {
            assertThat(AmountUnitPolicy.isBareAmountWithoutWon(text)).as(text).isFalse();
            assertThat(AmountUnitPolicy.unqualifiedMoney(text, rent)).as(text).isFalse();
        }
    }

    @Test
    void wonInsideOrdinaryWordsDoesNotQualifyAnAmount() {
        for (String text : new String[] {"월세는 1000000", "원룸 월세 100", "회사 지원은 1000000"}) {
            var result = AmountUnitPolicy.enforce(text, new BudgetExtraction(rent));
            assertThat(result.needsAmountUnitConfirmation()).as(text).isTrue();
            assertThat(result.budget().monthlyRentMaxWon()).isNull();
        }
    }

    @Test
    void explicitNoDepositKeepsZeroButOtherUnitlessAmountsStillRequireConfirmation() {
        var zeroDeposit = new Budget(RentalType.MONTHLY_RENT, null, 0L, null);
        assertThat(AmountUnitPolicy.unqualifiedMoney("보증금 없이", zeroDeposit)).isFalse();
        assertThat(AmountUnitPolicy.unqualifiedMoney("무보증", zeroDeposit)).isFalse();
        assertThat(AmountUnitPolicy.unqualifiedMoney("보증금 없이 월세는 100", new Budget(RentalType.MONTHLY_RENT, null, 0L, 100L))).isTrue();
    }
}
