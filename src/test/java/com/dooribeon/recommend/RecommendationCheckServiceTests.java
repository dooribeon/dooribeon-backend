package com.dooribeon.recommend;

import com.dooribeon.condition.*;
import com.dooribeon.condition.PlaceCondition.*;
import com.dooribeon.global.ApiException;
import com.dooribeon.neighborhood.*;
import com.dooribeon.route.*;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;

import static com.dooribeon.recommend.RecommendationCheckResponse.Status.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RecommendationCheckServiceTests {
    private final NeighborhoodRepository neighborhoods = mock(NeighborhoodRepository.class);
    private final RentContractRepository contracts = mock(RentContractRepository.class);
    private final KakaoRouteClient transit = mock(KakaoRouteClient.class);
    private final KakaoWalkClient walk = mock(KakaoWalkClient.class);
    private final RecommendationCheckService service = new RecommendationCheckService(
            new ConditionsRules(), neighborhoods, contracts, transit, walk);
    private static final String KEY = "관악구 봉천동";

    @BeforeEach void data() {
        when(neighborhoods.findAll()).thenReturn(List.of(new Neighborhood("관악구", "봉천동", 1, 37.48, 126.95)));
        when(contracts.findByNeighborhood(KEY)).thenReturn(List.of(contract(1000, 50)));
    }

    @Test void missingRequiredInputsBlockLookupButZeroBudgetAndNoPlacesAreValid() {
        assertThat(service.check(new Conditions(null, 50L, List.of()), KEY).status()).isEqualTo(INPUT_REQUIRED);
        var missingMode = place("회사", Importance.REQUIRED, null, 30);
        assertThat(service.check(new Conditions(1000L, 50L, List.of(missingMode)), KEY).issues())
                .anyMatch(i -> i.path().equals("places[0].transport"));
        verifyNoInteractions(neighborhoods, contracts, transit, walk);
        when(contracts.findByNeighborhood(KEY)).thenReturn(List.of(contract(0, 0)));
        var checked = service.check(new Conditions(0L, 0L, List.of()), KEY);
        assertThat(checked.status()).isEqualTo(CHECK_PASSED);
        assertThat(checked.rankingApplied()).isFalse();
        assertThat(checked.places()).isEmpty();
        verifyNoInteractions(transit, walk);
    }

    @Test void depositAndRentMustMatchOnTheSameContractAndMissingDataIsSeparate() {
        when(contracts.findByNeighborhood(KEY)).thenReturn(List.of(contract(1000, 60), contract(2000, 50)));
        var input = new Conditions(1000L, 50L, List.of(place("회사", Importance.REQUIRED, Transport.PUBLIC_TRANSIT, 30)));
        var checked = service.check(input, KEY);
        assertThat(checked.status()).isEqualTo(NO_BUDGET_MATCH);
        assertThat(checked.neighborhood().budgetContracts()).isZero();
        when(contracts.findByNeighborhood(KEY)).thenReturn(List.of());
        assertThat(service.check(input, KEY).status()).isEqualTo(NO_CONTRACT_DATA);
        verifyNoInteractions(transit, walk);
    }

    @Test void allRequiredPlacesMustPassInSecondsWhileOptionalTimeIsInformational() {
        var first = place("회사", Importance.REQUIRED, Transport.PUBLIC_TRANSIT, 30);
        var second = place("학교", Importance.REQUIRED, Transport.WALK, 30);
        when(transit.getFastestTravelSeconds(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(OptionalInt.of(1800));
        when(walk.getTravelSeconds(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(OptionalInt.of(1801));
        var result = service.check(new Conditions(1000L, 50L, List.of(first, second)), KEY);
        assertThat(result.status()).isEqualTo(REQUIRED_TIME_EXCEEDED);
        assertThat(result.places().get(0).withinLimit()).isTrue();
        assertThat(result.places().get(1).seconds()).isEqualTo(1801);
        assertThat(result.places().get(1).minutes()).isEqualTo(31);
        assertThat(result.places().get(1).withinLimit()).isFalse();
        var optional = second.withImportance(Importance.HIGH);
        assertThat(service.check(new Conditions(1000L, 50L, List.of(first, optional)), KEY).status()).isEqualTo(CHECK_PASSED);
    }

    @Test void optionalDefaultModeAndFrequencyDoNotChangeTheCheckOrTheInput() {
        var p = place("친구", Importance.LOW, null, null);
        var withFrequency = new PlaceCondition(p.id(), p.label(), p.targetType(), p.query(), p.location(),
                p.importance(), null, null, new VisitFrequency(VisitFrequency.Period.MONTH, 2));
        when(transit.getFastestTravelSeconds(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(OptionalInt.of(600));
        var first = service.check(new Conditions(1000L, 50L, List.of(p)), KEY);
        var second = service.check(new Conditions(1000L, 50L, List.of(withFrequency)), KEY);
        assertThat(first).isEqualTo(second);
        assertThat(first.places().getFirst().transport()).isEqualTo(Transport.PUBLIC_TRANSIT);
        assertThat(first.places().getFirst().withinLimit()).isNull();
        assertThat(withFrequency.transport()).isNull();
        assertThat(withFrequency.frequency().count()).isEqualTo(2);
        verifyNoInteractions(walk);
    }

    @Test void missingRequiredRouteIsUnknownAndProviderFailureNeverBecomesNoResults() {
        var p = place("회사", Importance.REQUIRED, Transport.PUBLIC_TRANSIT, 30);
        var input = new Conditions(1000L, 50L, List.of(p));
        when(transit.getFastestTravelSeconds(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(OptionalInt.empty());
        var result = service.check(input, KEY);
        assertThat(result.status()).isEqualTo(REQUIRED_ROUTE_UNAVAILABLE);
        assertThat(result.places().getFirst().seconds()).isNull();
        assertThat(result.places().getFirst().withinLimit()).isNull();
        assertThat(service.check(new Conditions(1000L, 50L, List.of(p.withImportance(Importance.LOW))), KEY)
                .status()).isEqualTo(CHECK_PASSED);
        when(transit.getFastestTravelSeconds(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenThrow(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR, "private-provider-body"));
        assertThatThrownBy(() -> service.check(input, KEY)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.code()).isEqualTo("ROUTE_PROVIDER_ERROR");
            assertThat(e.getMessage()).doesNotContain("private-provider-body");
        });
    }

    static PlaceCondition place(String id, Importance importance, Transport transport, Integer maxMinutes) {
        return new PlaceCondition(id, id, TargetType.FIXED_PLACE, "여의도역",
                new PlaceLocation("21160843", "여의도역", "서울 영등포구", 37.52, 126.92),
                importance, transport, maxMinutes, null);
    }

    private RentContract contract(int deposit, int rent) {
        return new RentContract("관악구", "봉천동", "오피스텔", 20, 3, deposit, rent);
    }
}
