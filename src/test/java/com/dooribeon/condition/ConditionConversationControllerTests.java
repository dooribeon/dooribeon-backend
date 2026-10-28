package com.dooribeon.condition;

import static com.dooribeon.condition.ConditionConversationServiceTests.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import com.dooribeon.condition.place.PlaceSearchClient;
import java.util.List;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "chatkhu.api-key=")
@AutoConfigureMockMvc
class ConditionConversationControllerTests {
    @Autowired MockMvc mvc;
    @MockitoBean ConditionsExtractor extractor;
    @MockitoBean PlaceSearchClient placeSearch;

    @Test
    void conversationApiReturnsCommonManwonConditionsAndMergesAnswer() throws Exception {
        when(extractor.extract(anyString())).thenReturn(values(null, 50L));
        String body = mvc.perform(post("/api/conditions/conversations").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"월세 50만 원\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.conditions.maxRent").value(50))
                .andExpect(jsonPath("$.readyForRecommendation").value(false))
                .andExpect(jsonPath("$.budget").doesNotExist()).andReturn().getResponse().getContentAsString();
        String id = JsonMapper.builder().build().readTree(body).get("conversationId").asString();
        when(extractor.extractUpdate(anyString(), any(), anyString())).thenReturn(money(1000L, null, "maxDeposit", Set.of()));
        mvc.perform(post("/api/conditions/conversations/{id}/messages", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"천만 원\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.conversationId").value(id))
                .andExpect(jsonPath("$.conditions.maxDeposit").value(1000))
                .andExpect(jsonPath("$.conditions.maxRent").value(50))
                .andExpect(jsonPath("$.conditions.places").isEmpty())
                .andExpect(jsonPath("$.readyForRecommendation").value(true))
                .andExpect(jsonPath("$.questions").doesNotExist())
                .andExpect(jsonPath("$.nextQuestion").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void searchAndSelectionFillLocationWithoutChangingBudgetOrPlaceConditions() throws Exception {
        var company = new ConditionsExtraction.ExtractedPlace("회사", PlaceCondition.TargetType.FIXED_PLACE, "여의도역",
                ConditionsExtraction.Role.WORK, null, false, PlaceCondition.Transport.PUBLIC_TRANSIT, 40, null, false);
        when(extractor.extract(anyString())).thenReturn(new ConditionsExtraction(1000L, 50L, List.of(company), List.of(), List.of()));
        var mapper = JsonMapper.builder().build();
        var initial = mapper.readTree(mvc.perform(post("/api/conditions/conversations").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"보증금 천만 원 월세 50만 원 회사 여의도역 대중교통 40분\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.readyForRecommendation").value(false))
                .andReturn().getResponse().getContentAsString());
        String id = initial.get("conversationId").asString();
        String placeId = initial.get("conditions").get("places").get(0).get("id").asString();
        String path = "/api/conditions/conversations/" + id + "/places/" + placeId;
        when(placeSearch.search("여의도역", PlaceSearchClient.SearchType.KEYWORD, 1)).thenReturn(
                new PlaceSearchClient.SearchResult(List.of(new PlaceLocation("k1", "여의도역", "서울 영등포구", 37.52, 126.92)), false));
        var found = mapper.readTree(mvc.perform(post(path + "/search").contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"여의도역\",\"type\":\"KEYWORD\",\"page\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.candidates[0].location.placeId").value("k1"))
                .andReturn().getResponse().getContentAsString());
        String searchId = found.get("searchId").asString();
        String candidateId = found.get("candidates").get(0).get("candidateId").asString();
        mvc.perform(put(path + "/location").contentType(MediaType.APPLICATION_JSON)
                .content("{\"searchId\":\"" + searchId + "\",\"candidateId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isBadRequest());
        String selection = "{\"searchId\":\"" + searchId + "\",\"candidateId\":\"" + candidateId + "\"}";
        mvc.perform(put(path + "/location").contentType(MediaType.APPLICATION_JSON).content(selection))
                .andExpect(status().isOk()).andExpect(jsonPath("$.conditions.maxDeposit").value(1000))
                .andExpect(jsonPath("$.conditions.maxRent").value(50))
                .andExpect(jsonPath("$.conditions.places[0].id").value(placeId))
                .andExpect(jsonPath("$.conditions.places[0].label").value("회사"))
                .andExpect(jsonPath("$.conditions.places[0].location.lat").value(37.52))
                .andExpect(jsonPath("$.conditions.places[0].importance").value("REQUIRED"))
                .andExpect(jsonPath("$.conditions.places[0].maxMinutes").value(40))
                .andExpect(jsonPath("$.readyForRecommendation").value(true));
        mvc.perform(put(path + "/location").contentType(MediaType.APPLICATION_JSON).content(selection))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_PLACE_SEARCH"));
        verify(extractor, never()).extractUpdate(anyString(), any(), anyString());
    }

    @Test
    void invalidRequestsAndMissingConversationNeverCallAi() throws Exception {
        mvc.perform(post("/api/conditions/conversations/{id}/messages", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"천만 원\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/conditions/conversations/not-an-id/messages")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"천만 원\"}"))
                .andExpect(status().isBadRequest());
        for (String path : new String[] {"/api/conditions/conversations", "/api/conditions/conversations/" + UUID.randomUUID() + "/messages"}) {
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{\"text\":123}"))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(extractor);
    }
}
