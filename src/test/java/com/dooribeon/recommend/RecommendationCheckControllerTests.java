package com.dooribeon.recommend;

import com.dooribeon.neighborhood.*;
import com.dooribeon.route.*;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"chatkhu.api-key=", "kakao.rest-key="})
@AutoConfigureMockMvc
class RecommendationCheckControllerTests {
    @Autowired MockMvc mvc;
    @MockitoBean NeighborhoodRepository neighborhoods;
    @MockitoBean RentContractRepository contracts;
    @MockitoBean KakaoRouteClient transit;
    @MockitoBean KakaoWalkClient walk;

    @Test void commonJsonReachesRequiredTimeCheckWithoutLegacyFrequency() throws Exception {
        when(neighborhoods.findAll()).thenReturn(List.of(new Neighborhood("관악구", "봉천동", 1, 37.48, 126.95)));
        when(contracts.findByNeighborhood("관악구 봉천동"))
                .thenReturn(List.of(new RentContract("관악구", "봉천동", "오피스텔", 20, 3, 1000, 50)));
        when(transit.getFastestTravelSeconds(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(OptionalInt.of(1801));
        mvc.perform(post("/api/recommend/check").param("neighborhoodKey", "관악구 봉천동")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"maxDeposit":1000,"maxRent":50,"places":[{
                          "id":"p1","label":"회사","targetType":"FIXED_PLACE","query":"여의도역",
                          "location":{"placeId":"21160843","placeName":"여의도역","address":"서울 영등포구","lat":37.52,"lng":126.92},
                          "importance":"REQUIRED","transport":"PUBLIC_TRANSIT","maxMinutes":30,"frequency":null
                        }]}
                        """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REQUIRED_TIME_EXCEEDED"))
                .andExpect(jsonPath("$.rankingApplied").value(false))
                .andExpect(jsonPath("$.places[0].placeId").value("p1"))
                .andExpect(jsonPath("$.places[0].seconds").value(1801))
                .andExpect(jsonPath("$.places[0].withinLimit").value(false))
                .andExpect(jsonPath("$.weeklyMinutes").doesNotExist());
        verifyNoInteractions(walk);
    }

    @Test void malformedAndIncompleteBudgetsCannotTriggerProviderCalls() throws Exception {
        for (String value : List.of("50.1", "50.0", "\"50\"", "true")) {
            mvc.perform(post("/api/recommend/check").param("neighborhoodKey", "관악구 봉천동")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"maxDeposit\":1000,\"maxRent\":" + value + ",\"places\":[]}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_JSON"));
        }
        mvc.perform(post("/api/recommend/check").param("neighborhoodKey", "관악구 봉천동")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"maxRent\":50,\"places\":[]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("INPUT_REQUIRED"))
                .andExpect(jsonPath("$.issues[0].path").value("maxDeposit"));
        verifyNoInteractions(neighborhoods, contracts, transit, walk);
    }
}
