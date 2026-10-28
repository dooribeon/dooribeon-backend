package com.dooribeon.recommend;

import com.dooribeon.condition.Conditions;
import com.dooribeon.condition.ConditionsRules;
import com.dooribeon.condition.PlaceCondition;
import com.dooribeon.condition.PlaceCondition.Importance;
import com.dooribeon.condition.PlaceCondition.Transport;
import com.dooribeon.global.ApiException;
import com.dooribeon.neighborhood.NeighborhoodRepository;
import com.dooribeon.neighborhood.RentContractRepository;
import com.dooribeon.recommend.RecommendationCheckResponse.*;
import com.dooribeon.route.KakaoRouteClient;
import com.dooribeon.route.KakaoWalkClient;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** 순위 정책과 분리한 검사 단계. 기존 후보 20개 축소나 방문 빈도 가중치를 적용하지 않는다. */
@Service
@RequiredArgsConstructor
public class RecommendationCheckService {
    private final ConditionsRules rules;
    private final NeighborhoodRepository neighborhoods;
    private final RentContractRepository contracts;
    private final KakaoRouteClient transit;
    private final KakaoWalkClient walk;

    public RecommendationCheckResponse check(Conditions conditions, String neighborhoodKey) {
        var input = rules.check(conditions);
        var notices = new ArrayList<>(input.notices());
        notices.add("동네 한 곳의 조건 검사예요. 추천 순위·방문 빈도·최소 표본 기준은 적용하지 않았어요.");
        if (!input.readyForRecommendation()) {
            return new RecommendationCheckResponse(Status.INPUT_REQUIRED, false,
                    input.issues(), input.nextQuestion(), notices, null, List.of());
        }
        var neighborhood = neighborhoods.findAll().stream()
                .filter(n -> n.key().equals(neighborhoodKey)).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NEIGHBORHOOD_NOT_FOUND",
                        "검사할 동네를 찾지 못했어요. 데이터에 있는 구·동 이름을 확인해 주세요."));
        var samples = contracts.findByNeighborhood(neighborhood.key());
        int matching = (int) samples.stream().filter(c -> c.deposit() <= conditions.maxDeposit()
                && c.rent() <= conditions.maxRent()).count();
        var checked = new NeighborhoodCheck(neighborhood.key(), neighborhood.lat(), neighborhood.lng(),
                samples.size(), matching);
        notices.add("예산은 과거 계약의 보증금·월세를 함께 비교하며 관리비는 제외해요. 현재 매물을 보장하지 않아요.");
        if (samples.isEmpty()) return result(Status.NO_CONTRACT_DATA, notices, checked, List.of());
        if (matching == 0) return result(Status.NO_BUDGET_MATCH, notices, checked, List.of());

        var places = new ArrayList<PlaceCheck>();
        boolean exceeded = false;
        boolean requiredRouteMissing = false;
        for (var place : conditions.places()) {
            Transport transport = place.transport() == null ? Transport.PUBLIC_TRANSIT : place.transport();
            OptionalInt seconds = travelSeconds(transport, neighborhood.lat(), neighborhood.lng(),
                    place.location().lat(), place.location().lng());
            String name = displayName(place);
            if (seconds.isEmpty()) {
                places.add(new PlaceCheck(place.id(), name, place.importance(), transport, place.maxMinutes(),
                        RouteStatus.NOT_FOUND, null, null, null));
                if (place.importance() == Importance.REQUIRED) requiredRouteMissing = true;
                notices.add(name + "까지 경로를 찾지 못해 시간을 확인할 수 없어요.");
                continue;
            }
            int duration = seconds.getAsInt();
            Boolean within = place.maxMinutes() == null ? null : duration <= place.maxMinutes() * 60L;
            if (place.importance() == Importance.REQUIRED && Boolean.FALSE.equals(within)) exceeded = true;
            places.add(new PlaceCheck(place.id(), name, place.importance(), transport, place.maxMinutes(),
                    RouteStatus.FOUND, duration, (int) ((duration + 59L) / 60), within));
        }
        if (!places.isEmpty()) notices.add("이동시간은 동네의 기준 지점에서 선택 장소까지의 경로 조회값이에요. 개별 집의 이동시간과 다를 수 있어요.");
        Status status = exceeded ? Status.REQUIRED_TIME_EXCEEDED
                : requiredRouteMissing ? Status.REQUIRED_ROUTE_UNAVAILABLE : Status.CHECK_PASSED;
        return result(status, notices, checked, places);
    }

    private OptionalInt travelSeconds(Transport mode, double fromLat, double fromLng, double toLat, double toLng) {
        try {
            return mode == Transport.WALK ? walk.getTravelSeconds(fromLat, fromLng, toLat, toLng)
                    : transit.getFastestTravelSeconds(fromLat, fromLng, toLat, toLng);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 429) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ROUTE_PROVIDER_BUSY",
                        "경로 조회 한도에 도달했어요. 잠시 뒤 다시 시도해 주세요.");
            }
            throw new ApiException(HttpStatus.BAD_GATEWAY, "ROUTE_PROVIDER_ERROR",
                    "경로 제공자가 요청을 처리하지 못했어요. 서버의 키·사용 권한을 확인해 주세요.");
        } catch (ResourceAccessException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ROUTE_PROVIDER_UNAVAILABLE",
                    "경로 제공자와 연결하지 못했어요. 잠시 뒤 다시 시도해 주세요.");
        } catch (RestClientException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "ROUTE_INVALID_RESPONSE",
                    "경로 응답을 확인하지 못했어요. 잠시 뒤 다시 시도해 주세요.");
        }
    }

    private RecommendationCheckResponse result(Status status, List<String> notices,
            NeighborhoodCheck neighborhood, List<PlaceCheck> places) {
        return new RecommendationCheckResponse(status, false, List.of(), null, notices, neighborhood, places);
    }

    private String displayName(PlaceCondition place) {
        if (place.label() != null) return place.label();
        return place.location().placeName() != null ? place.location().placeName() : place.location().address();
    }
}
