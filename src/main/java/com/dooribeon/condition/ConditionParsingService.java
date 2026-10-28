package com.dooribeon.condition;

import org.springframework.stereotype.Service;

@Service
public class ConditionParsingService {
    private final BudgetExtractor extractor;
    private final BudgetRules rules;

    public ConditionParsingService(BudgetExtractor extractor, BudgetRules rules) {
        this.extractor = extractor;
        this.rules = rules;
    }

    public ConditionParseResponse parse(String text) {
        BudgetExtraction extraction = AmountUnitPolicy.isBareAmountWithoutWon(text)
                ? new BudgetExtraction(AmountUnitPolicy.emptyBudget(), true) : extractor.extract(text);
        extraction = AmountUnitPolicy.enforce(text, extraction);
        ConditionParseResponse result = rules.check(extraction.budget());
        if (extraction.needsAmountUnitConfirmation()) {
            AmountUnitPolicy.validateUnconfirmed(extraction.budget());
            return AmountUnitPolicy.ask(result);
        }
        return result;
    }
}
