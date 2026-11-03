package com.dooribeon.condition.place;

import com.dooribeon.condition.PlaceLocation;
import com.dooribeon.global.ApiException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 카카오 공식 로컬 검색 응답만 위치로 바꾼다. AI 추측·위치 로그·재사용 캐시는 없다. */
@Component
public class KakaoLocalClient implements PlaceSearchClient {
    private final RestClient client;
    private final String apiKey;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public KakaoLocalClient(@Qualifier("kakaoLocalRestClient") RestClient client,
                            @Value("${kakao.local.rest-api-key:}") String apiKey) {
        this.client = client;
        this.apiKey = apiKey;
    }

    @Override
    public SearchResult search(String query, SearchType type, int page) {
        if (query == null || query.isBlank() || query.length() > 200 || type == null || page < 1 || page > 45) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PLACE_SEARCH", "검색어와 검색 페이지를 확인해 주세요.");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PLACE_SEARCH_NOT_CONFIGURED",
                    "장소 검색 키가 아직 설정되지 않았어요. 서버의 카카오 REST API 키를 확인해 주세요.");
        }
        String body;
        try {
            body = client.get().uri(builder -> builder.path(type == SearchType.KEYWORD
                            ? "/v2/local/search/keyword.json" : "/v2/local/search/address.json")
                    .queryParam("query", "{query}").queryParam("page", page).queryParam("size", 15).build(query.strip()))
                    .header("Authorization", "KakaoAK " + apiKey).accept(MediaType.APPLICATION_JSON)
                    .retrieve().body(String.class);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 429) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "PLACE_SEARCH_BUSY", "장소 검색 요청이 많아요. 잠시 뒤 다시 시도해 주세요.");
            throw new ApiException(HttpStatus.BAD_GATEWAY, "PLACE_SEARCH_PROVIDER_ERROR",
                    "장소 검색 제공자가 요청을 처리하지 못했어요. 서버의 키·사용 권한을 확인해 주세요.");
        } catch (ResourceAccessException e) {
            if (e.getMostSpecificCause() instanceof HttpTimeoutException || e.getMostSpecificCause() instanceof SocketTimeoutException) {
                throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "PLACE_SEARCH_TIMEOUT", "장소 검색이 늦어지고 있어요. 다시 시도해 주세요.");
            }
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PLACE_SEARCH_CONNECTION_FAILED", "장소 검색에 연결하지 못했어요.");
        }
        return decode(body, type, page);
    }

    private SearchResult decode(String body, SearchType type, int page) {
        try {
            JsonNode root = mapper.readTree(body);
            if (root == null || !root.path("documents").isArray() || !root.path("meta").path("is_end").isBoolean()) throw invalid();
            if (root.path("documents").size() > 15) throw invalid();
            var locations = new ArrayList<PlaceLocation>();
            for (JsonNode document : root.path("documents")) {
                String id;
                String name;
                String address;
                if (type == SearchType.KEYWORD) {
                    id = text(document, "id");
                    name = text(document, "place_name");
                    address = text(document, "road_address_name");
                    if (address == null) address = text(document, "address_name");
                    if (id == null || name == null || address == null) throw invalid();
                } else {
                    String addressType = text(document, "address_type");
                    if (addressType == null) throw invalid();
                    // 동·도로 전체의 중심점을 특정 건물 위치로 확정하지 않는다.
                    if (Set.of("REGION", "ROAD").contains(addressType)) continue;
                    if (!Set.of("REGION_ADDR", "ROAD_ADDR").contains(addressType)) throw invalid();
                    id = null; // 주소 검색에는 카카오 장소 id가 없다. 만들어 넣지 않는다.
                    name = text(document.path("road_address"), "building_name");
                    address = text(document.path("road_address"), "address_name");
                    if (address == null) address = text(document, "address_name");
                    if (address == null) throw invalid();
                }
                double lat = coordinate(document, "y", -90, 90);
                double lng = coordinate(document, "x", -180, 180);
                locations.add(new PlaceLocation(id, name, address, lat, lng));
            }
            return new SearchResult(locations, !root.path("meta").path("is_end").booleanValue() && page < 45);
        } catch (ApiException e) { throw e; }
        catch (RuntimeException e) { throw invalid(); }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isString()) throw invalid();
        String result = value.asString().strip();
        return result.isEmpty() ? null : result;
    }

    private double coordinate(JsonNode node, String field, double min, double max) {
        String value = text(node, field);
        if (value == null) throw invalid();
        double result = Double.parseDouble(value);
        if (!Double.isFinite(result) || result < min || result > max) throw invalid();
        return result;
    }

    private ApiException invalid() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "PLACE_SEARCH_INVALID_RESPONSE",
                "장소 검색 결과의 주소·좌표를 확인하지 못했어요. 다시 검색해 주세요.");
    }
}
