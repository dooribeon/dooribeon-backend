package com.dooribeon.condition;

import com.dooribeon.global.ApiException;
import org.springframework.stereotype.Component;

@Component
public class BudgetMerger {
    public Budget merge(Budget current, BudgetUpdate update) {
        if (update == null || update.budget() == null) throw ApiException.invalidAiResponse();
        if (update.needsAmountUnitConfirmation()) throw ApiException.invalidAiResponse();
        Budget patch = update.budget();
        var fields = update.updatedFields();
        // 미변경 필드의 값을 AI가 끼워 넣으면 받아들이지 않는다.
        if ((!fields.contains(BudgetField.rentalType) && patch.rentalType() != null)
                || (!fields.contains(BudgetField.jeonseMaxWon) && patch.jeonseMaxWon() != null)
                || (!fields.contains(BudgetField.depositMaxWon) && patch.depositMaxWon() != null)
                || (!fields.contains(BudgetField.monthlyRentMaxWon) && patch.monthlyRentMaxWon() != null)) {
            throw ApiException.invalidAiResponse();
        }
        RentalType type = fields.contains(BudgetField.rentalType) ? patch.rentalType() : current.rentalType();
        boolean switched = fields.contains(BudgetField.rentalType) && type != current.rentalType();
        // 유형 전환 시 예전 계약의 금액을 새 계약으로 옮겨 쓰지 않는다.
        Budget base = switched ? new Budget(type, null, null, null) : current;
        // 보증금부터 말한 뒤 월세라고 확정한 경우, 이미 말한 월세 관련 금액을 보존한다.
        if (current.rentalType() == null && type == RentalType.MONTHLY_RENT) {
            base = new Budget(type, null, current.depositMaxWon(), current.monthlyRentMaxWon());
        }
        Long jeonse = fields.contains(BudgetField.jeonseMaxWon) ? patch.jeonseMaxWon() : base.jeonseMaxWon();
        Long deposit = fields.contains(BudgetField.depositMaxWon) ? patch.depositMaxWon() : base.depositMaxWon();
        Long rent = fields.contains(BudgetField.monthlyRentMaxWon) ? patch.monthlyRentMaxWon() : base.monthlyRentMaxWon();
        return new Budget(type, jeonse, deposit, rent);
    }
}
