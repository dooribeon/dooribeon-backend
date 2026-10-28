package com.dooribeon.condition;

import static org.assertj.core.api.Assertions.assertThat;

import com.dooribeon.condition.ConditionCheckResult.Kind;
import com.dooribeon.condition.ConditionsRules.InputContext;
import com.dooribeon.condition.PlaceCondition.Importance;
import com.dooribeon.condition.PlaceCondition.TargetType;
import com.dooribeon.condition.PlaceCondition.Transport;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ConditionsRulesTests {
    private final ConditionsRules rules = new ConditionsRules();
    private final PlaceLocation location = new PlaceLocation("station-1", "여의도역", "서울 영등포구", 37.52, 126.92);

    @Test
    void budgetAloneIsReadyWithoutAnyRequiredPlace() {
        var result = rules.check(new Conditions(1000L, 50L, List.of()));

        assertThat(result.readyForRecommendation()).isTrue();
        assertThat(result.nextQuestion()).isNull();
        assertThat(result.conditions().places()).isEmpty();
        assertThat(result.conditions().maxDeposit()).isEqualTo(1000L);
    }

    @Test
    void missingBudgetsAskInOrderAndExplicitZeroIsKept() {
        var missing = rules.check(new Conditions(null, null, List.of()));
        assertThat(missing.readyForRecommendation()).isFalse();
        assertThat(missing.issues()).extracting(ConditionCheckResult.Issue::path)
                .containsExactly("maxDeposit", "maxRent");
        assertThat(missing.nextQuestion()).contains("보증금");

        assertThat(rules.check(new Conditions(0L, 50L, List.of())).readyForRecommendation()).isTrue();
        assertThat(rules.check(new Conditions(1000L, 0L, List.of())).readyForRecommendation()).isTrue();
    }

    @Test
    void negativeAndUnsafeMoneyAreInvalidRatherThanMissing() {
        for (long bad : new long[] {-1, 9007199254740992L}) {
            var result = rules.check(new Conditions(bad, 50L, List.of()));
            assertThat(result.readyForRecommendation()).isFalse();
            assertThat(result.issues()).anySatisfy(issue -> {
                assertThat(issue.path()).isEqualTo("maxDeposit");
                assertThat(issue.kind()).isEqualTo(Kind.INVALID);
            });
            assertThat(rules.check(new Conditions(1000L, bad, List.of())).readyForRecommendation()).isFalse();
        }
    }

    @Test
    void unconfirmedMoneyBlocksEvenPreviouslyCompleteBudget() {
        var conditions = new Conditions(1000L, 50L, List.of());
        var result = rules.check(conditions, new InputContext(Set.of(), Set.of(), true));

        assertThat(result.readyForRecommendation()).isFalse();
        assertThat(result.issues()).extracting(ConditionCheckResult.Issue::kind)
                .containsExactly(Kind.NEEDS_CONFIRMATION);
        assertThat(result.conditions().maxRent()).isEqualTo(50L);
    }

    @Test
    void requiredPlaceNeedsSelectedLocationTransportAndTimeButFrequencyIsOptional() {
        var incomplete = place("p1", "회사", null, Importance.REQUIRED, null, null);
        var result = rules.check(conditions(incomplete));

        assertThat(result.issues()).extracting(ConditionCheckResult.Issue::path)
                .containsExactly("places[0].location", "places[0].transport", "places[0].maxMinutes");
        assertThat(result.conditions().places().getFirst().transport()).isNull();
        assertThat(result.readyForRecommendation()).isFalse();

        var complete = place("p1", "회사", location, Importance.REQUIRED, Transport.PUBLIC_TRANSIT, 40);
        assertThat(rules.check(conditions(complete)).readyForRecommendation()).isTrue();
    }

    @Test
    void everyRequiredPlaceIsCheckedRatherThanOnlyFirstOne() {
        var company = place("p1", "회사", location, Importance.REQUIRED, Transport.PUBLIC_TRANSIT, 40);
        var school = place("p2", "학교", location, Importance.REQUIRED, null, null);
        var result = rules.check(new Conditions(1000L, 50L, List.of(company, school)));

        assertThat(result.readyForRecommendation()).isFalse();
        assertThat(result.issues()).extracting(ConditionCheckResult.Issue::path)
                .containsExactly("places[1].transport", "places[1].maxMinutes");
    }

    @Test
    void nonRequiredPlacesCanOmitImportanceModeTimeAndFrequencyAndName() {
        var unnamed = place("p1", null, location, null, null, null);
        var result = rules.check(conditions(unnamed));

        assertThat(result.readyForRecommendation()).isTrue();
        assertThat(result.notices()).anySatisfy(notice -> assertThat(notice).contains("여의도역", "대중교통"));
        assertThat(result.conditions().places().getFirst()).isEqualTo(unnamed);
    }

    @Test
    void defaultsOnlyApplyToIdentifiedWorkOrSchoolPlacesAndGiveNotice() {
        for (String label : new String[] {"회사", "직장", "학교", "일하는 곳"}) {
            var input = conditions(place("p1", label, location, null, Transport.PUBLIC_TRANSIT, 40));
            var context = new InputContext(Set.of("p1"), Set.of(), false);
            var result = rules.check(input, context);

            assertThat(result.readyForRecommendation()).isTrue();
            assertThat(result.conditions().places().getFirst().importance()).isEqualTo(Importance.REQUIRED);
            assertThat(result.notices()).hasSize(1).first().asString().contains("서비스 기본값", "바꿀 수");
            assertThat(input.places().getFirst().importance()).isNull();
            assertThat(result.conditions().places().getFirst().id()).isEqualTo("p1");
            assertThat(rules.check(result.conditions(), context).notices()).isEmpty();
        }

        var noRole = conditions(place("p1", "일주일에 가는 운동 장소", location, null, null, null));
        assertThat(rules.check(noRole).conditions().places().getFirst().importance()).isNull();
    }

    @Test
    void explicitImportanceAndUserClearedImportanceAreNotOverwrittenByDefault() {
        var context = new InputContext(Set.of("p1"), Set.of(), false);
        var low = conditions(place("p1", "회사", location, Importance.LOW, null, null));
        assertThat(rules.check(low, context).conditions().places().getFirst().importance()).isEqualTo(Importance.LOW);

        var cleared = conditions(place("p1", "회사", location, null, null, null));
        var userEdited = new InputContext(Set.of("p1"), Set.of("p1"), false);
        var result = rules.check(cleared, userEdited);
        assertThat(result.conditions().places().getFirst().importance()).isNull();
        assertThat(result.readyForRecommendation()).isTrue();
        assertThat(result.notices()).noneMatch(notice -> notice.contains("필수"));
    }

    @Test
    void frequencyKeepsOriginalMonthPeriodAndNeverChangesImportance() {
        var monthly = new VisitFrequency(VisitFrequency.Period.MONTH, 2);
        var place = new PlaceCondition("p1", "친구", TargetType.FIXED_PLACE, "강남역", location,
                Importance.LOW, null, null, monthly);
        var result = rules.check(conditions(place));

        assertThat(result.readyForRecommendation()).isTrue();
        assertThat(result.conditions().places().getFirst().frequency()).isEqualTo(monthly);
        assertThat(result.conditions().places().getFirst().importance()).isEqualTo(Importance.LOW);
    }

    @Test
    void invalidOptionalTimeAndFrequencyStillBlockReadiness() {
        for (int bad : new int[] {0, -1}) {
            var invalidTime = place("p1", "친구", location, Importance.LOW, null, bad);
            assertThat(rules.check(conditions(invalidTime)).issues())
                    .anySatisfy(issue -> assertThat(issue.path()).isEqualTo("places[0].maxMinutes"));
        }
        for (var frequency : List.of(new VisitFrequency(null, 2),
                new VisitFrequency(VisitFrequency.Period.WEEK, null),
                new VisitFrequency(VisitFrequency.Period.WEEK, 0),
                new VisitFrequency(VisitFrequency.Period.MONTH, -1))) {
            var place = new PlaceCondition("p1", "친구", TargetType.FIXED_PLACE, "강남역", location,
                    null, null, null, frequency);
            var result = rules.check(conditions(place));
            assertThat(result.readyForRecommendation()).isFalse();
            assertThat(result.issues()).allSatisfy(issue -> assertThat(issue.kind()).isEqualTo(Kind.INVALID));
        }
    }

    @Test
    void invalidOrMissingCoordinatesNeverBecomeReady() {
        for (var bad : List.of(
                new PlaceLocation(null, "역", null, null, 126.9),
                new PlaceLocation(null, "역", null, 37.5, null),
                new PlaceLocation(null, "역", null, Double.NaN, 126.9),
                new PlaceLocation(null, "역", null, 37.5, Double.POSITIVE_INFINITY),
                new PlaceLocation(null, "역", null, 91.0, 126.9),
                new PlaceLocation(null, "역", null, 37.5, -181.0),
                new PlaceLocation(null, null, null, 37.5, 126.9))) {
            assertThat(rules.check(conditions(place("p1", null, bad, null, null, null)))
                    .readyForRecommendation()).isFalse();
        }
        var addressOnly = new PlaceLocation(null, null, "선택한 도로명주소", 37.5, 126.9);
        assertThat(rules.check(conditions(place("p1", null, addressOnly, null, null, null)))
                .readyForRecommendation()).isTrue();
    }

    @Test
    void nearbyFacilityAndUnknownTypeAreNotTreatedAsSupportedFixedPlaces() {
        var nearby = new PlaceCondition("p1", "학교", TargetType.NEARBY_FACILITY, "주변 학교", null,
                null, null, null, null);
        var result = rules.check(conditions(nearby), new InputContext(Set.of("p1"), Set.of(), false));
        assertThat(result.readyForRecommendation()).isFalse();
        assertThat(result.issues()).extracting(ConditionCheckResult.Issue::kind).containsExactly(Kind.UNSUPPORTED);
        assertThat(result.conditions().places().getFirst().importance()).isNull();
        assertThat(result.issues()).noneMatch(issue -> issue.path().endsWith(".location"));

        var unknown = new PlaceCondition("p1", null, null, null, null, null, null, null, null);
        assertThat(rules.check(conditions(unknown)).issues()).anySatisfy(issue -> {
            assertThat(issue.path()).isEqualTo("places[0].targetType");
            assertThat(issue.kind()).isEqualTo(Kind.MISSING);
        });
    }

    @Test
    void malformedListsAndMissingOrDuplicateIdsAreNotEmptyPlaceSuccess() {
        assertThat(rules.check(null).readyForRecommendation()).isFalse();
        assertThat(rules.check(new Conditions(1000L, 50L, null)).readyForRecommendation()).isFalse();
        assertThat(rules.check(new Conditions(1000L, 50L, Arrays.asList((PlaceCondition) null)))
                .readyForRecommendation()).isFalse();
        var withoutId = place(null, null, location, null, null, null);
        assertThat(rules.check(conditions(withoutId)).readyForRecommendation()).isFalse();
        var first = place("same", "회사", location, Importance.LOW, null, null);
        var second = place("same", "친구", location, Importance.LOW, null, null);
        assertThat(rules.check(new Conditions(1000L, 50L, List.of(first, second))).issues())
                .anySatisfy(issue -> assertThat(issue.path()).isEqualTo("places[1].id"));
    }

    private Conditions conditions(PlaceCondition place) {
        return new Conditions(1000L, 50L, List.of(place));
    }

    private PlaceCondition place(String id, String label, PlaceLocation location, Importance importance,
                                 Transport transport, Integer maxMinutes) {
        return new PlaceCondition(id, label, TargetType.FIXED_PLACE, "역 검색어", location,
                importance, transport, maxMinutes, null);
    }
}
