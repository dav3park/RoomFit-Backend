package com.roomfit.placement;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "AI 추천 받기" 클릭 시 기존 가구를 지우고 새로 시작하는 discardExisting 옵션
 * (사용자 QA 피드백 반영) — 기존 가구가 새 추천 결과에 살아남지 않아야 하고,
 * 옵션을 생략하면 예전처럼 기존 가구가 유지되는 회귀도 함께 지킨다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LayoutServiceDiscardExistingTest {

    private static final Set<String> CANONICAL_SAMPLE_FURNITURE_IDS =
            Set.of("bed-1", "desk-1", "chair-1", "wardrobe-1");

    @Autowired
    private MockMvc mockMvc;

    @Test
    void discardExistingTrue_recommendationContainsNoneOfTheRoomsOriginalFurniture() throws Exception {
        Long contextId = createAgentContext();

        String response = mockMvc.perform(post("/api/layouts/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "roomId": 1, "contextId": %d, "discardExisting": true }
                                """.formatted(contextId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Set<String> resultIds = furnitureIds(response);
        assertThat(resultIds).noneMatch(CANONICAL_SAMPLE_FURNITURE_IDS::contains);
    }

    @Test
    void discardExistingOmitted_keepsExistingBehaviorOfLayeringOnExistingFurniture() throws Exception {
        Long contextId = createAgentContext();

        String response = mockMvc.perform(post("/api/layouts/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "roomId": 1, "contextId": %d }
                                """.formatted(contextId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Set<String> resultIds = furnitureIds(response);
        assertThat(resultIds).containsAll(CANONICAL_SAMPLE_FURNITURE_IDS);
    }

    private Set<String> furnitureIds(String response) throws Exception {
        List<?> raw = JsonPath.read(response, "$.data.recommendedFurniture[*].id");
        return raw.stream().map(Object::toString).collect(Collectors.toSet());
    }

    private Long createAgentContext() throws Exception {
        String contextResponse = mockMvc.perform(post("/api/agent/context")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "roomId": 1,
                                  "lifestyleGoal": "STUDY_FOCUSED",
                                  "designStyle": ["MINIMAL"],
                                  "requiredItems": ["chair"],
                                  "optionalItems": [],
                                  "selectedImageIds": [1],
                                  "selectedProductIds": ["desk-compact-01"]
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(contextResponse, "$.data.contextId")).longValue();
    }
}
