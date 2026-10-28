package com.dooribeon.condition;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dooribeon.global.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "chatkhu.api-key=")
@AutoConfigureMockMvc
class ConditionControllerTests {
    @Autowired MockMvc mvc;
    @MockitoBean ConditionsExtractor extractor;

    @Test
    void monthlyRentWithoutDepositReturnsIncompleteAndQuestion() throws Exception {
        when(extractor.extract(anyString())).thenReturn(new ConditionsExtraction(null, 60L, java.util.List.of(), java.util.List.of(), java.util.List.of()));
        mvc.perform(post("/api/conditions/parse").contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"월세 60만 원 이하\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.conditions.maxRent").value(60))
                .andExpect(jsonPath("$.readyForRecommendation").value(false))
                .andExpect(jsonPath("$.issues[0].path").value("maxDeposit"))
                .andExpect(jsonPath("$.nextQuestion").value("보증금은 최대 몇 만원까지 생각하고 있나요?"));
    }

    @Test
    void invalidInputNeverCallsAi() throws Exception {
        for (String body : new String[] {"{}", "{\"text\":\" \"}", "{\"text\":null}", "{\"text\":123}", "{\"wrong\":\"문장\"}", "{", "{\"text\":\"월세 60만 원\",\"wrong\":true}"}) {
            mvc.perform(post("/api/conditions/parse").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(extractor);
    }

    @Test
    void aiFailureReturnsErrorInsteadOfSuccessOrEmptyBudget() throws Exception {
        when(extractor.extract(anyString())).thenThrow(new ApiException(HttpStatus.BAD_GATEWAY, "AI_PROVIDER_ERROR", "AI 요청에 실패했어요."));
        mvc.perform(post("/api/conditions/parse").contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"월세 60만 원\"}"))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("AI_PROVIDER_ERROR"));
    }

    @Test
    void firstInputWithoutUnitsReturnsAQuestionAndNeverInventsRent() throws Exception {
        when(extractor.extract("월세 1000000")).thenReturn(new ConditionsExtraction(null, 100L, java.util.List.of(), java.util.List.of(), java.util.List.of()));
        mvc.perform(post("/api/conditions/parse").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"월세 1000000\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.readyForRecommendation").value(false))
                .andExpect(jsonPath("$.conditions.maxRent").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.nextQuestion").value(org.hamcrest.Matchers.containsString("단위")));
    }
}
