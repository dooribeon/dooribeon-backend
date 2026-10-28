package com.dooribeon.condition;

import java.util.Set;

/** updatedFields에 없는 값은 유지하고, 포함된 값이 null이면 명시적으로 비운다. */
public record BudgetUpdate(Budget budget, Set<BudgetField> updatedFields, boolean needsAmountUnitConfirmation) {
    public BudgetUpdate(Budget budget, Set<BudgetField> updatedFields) { this(budget, updatedFields, false); }
    public BudgetUpdate {
        updatedFields = Set.copyOf(updatedFields);
    }
}
