package com.dooribeon.condition.place;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.dooribeon.global.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class KakaoLocalClientTests {
    private static final String URL = "https://dapi.kakao.com";

    @Test
    void keywordSearchEncodesQueryAndMapsLongitudeLatitudeAndAddressFallback() {
        var builder = RestClient.builder().baseUrl(URL);
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(org.hamcrest.Matchers.containsString("query=A%26B")))
                .andExpect(header("Authorization", "KakaoAK test-key"))
                .andRespond(withSuccess("""
                        {"meta":{"is_end":false},"documents":[{"id":"k1","place_name":"A&B",
                        "road_address_name":"","address_name":"서울 영등포구","x":"126.92","y":"37.52"}]}
                        """, MediaType.APPLICATION_JSON));
        var result = new KakaoLocalClient(builder.build(), "test-key").search("A&B", PlaceSearchClient.SearchType.KEYWORD, 1);
        assertThat(result.hasNext()).isTrue();
        var place = result.locations().getFirst();
        assertThat(place.placeId()).isEqualTo("k1");
        assertThat(place.address()).isEqualTo("서울 영등포구");
        assertThat(place.lat()).isEqualTo(37.52);
        assertThat(place.lng()).isEqualTo(126.92);
        server.verify();
    }

    @Test
    void addressSearchDoesNotInventPlaceIdOrSelectBroadRegionCenter() {
        var builder = RestClient.builder().baseUrl(URL);
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/search/address.json")))
                .andRespond(withSuccess("""
                        {"meta":{"is_end":true},"documents":[
                        {"address_type":"REGION","address_name":"서울 여의도동","x":"126.92","y":"37.52"},
                        {"address_type":"ROAD_ADDR","address_name":"서울 영등포구 의사당대로 1",
                         "road_address":{"address_name":"서울 영등포구 의사당대로 1","building_name":""},
                         "x":"126.91","y":"37.53"}]}
                        """, MediaType.APPLICATION_JSON));
        var result = new KakaoLocalClient(builder.build(), "test-key").search("의사당대로 1", PlaceSearchClient.SearchType.ADDRESS, 1);
        assertThat(result.locations()).hasSize(1);
        assertThat(result.locations().getFirst().placeId()).isNull();
        assertThat(result.locations().getFirst().placeName()).isNull();
        assertThat(result.locations().getFirst().address()).contains("의사당대로 1");
        server.verify();
    }

    @Test
    void badCoordinatesMissingKeyAndProviderFailureAreNotEmptySuccessfulResults() {
        for (String body : new String[] {
                "{\"meta\":{\"is_end\":true},\"documents\":[{\"id\":\"1\",\"place_name\":\"역\",\"address_name\":\"서울\",\"x\":\"126.9\",\"y\":\"NaN\"}]}",
                "{\"documents\":[]}"}) {
            var builder = RestClient.builder().baseUrl(URL);
            var server = MockRestServiceServer.bindTo(builder).build();
            server.expect(anything()).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
            assertThatThrownBy(() -> new KakaoLocalClient(builder.build(), "test-key")
                    .search("역", PlaceSearchClient.SearchType.KEYWORD, 1)).isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.code()).isEqualTo("PLACE_SEARCH_INVALID_RESPONSE"));
            server.verify();
        }
        var builder = RestClient.builder().baseUrl(URL);
        var server = MockRestServiceServer.bindTo(builder).build();
        assertThatThrownBy(() -> new KakaoLocalClient(builder.build(), "")
                .search("역", PlaceSearchClient.SearchType.KEYWORD, 1)).isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo("PLACE_SEARCH_NOT_CONFIGURED"));
        server.verify();
        server.expect(anything()).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body("private test-key"));
        assertThatThrownBy(() -> new KakaoLocalClient(builder.build(), "test-key")
                .search("역", PlaceSearchClient.SearchType.KEYWORD, 1)).isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo("PLACE_SEARCH_BUSY");
                    assertThat(e.getMessage()).doesNotContain("test-key", "private");
                });
        server.verify();
    }
}
