package com.dooribeon.condition.chatkhu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.dooribeon.global.ApiException;
import com.dooribeon.condition.Budget;
import com.dooribeon.condition.BudgetField;
import com.dooribeon.condition.RentalType;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ChatKhuBudgetClientTests {
    private static final String BASE_URL = "https://factchat-cloud.mindlogic.ai/v1/gateway";

    private ChatKhuBudgetClient client(RestClient restClient, String key) throws Exception {
        return new ChatKhuBudgetClient(restClient, new ChatKhuProperties(URI.create(BASE_URL), key,
                "claude-haiku-5-5", Duration.ofSeconds(10), Duration.ofSeconds(90)), new ChatKhuBudgetCodec());
    }

    @Test
    void sendsBearerAuthToCorrectPathAndReadsResponse() throws Exception {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL + "/chat/completions/"))
                .andExpect(method(HttpMethod.POST)).andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(jsonPath("$.response_format.json_schema.strict").value(true))
                .andRespond(withSuccess(ChatKhuBudgetCodecTests.envelope(ChatKhuBudgetCodecTests.monthlyBudget()), MediaType.APPLICATION_JSON));
        assertThat(client(builder.build(), "test-key").extract("보증금 1천만 원, 월세 50만 원").budget().monthlyRentMaxWon()).isEqualTo(500000L);
        server.verify();
    }

    @Test
    void missingKeyDoesNotCallProvider() throws Exception {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ChatKhuBudgetClient client = client(builder.build(), "");
        assertThatThrownBy(() -> client.extract("월세 60만 원"))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo("AI_NOT_CONFIGURED"));
        server.verify();
    }

    @Test
    void upstreamAuthErrorNeverExposesBodyOrKey() throws Exception {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL + "/chat/completions/"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("test-key must never be returned"));
        ChatKhuBudgetClient client = client(builder.build(), "test-key");
        assertThatThrownBy(() -> client.extract("월세 60만 원"))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(error.getMessage()).doesNotContain("test-key");
                });
        server.verify();
    }

    @Test
    void timeoutIsAnErrorInsteadOfEmptyConditions() throws Exception {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL + "/chat/completions/"))
                .andRespond(request -> { throw new SocketTimeoutException(); });
        ChatKhuBudgetClient client = client(builder.build(), "test-key");
        assertThatThrownBy(() -> client.extract("월세 60만 원"))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.status()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT));
        server.verify();
    }

    @Test
    void followUpUsesUpdateSchemaAndReadsTheChangedField() throws Exception {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String content = """
                {"budget":{"rentalType":null,"jeonseMaxWon":null,"depositMaxWon":10000000,"monthlyRentMaxWon":null},
                "updatedFields":["depositMaxWon"],"needsAmountUnitConfirmation":false}
                """;
        server.expect(requestTo(BASE_URL + "/chat/completions/"))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(jsonPath("$.response_format.json_schema.name").value("dooribeon_budget_update"))
                .andRespond(withSuccess(ChatKhuBudgetCodecTests.envelope(content), MediaType.APPLICATION_JSON));
        var update = client(builder.build(), "test-key").extractUpdate("천만 원",
                new Budget(RentalType.MONTHLY_RENT, null, null, 500000L), "보증금은 최대 얼마까지 생각하고 있나요?");
        assertThat(update.budget().depositMaxWon()).isEqualTo(10000000L);
        assertThat(update.updatedFields()).containsExactly(BudgetField.depositMaxWon);
        server.verify();
    }
}
