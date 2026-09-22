package com.roomfit.placement;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers the direct-placement path added for the client-led editor (§8 of the
 * roadmap): a Layout can now be created and edited with no AgentContext at
 * all, and a catalog product can be dropped at an arbitrary client-chosen
 * coordinate — the server validates and reports issues instead of moving it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LayoutDirectPlacementControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void createLayout_withoutAnyAgentContext_startsFromTheRoomsExistingFurniture() throws Exception {
        mockMvc.perform(post("/api/layouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "roomId": 1 }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.layoutId", not(nullValue())))
                .andExpect(jsonPath("$.data.confirmed").value(false))
                .andExpect(jsonPath("$.data.recommendedFurniture", not(empty())))
                .andExpect(jsonPath("$.data.scoreSummary.goalScore").value(80))
                .andExpect(jsonPath("$.data.scoreSummary.styleScore").value(80));
    }

    @Test
    void addFurnitureDirect_atAnArbitraryFreeCoordinate_succeedsWithNoErrorIssues() throws Exception {
        Long layoutId = createBlankLayout();

        mockMvc.perform(post("/api/layouts/{layoutId}/furniture", layoutId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "productId": "wardrobe-classic-gullaberg-01",
                                  "position": { "x": 2.9, "z": 4.3 },
                                  "rotation": 0
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recommendedFurniture[?(@.productId == 'wardrobe-classic-gullaberg-01')]",
                        hasSize(1)))
                .andExpect(jsonPath("$.data.validationResult.issues[?(@.type == 'BODY_COLLISION')]", empty()));
    }

    @Test
    void addFurnitureDirect_onTopOfExistingFurniture_stillSucceedsButReportsACollisionIssue() throws Exception {
        Long layoutId = createBlankLayout();
        JsonNode first = recommendedFurniture(layoutId).get(0);
        double x = first.get("position").get("x").asDouble();
        double z = first.get("position").get("z").asDouble();

        // Drop a wardrobe exactly on top of an existing item — the server
        // must not silently move it out of the way; it must accept the
        // placement as-given and surface a BODY_COLLISION issue instead.
        mockMvc.perform(post("/api/layouts/{layoutId}/furniture", layoutId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "productId": "wardrobe-classic-gullaberg-01",
                                  "position": { "x": %s, "z": %s },
                                  "rotation": 0
                                }
                                """.formatted(x, z)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.validationResult.collisionFree").value(false))
                .andExpect(jsonPath("$.data.validationResult.issues[?(@.type == 'BODY_COLLISION')]", not(empty())));
    }

    @Test
    void updateLayout_withoutAnyAgentContext_stillWorks() throws Exception {
        Long layoutId = createBlankLayout();

        // PUT requires the *entire* current furniture set (FURNITURE_ARRAY_MISMATCH
        // otherwise) — only the first item's position actually changes here, by a
        // small nudge that stays safely inside the room (jumping to an arbitrary
        // fixed point risks pushing a large item like the bed out of bounds).
        List<Map<String, Object>> furniture = new ArrayList<>();
        JsonNode all = recommendedFurniture(layoutId);
        for (int i = 0; i < all.size(); i++) {
            JsonNode item = all.get(i);
            Map<String, Object> override = new LinkedHashMap<>();
            override.put("id", item.get("id").asText());
            boolean moveThis = i == 0;
            double x = item.get("position").get("x").asDouble();
            double z = item.get("position").get("z").asDouble();
            override.put("position", Map.of("x", moveThis ? x + 0.05 : x, "z", z));
            override.put("rotation", item.get("rotation").asDouble());
            override.put("status", moveThis ? "USER_MODIFIED" : item.get("status").asText());
            furniture.add(override);
        }

        mockMvc.perform(put("/api/layouts/{layoutId}", layoutId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("furniture", furniture))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scoreSummary.goalScore").value(80));
    }

    @Test
    void confirmLayout_withoutAnyAgentContext_succeedsWhenValid() throws Exception {
        Long layoutId = createBlankLayout();

        mockMvc.perform(post("/api/layouts/{layoutId}/confirm", layoutId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.confirmed").value(true));
    }

    private Long createBlankLayout() throws Exception {
        String response = mockMvc.perform(post("/api/layouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "roomId": 1 }
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.data.layoutId")).longValue();
    }

    private JsonNode recommendedFurniture(Long layoutId) throws Exception {
        String response = mockMvc.perform(get("/api/layouts/{layoutId}", layoutId))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").path("recommendedFurniture");
    }
}
