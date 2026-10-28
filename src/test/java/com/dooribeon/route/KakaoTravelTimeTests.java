package com.dooribeon.route;

import com.dooribeon.global.ApiException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class KakaoTravelTimeTests {
    @Test void transitKeepsExactSecondsAndIgnoresUnneededProviderFields() {
        var builder = RestClient.builder().baseUrl("https://dapi.kakao.com");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://dapi.kakao.com/v2/routing/publictraffic?start_x=126.95&start_y=37.48&end_x=126.92&end_y=37.52"))
                .andExpect(header("Authorization", "KakaoAK test-key"))
                .andRespond(withSuccess("""
                        {"status":"OK","properties":{"total":2},"routes":[
                          {"properties":{"totalTime":2115,"fare":{"value":1500}},"steps":[]},
                          {"properties":{"totalTime":1801,"type":"BUS"},"steps":[]}
                        ]}
                        """, MediaType.APPLICATION_JSON));
        var client = new KakaoRouteClient(builder.build(), "test-key");
        assertThat(client.getFastestTravelSeconds(37.48, 126.95, 37.52, 126.92)).hasValue(1801);
        server.verify();
    }

    @Test void walkingKeepsExactSecondsAndBothClientsDistinguishNoRouteFromZeroDistance() {
        var builder = RestClient.builder().baseUrl("https://dapi.kakao.com");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/v2/routing/walk?")))
                .andRespond(withSuccess("""
                        {"status":"OK","route":{"properties":{"totalDistance":1500,"totalTime":1801},"legs":[]}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(anything()).andRespond(withSuccess("{\"status\":\"ROUTE_RESULT_NOT_FOUND\"}", MediaType.APPLICATION_JSON));
        server.expect(anything()).andRespond(withSuccess("{\"status\":\"SAME_POINT\"}", MediaType.APPLICATION_JSON));
        server.expect(anything()).andRespond(withSuccess("{\"status\":\"NO_RESULTS\"}", MediaType.APPLICATION_JSON));
        server.expect(anything()).andRespond(withSuccess("{\"status\":\"EQUAL_POINTS\"}", MediaType.APPLICATION_JSON));
        var rest = builder.build();
        var walk = new KakaoWalkClient(rest, "test-key");
        var transit = new KakaoRouteClient(rest, "test-key");
        assertThat(walk.getTravelSeconds(37.48, 126.95, 37.52, 126.92)).hasValue(1801);
        assertThat(walk.getTravelSeconds(37.48, 126.95, 37.52, 126.92)).isEmpty();
        assertThat(walk.getTravelSeconds(37.48, 126.95, 37.48, 126.95)).hasValue(0);
        assertThat(transit.getFastestTravelSeconds(37.48, 126.95, 37.52, 126.92)).isEmpty();
        assertThat(transit.getFastestTravelSeconds(37.48, 126.95, 37.48, 126.95)).hasValue(0);
        server.verify();
    }

    @Test void missingMalformedTimesUnknownStatusesAndMissingKeysNeverBecomeZeroMinuteSuccess() {
        for (String bad : List.of("null", "-1", "1800.1", "\"1800\"", "2147483648")) {
            var builder = RestClient.builder().baseUrl("https://dapi.kakao.com");
            var server = MockRestServiceServer.bindTo(builder).build();
            server.expect(anything()).andRespond(withSuccess("{\"status\":\"OK\",\"routes\":[{\"properties\":{\"totalTime\":" + bad + "}}]}", MediaType.APPLICATION_JSON));
            server.expect(anything()).andRespond(withSuccess("{\"status\":\"OK\",\"route\":{\"properties\":{\"totalTime\":" + bad + "}}}", MediaType.APPLICATION_JSON));
            var rest = builder.build();
            assertInvalid(() -> new KakaoRouteClient(rest, "test-key").getFastestTravelSeconds(37, 127, 38, 128));
            assertInvalid(() -> new KakaoWalkClient(rest, "test-key").getTravelSeconds(37, 127, 38, 128));
            server.verify();
        }
        var builder = RestClient.builder().baseUrl("https://dapi.kakao.com");
        var server = MockRestServiceServer.bindTo(builder).build();
        var rest = builder.build();
        assertThatThrownBy(() -> new KakaoRouteClient(rest, "").getFastestTravelSeconds(37, 127, 38, 128))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("ROUTE_NOT_CONFIGURED"));
        server.expect(anything()).andRespond(withSuccess("{\"status\":\"INVALID_REQUEST\"}", MediaType.APPLICATION_JSON));
        server.expect(anything()).andRespond(withSuccess("{\"status\":\"TOO_MANY_SEARCH_LINK\"}", MediaType.APPLICATION_JSON));
        assertInvalid(() -> new KakaoRouteClient(rest, "test-key").getFastestTravelSeconds(37, 127, 38, 128));
        assertInvalid(() -> new KakaoWalkClient(rest, "test-key").getTravelSeconds(37, 127, 38, 128));
        server.verify();
    }

    private void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo("ROUTE_INVALID_RESPONSE"));
    }
}
