package com.dooribeon.condition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ConditionsTests {
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void budgetOnlyJsonUsesIntegerManWonAndAnEmptyPlaceList() {
        var conditions = new Conditions(1000L, 50L, List.of());
        var json = mapper.readTree(mapper.writeValueAsString(conditions));

        assertThat(json.size()).isEqualTo(3);
        assertThat(json.get("maxDeposit").longValue()).isEqualTo(1000);
        assertThat(json.get("maxRent").longValue()).isEqualTo(50);
        assertThat(json.get("places").isArray()).isTrue();
        assertThat(json.get("places").isEmpty()).isTrue();
        assertThat(mapper.readValue(json.toString(), Conditions.class)).isEqualTo(conditions);
    }

    @Test
    void draftJsonRetainsNullsAndOriginalFrequencyWithoutOldBudgetFields() {
        var place = new PlaceCondition("p1", null, PlaceCondition.TargetType.FIXED_PLACE, "강남역", null,
                PlaceCondition.Importance.LOW, null, null,
                new VisitFrequency(VisitFrequency.Period.MONTH, 2));
        var input = new Conditions(null, 50L, List.of(place));
        var json = mapper.readTree(mapper.writeValueAsString(input));
        var p = json.get("places").get(0);

        assertThat(json.get("maxDeposit").isNull()).isTrue();
        assertThat(json.has("budget")).isFalse();
        assertThat(json.has("rentalType")).isFalse();
        assertThat(p.get("label").isNull()).isTrue();
        assertThat(p.get("location").isNull()).isTrue();
        assertThat(p.get("frequency").get("period").asText()).isEqualTo("MONTH");
        assertThat(p.has("frequencyText")).isFalse();
        assertThat(p.has("visitsPerWeek")).isFalse();
        assertThat(p.has("preferences")).isFalse();
        assertThat(mapper.readValue(json.toString(), Conditions.class)).isEqualTo(input);
    }

    @Test
    void changingOriginalListCannotChangeStoredConditions() {
        var source = new ArrayList<PlaceCondition>();
        var conditions = new Conditions(1000L, 50L, source);
        source.add(new PlaceCondition("p1", null, null, null, null, null, null, null, null));

        assertThat(conditions.places()).isEmpty();
        assertThatThrownBy(() -> conditions.places().add(source.getFirst()))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
