package com.roomfit.placement;

import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class EmptyRoomRecommendationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EntityManager entityManager;

    @Test
    void emptyFurnitureRoom_canBeReloadedAndRecommended() throws Exception {
        String roomResponse = mockMvc.perform(post("/api/rooms/upload")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Empty Room",
                                  "room": { "width": 5.8, "depth": 5.4, "height": 2.7 },
                                  "openings": [],
                                  "furniture": []
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.furniture.length()").value(0))
                .andReturn()
                .getResponse()
                .getContentAsString();
        long roomId = ((Number) JsonPath.read(roomResponse, "$.data.roomId")).longValue();

        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(get("/api/rooms/{roomId}", roomId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.furniture.length()").value(0));

        String contextResponse = mockMvc.perform(post("/api/agent/context")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "roomId": %d,
                                  "lifestyleGoal": "STUDY_FOCUSED",
                                  "designStyle": ["MIDCENTURY"],
                                  "requiredItems": ["desk"],
                                  "optionalItems": [],
                                  "selectedImageIds": [5],
                                  "selectedProductIds": [],
                                  "preferredColorTone": "BROWN_WOOD"
                                }
                                """.formatted(roomId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.styleTags[0]").value("midcentury"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        long contextId = ((Number) JsonPath.read(contextResponse, "$.data.contextId")).longValue();

        mockMvc.perform(post("/api/layouts/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "roomId": %d,
                                  "contextId": %d
                                }
                                """.formatted(roomId, contextId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.recommendedFurniture[0].productId")
                        .value(org.hamcrest.Matchers.startsWith("expansion-c002")))
                .andExpect(jsonPath("$.data.recommendedFurniture[0].variantId")
                        .value("expansion-c002"))
                .andExpect(jsonPath("$.data.validationResult", notNullValue()))
                .andExpect(jsonPath("$.error", nullValue()));
    }

    @Test
    void tooSmallRoom_returnsStructuredRecommendationFailureWithoutSavingLayout() throws Exception {
        String roomResponse = mockMvc.perform(post("/api/rooms/upload")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Tiny Room",
                                  "room": { "width": 1.0, "depth": 1.0, "height": 2.4 },
                                  "openings": [], "furniture": []
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long roomId = ((Number) JsonPath.read(roomResponse, "$.data.roomId")).longValue();

        String contextResponse = mockMvc.perform(post("/api/agent/context")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "roomId": %d,
                                  "lifestyleGoal": "STUDY_FOCUSED",
                                  "designStyle": ["MINIMAL"],
                                  "requiredItems": ["bed"],
                                  "optionalItems": [],
                                  "selectedImageIds": [1],
                                  "selectedProductIds": []
                                }
                                """.formatted(roomId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long contextId = ((Number) JsonPath.read(contextResponse, "$.data.contextId")).longValue();

        mockMvc.perform(post("/api/layouts/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "roomId": %d, "contextId": %d }
                                """.formatted(roomId, contextId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.layoutId").value(nullValue()))
                .andExpect(jsonPath("$.data.recommendationStatus").value("FAILED"))
                .andExpect(jsonPath("$.data.requestedFurnitureCount").value(1))
                .andExpect(jsonPath("$.data.placedFurnitureCount").value(0))
                .andExpect(jsonPath("$.data.unplacedFurniture[0].reasonCode").value("NO_VALID_BOUNDARY_PLACEMENT"))
                .andExpect(jsonPath("$.data.warningCode").value("INSUFFICIENT_ROOM_SPACE"));
    }
}
