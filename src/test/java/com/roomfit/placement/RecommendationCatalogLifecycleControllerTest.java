package com.roomfit.placement;

import com.jayway.jsonpath.JsonPath;
import com.roomfit.product.catalog.GeneratedFurnitureCatalog;
import com.roomfit.product.domain.MockProduct;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.MockMvc;

import java.util.stream.Stream;

import static org.hamcrest.Matchers.notNullValue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RecommendationCatalogLifecycleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    static Stream<String> canonicalFurnitureTypes() {
        return GeneratedFurnitureCatalog.get().products().stream()
                .map(MockProduct::getType)
                .distinct();
    }

    @ParameterizedTest(name = "{0} persists, reloads, and confirms")
    @MethodSource("canonicalFurnitureTypes")
    void eachCanonicalType_survivesRecommendationLayoutAndRoomLifecycle(String furnitureType) throws Exception {
        String openings = "curtain_blind".equals(furnitureType)
                ? """
                  [{ "id": "window-1", "type": "window", "wall": "north",
                     "offset": 14, "width": 2, "height": 1, "sillHeight": 0.9 }]
                  """
                : "[]";
        String support = switch (furnitureType) {
            case "monitor" -> """
                    [{ "id": "support-1", "type": "desk", "label": "책상",
                       "width": 1.2, "depth": 0.6, "height": 0.73,
                       "position": { "x": 15, "z": 15 }, "rotation": 0, "status": "EXISTING",
                       "productId": "desk-compact-01", "variantId": "desk-compact" }]
                    """;
            case "tv" -> """
                    [{ "id": "support-1", "type": "media_console", "label": "TV장",
                       "width": 1.2, "depth": 0.42, "height": 0.48,
                       "position": { "x": 15, "z": 15 }, "rotation": 0, "status": "EXISTING",
                       "productId": "media-console-drawer-01", "variantId": "media-console-drawer" }]
                    """;
            default -> "[]";
        };
        int targetIndex = support.equals("[]") ? 0 : 1;
        long roomId = number(postJson("/api/rooms/upload", """
                { "name": "Catalog QA", "room": { "width": 30, "depth": 30, "height": 3 },
                  "openings": %s, "furniture": %s }
                """.formatted(openings, support)), "$.data.roomId");
        long contextId = number(postJson("/api/agent/context", """
                { "roomId": %d, "lifestyleGoal": "STUDY_FOCUSED", "designStyle": ["MINIMAL"],
                  "requiredItems": ["%s"], "optionalItems": [], "selectedImageIds": [1], "selectedProductIds": [] }
                """.formatted(roomId, furnitureType)), "$.data.contextId");

        String recommendation = postJson("/api/layouts/recommend", """
                { "roomId": %d, "contextId": %d }
                """.formatted(roomId, contextId));
        long layoutId = number(recommendation, "$.data.layoutId");
        if (targetIndex == 1) {
            assertThat((Number) JsonPath.read(recommendation,
                    "$.data.recommendedFurniture[1].position.x")).isEqualTo(15.0);
            assertThat((Number) JsonPath.read(recommendation,
                    "$.data.recommendedFurniture[1].position.z")).isEqualTo(15.0);
        }

        mockMvc.perform(get("/api/layouts/{layoutId}", layoutId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recommendedFurniture[%d].type".formatted(targetIndex)).value(furnitureType))
                .andExpect(jsonPath("$.data.recommendedFurniture[%d].productId".formatted(targetIndex), notNullValue()))
                .andExpect(jsonPath("$.data.recommendedFurniture[%d].variantId".formatted(targetIndex), notNullValue()));
        mockMvc.perform(post("/api/layouts/{layoutId}/confirm", layoutId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.confirmed").value(true));
        mockMvc.perform(get("/api/rooms/{roomId}", roomId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.furniture[%d].type".formatted(targetIndex)).value(furnitureType))
                .andExpect(jsonPath("$.data.furniture[%d].productId".formatted(targetIndex), notNullValue()))
                .andExpect(jsonPath("$.data.furniture[%d].variantId".formatted(targetIndex), notNullValue()));
    }

    private String postJson(String path, String body) throws Exception {
        return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    private long number(String body, String path) {
        return ((Number) JsonPath.read(body, path)).longValue();
    }
}
