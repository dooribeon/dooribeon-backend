package com.dooribeon.condition.chatkhu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.dooribeon.condition.ConditionsExtractionService;
import com.dooribeon.condition.ConditionsRules;
import com.dooribeon.global.ApiException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ChatKhuConditionsClientTests {
    private static final String URL = "https://factchat-cloud.mindlogic.ai/v1/gateway";

    @Test
    void firstInputFlowsThroughMockProviderDecoderAndRulesWithoutRealApi() throws Exception {
        var builder = RestClient.builder().baseUrl(URL);
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URL + "/chat/completions/"))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(jsonPath("$.response_format.json_schema.name").value("dooribeon_conditions"))
                .andExpect(jsonPath("$.messages[1].content").value("보증금 천만 원, 월세 50만 원"))
                .andRespond(withSuccess(ChatKhuBudgetCodecTests.envelope(
                        ChatKhuConditionsCodecTests.emptyPlaces("1000", "50", "[]")), MediaType.APPLICATION_JSON));
        var service = new ConditionsExtractionService(client(builder.build(), "test-key"), new ConditionsRules());
        assertThat(service.parse("보증금 천만 원, 월세 50만 원").readyForRecommendation()).isTrue();
        server.verify();
    }

    @Test
    void followupSendsCurrentConditionsAndReadsOnlyDeclaredChanges() throws Exception {
        var builder = RestClient.builder().baseUrl(URL);
        var server = MockRestServiceServer.bindTo(builder).build();
        String patch = "{\"values\":" + ChatKhuConditionsCodecTests.emptyPlaces("1000", "null", "[]")
                + ",\"updatedFields\":[\"maxDeposit\"],\"placeUpdates\":[],\"resolvedIssueIds\":[]}";
        server.expect(requestTo(URL + "/chat/completions/"))
                .andExpect(jsonPath("$.response_format.json_schema.name").value("dooribeon_conditions_update"))
                .andExpect(jsonPath("$.messages[1].content").value(org.hamcrest.Matchers.containsString("lastQuestion")))
                .andRespond(withSuccess(ChatKhuBudgetCodecTests.envelope(patch), MediaType.APPLICATION_JSON));
        var draft = new com.dooribeon.condition.ConditionsDraft(
                new com.dooribeon.condition.Conditions(null, 50L, java.util.List.of()),
                java.util.Set.of(), java.util.List.of(), java.util.List.of());
        var update = client(builder.build(), "test-key").extractUpdate("천만 원", draft, "보증금은 얼마인가요?");
        assertThat(update.updatedFields()).containsExactly("maxDeposit");
        assertThat(update.values().maxDeposit()).isEqualTo(1000L);
        assertThat(update.values().maxRent()).isNull();
        server.verify();
    }

    @Test
    void missingConfigurationNeverSendsARequest() throws Exception {
        var builder = RestClient.builder().baseUrl(URL);
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = client(builder.build(), "");
        assertThatThrownBy(() -> client.extract("월세 50만 원"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("AI_NOT_CONFIGURED"));
        server.verify();
    }

    @Test
    void providerFailureAndBusyStatusNeverExposeProviderBody() throws Exception {
        for (HttpStatus status : new HttpStatus[] {HttpStatus.UNAUTHORIZED, HttpStatus.BAD_REQUEST, HttpStatus.TOO_MANY_REQUESTS}) {
            var builder = RestClient.builder().baseUrl(URL);
            var server = MockRestServiceServer.bindTo(builder).build();
            server.expect(requestTo(URL + "/chat/completions/"))
                    .andRespond(withStatus(status).body("test-key private provider body response_format json_schema"));
            var client = client(builder.build(), "test-key");
            assertThatThrownBy(() -> client.extract("월세 50만 원"))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.code()).isEqualTo(status == HttpStatus.TOO_MANY_REQUESTS ? "AI_BUSY" : "AI_PROVIDER_ERROR");
                        assertThat(e.getMessage()).doesNotContain("test-key", "private provider body", "response_format", "json_schema");
                        if (status != HttpStatus.TOO_MANY_REQUESTS) {
                            assertThat(e.getMessage()).contains("HTTP " + status.value());
                        }
                        if (status == HttpStatus.BAD_REQUEST) assertThat(e.getMessage()).contains("JSON 응답 형식");
                    });
            server.verify();
        }
    }

    @Test
    void timeoutIsReportedInsteadOfReturningEmptyConditions() throws Exception {
        var builder = RestClient.builder().baseUrl(URL);
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URL + "/chat/completions/"))
                .andRespond(request -> { throw new SocketTimeoutException(); });
        var client = client(builder.build(), "test-key");
        assertThatThrownBy(() -> client.extract("월세 50만 원"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("AI_TIMEOUT"));
        server.verify();
    }

    private ChatKhuConditionsClient client(RestClient rest, String key) throws Exception {
        return new ChatKhuConditionsClient(rest,
                new ChatKhuProperties(URI.create(URL), key, "test-model", Duration.ofSeconds(10), Duration.ofSeconds(90)),
                new ChatKhuConditionsCodec());
    }
}
