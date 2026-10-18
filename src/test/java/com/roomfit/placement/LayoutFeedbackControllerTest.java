package com.roomfit.placement;

import com.jayway.jsonpath.JsonPath;
import com.roomfit.room.Furniture;
import com.roomfit.room.FurnitureBoundary;
import com.roomfit.room.FurnitureStatus;
import com.roomfit.room.Position;
import com.roomfit.room.Room;
import com.roomfit.room.RoomRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;

@SpringBootTest(properties = {
        "roomfit.llm.feedback.enabled=false",
        "roomfit.llm.api-key=",
        "roomfit.llm.base-url=",
        "roomfit.llm.model="
})
@AutoConfigureMockMvc
@Transactional
class LayoutFeedbackControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private LayoutRepository layoutRepository;
    @Autowired
    private RoomRepository roomRepository;

    @Test
    void feedback_withLargerDesk_returnsReRecommendedLayout() throws Exception {
        Long layoutId = createLayout();

        mockMvc.perform(post("/api/layouts/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "layoutId": %d,
                                  "feedback": "책상을 조금 더 넓게 쓰고 싶어"
                                }
                                """.formatted(layoutId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.error").value(nullValue()))
                .andExpect(jsonPath("$.data.layoutId", notNullValue()))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.feedbackStatus").value("SUCCESS"))
                .andExpect(jsonPath("$.data.operationResults", hasSize(1)))
                .andExpect(jsonPath("$.data.operationResults[0].operationId").value("op-1"))
                .andExpect(jsonPath("$.data.operationResults[0].operationType").value("REPLACE_PRODUCT"))
                .andExpect(jsonPath("$.data.operationResults[0].status").value("APPLIED"))
                .andExpect(jsonPath("$.data.operationResults[0].resultFurnitureId", notNullValue()))
                .andExpect(jsonPath("$.data.operationResults[0].productId", notNullValue()))
                .andExpect(jsonPath("$.data.operationResults[0].variantId", notNullValue()))
                .andExpect(jsonPath("$.data.recommendedFurniture", notNullValue()))
                .andExpect(jsonPath("$.data.recommendedFurniture[?(@.type == 'desk')]").value(hasSize(1)))
                .andExpect(jsonPath("$.data.recommendedFurniture[?(@.type == 'desk')].width")
                        .value(hasItem(greaterThanOrEqualTo(1.4))))
                .andExpect(jsonPath("$.data.scoreSummary.totalScore", notNullValue()))
                .andExpect(jsonPath("$.data.validationResult.boundaryValid").value(true))
                .andExpect(jsonPath("$.data.validationResult.validationItems.length()").value(5))
                .andExpect(jsonPath("$.data.interpretedIntent.source").value("RULE_BASED"))
                .andExpect(jsonPath("$.data.interpretedIntent.version").value("2.0"))
                .andExpect(jsonPath("$.data.interpretedIntent.requestKind").value("DIRECT"))
                .andExpect(jsonPath("$.data.interpretedIntent.operations").value(hasItems("REPLACE_PRODUCT")))
                .andExpect(jsonPath("$.data.interpretedIntent.operationIds").value(hasItems("op-1")))
                .andExpect(jsonPath("$.data.interpretedIntent.rawIntent").value("LARGER_DESK"))
                .andExpect(jsonPath("$.data.interpretedIntent.targetFurniture").value("desk"))
                .andExpect(jsonPath("$.data.interpretedIntent.deskMinWidth").value(1.4))
                .andExpect(jsonPath("$.data.interpretedIntent.fallbackUsed").value(true))
                .andExpect(jsonPath("$.data.feedbackResult.applied").value(true))
                .andExpect(jsonPath("$.data.feedbackResult.operationsRequested").value(hasItems("REPLACE_PRODUCT")))
                .andExpect(jsonPath("$.data.feedbackResult.operationsApplied").value(hasItems("REPLACE_PRODUCT")));
    }

    @Test
    void feedback_withSmallestDesk_returnsProductFailureWithoutSnapshotOrCandidates() throws Exception {
        Long layoutId = createLayout();
        Layout layout = layoutRepository.findById(layoutId).orElseThrow();
        var product = com.roomfit.product.catalog.GeneratedFurnitureCatalog.get().products().stream()
                .filter(p -> "desk".equals(p.getType()))
                .min(java.util.Comparator.comparingDouble(p -> p.getWidth() * p.getDepth())).orElseThrow();
        Furniture smallest = new Furniture("desk-smallest", "desk", product.getName(), product.getWidth(), product.getDepth(), product.getHeight(),
                new Position(2.0, 2.0), 0, FurnitureStatus.RECOMMENDED,
                product.getProductId(), product.getStyleTags(), product.getVariantId());
        layout.setFurniture(new ArrayList<>(List.of(smallest)));
        layoutRepository.save(layout);

        mockMvc.perform(post("/api/layouts/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "layoutId": %d,
                                  "feedback": "책상을 더 작은 제품으로 바꿔줘"
                                }
                                """.formatted(layoutId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.layoutId").value(layoutId))
                .andExpect(jsonPath("$.data.feedbackStatus").value("FAILED"))
                .andExpect(jsonPath("$.data.feedbackResult.applied").value(false))
                .andExpect(jsonPath("$.data.feedbackResult.noChangeReason").value("NO_SMALLER_PRODUCT_AVAILABLE"))
                .andExpect(jsonPath("$.data.operationResults", hasSize(1)))
                .andExpect(jsonPath("$.data.operationResults[0].status").value("FAILED"))
                .andExpect(jsonPath("$.data.operationResults[0].reasonCode").value("NO_SMALLER_PRODUCT_AVAILABLE"))
                .andExpect(jsonPath("$.data.clarification").value(nullValue()))
                .andExpect(jsonPath("$.data.clarifications", hasSize(0)))
                .andExpect(jsonPath("$.data.recommendedFurniture[0].id").value("desk-smallest"))
                .andExpect(jsonPath("$.data.recommendedFurniture[0].productId").value(product.getProductId()));
    }

    @Test
    void feedback_withStoragePriority_returnsInterpretedIntent() throws Exception {
        Long layoutId = createLayout();

        mockMvc.perform(post("/api/layouts/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "layoutId": %d,
                                  "feedback": "수납 늘려줘"
                                }
                                """.formatted(layoutId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.recommendedFurniture[?(@.type == 'desk')].productId")
                        .value(hasItems("desk-storage-01")))
                .andExpect(jsonPath("$.data.recommendedFurniture[?(@.type == 'desk')].variantId")
                        .value(hasItems("desk-storage")))
                .andExpect(jsonPath("$.data.interpretedIntent.storagePriority").value("HIGH"));
    }

    @Test
    void feedback_withOpenSpacePriority_returnsInterpretedIntent() throws Exception {
        Long layoutId = createLayout();

        mockMvc.perform(post("/api/layouts/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "layoutId": %d,
                                  "feedback": "방이 넓어 보이게"
                                }
                                """.formatted(layoutId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.interpretedIntent.openSpacePriority").value("HIGH"));
    }

    @Test
    void feedback_withUnsupportedSentence_returnsUnsupportedFeedbackIntent() throws Exception {
        Long layoutId = createLayout();

        mockMvc.perform(post("/api/layouts/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "layoutId": %d,
                                  "feedback": "침대 색 바꿔줘"
                                }
                                """.formatted(layoutId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_FEEDBACK_INTENT"));
    }

    @Test
    void feedback_withUnknownLayout_returnsLayoutNotFound() throws Exception {
        mockMvc.perform(post("/api/layouts/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "layoutId": 99999,
                                  "feedback": "책상 더 크게"
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("LAYOUT_NOT_FOUND"));
    }

    @Test
    void feedback_removeFurniturePersistsArrayRemovalThroughConfirmAndRoomRead() throws Exception {
        Long layoutId = createLayout();

        String feedbackResponse = mockMvc.perform(post("/api/layouts/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "layoutId": %d,
                                  "feedback": "의자를 빼줘"
                                }
                                """.formatted(layoutId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.recommendedFurniture[?(@.type == 'chair')]").value(hasSize(0)))
                .andExpect(jsonPath("$.data.feedbackResult.operationsApplied").value(hasItems("REMOVE_FURNITURE")))
                .andReturn().getResponse().getContentAsString();

        Integer feedbackLayoutId = JsonPath.read(feedbackResponse, "$.data.layoutId");
        mockMvc.perform(post("/api/layouts/{layoutId}/confirm", feedbackLayoutId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.confirmed").value(true));

        mockMvc.perform(get("/api/rooms/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.furniture[?(@.type == 'chair')]").value(hasSize(0)));
    }

    @Test
    void feedback_addFurnitureKeepsExistingResponseShapeAndUsesCatalogMetadata() throws Exception {
        Long layoutId = createLayout();

        mockMvc.perform(post("/api/layouts/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "layoutId": %d,
                                  "feedback": "구석에 조명을 하나 추가해줘"
                                }
                                """.formatted(layoutId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.recommendedFurniture[?(@.id =~ /mood-lamp-feedback-.*/)]")
                        .value(hasSize(1)))
                .andExpect(jsonPath("$.data.recommendedFurniture[?(@.id =~ /mood-lamp-feedback-.*/)].type")
                        .value(hasItems("mood_lamp")))
                .andExpect(jsonPath("$.data.recommendedFurniture[?(@.id =~ /mood-lamp-feedback-.*/)].productId")
                        .value(hasItems("expansion-c088-01")))
                .andExpect(jsonPath("$.data.recommendedFurniture[?(@.id =~ /mood-lamp-feedback-.*/)].variantId")
                        .value(hasItems("expansion-c088")))
                .andExpect(jsonPath("$.data.interpretedIntent.operations").value(hasItems("ADD_FURNITURE")))
                .andExpect(jsonPath("$.data.feedbackResult.applied").value(true));
    }

    @Test
    void feedback_withDirectLeftMovePersistsOnlyALeftwardResultLayout() throws Exception {
        Long sourceLayoutId = createLayout();
        Layout source = layoutRepository.findById(sourceLayoutId).orElseThrow();
        Furniture sourceDesk = new Furniture("desk-left", "desk", "책상", 1.2, 0.6, 0.73,
                new Position(4.0, 2.0), 0, FurnitureStatus.EXISTING,
                "desk-compact-01", List.of("minimal"), "desk-compact");
        source.setFurniture(new ArrayList<>(List.of(sourceDesk)));
        layoutRepository.save(source);
        long layoutsBeforeFeedback = layoutRepository.count();

        String response = mockMvc.perform(post("/api/layouts/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "layoutId": %d,
                                  "feedback": "책상을 왼쪽으로 옮겨줘"
                                }
                                """.formatted(sourceLayoutId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.feedbackStatus").value("SUCCESS"))
                .andExpect(jsonPath("$.data.operationResults", hasSize(1)))
                .andExpect(jsonPath("$.data.operationResults[0].operationType").value("MOVE"))
                .andExpect(jsonPath("$.data.operationResults[0].status").value("APPLIED"))
                .andExpect(jsonPath("$.data.operationResults[0].targetFurnitureId").value("desk-left"))
                .andReturn().getResponse().getContentAsString();

        Integer resultLayoutId = JsonPath.read(response, "$.data.layoutId");
        Layout result = layoutRepository.findById(resultLayoutId.longValue()).orElseThrow();
        Furniture resultDesk = result.getFurniture().getFirst();
        Furniture unchangedSourceDesk = layoutRepository.findById(sourceLayoutId).orElseThrow().getFurniture().getFirst();

        assertThat(result.getId()).isNotEqualTo(sourceLayoutId);
        assertThat(resultDesk.getPosition().getX()).isLessThan(4.0);
        assertThat(unchangedSourceDesk.getPosition().getX()).isEqualTo(4.0);
        assertThat(layoutRepository.count()).isEqualTo(layoutsBeforeFeedback + 1);
    }

    @Test
    void feedback_withFloatingPointLeftBoundaryNoOpKeepsSourceLayout() throws Exception {
        Long sourceLayoutId = createLayout();
        Layout source = layoutRepository.findById(sourceLayoutId).orElseThrow();
        Furniture sourceDesk = new Furniture("desk-left-boundary", "desk", "책상", 1.2, 0.6, 0.73,
                new Position(0.68, 2.0), 0, FurnitureStatus.EXISTING);
        source.setFurniture(new ArrayList<>(List.of(sourceDesk)));
        layoutRepository.save(source);
        long layoutsBeforeFeedback = layoutRepository.count();

        mockMvc.perform(post("/api/layouts/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "layoutId": %d,
                                  "feedback": "책상을 왼쪽으로 옮겨줘"
                                }
                                """.formatted(sourceLayoutId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.layoutId").value(sourceLayoutId))
                .andExpect(jsonPath("$.data.feedbackStatus").value("FAILED"))
                .andExpect(jsonPath("$.data.feedbackResult.applied").value(false))
                .andExpect(jsonPath("$.data.feedbackResult.noChangeReason").value("NO_VALID_MOVE_PLACEMENT"))
                .andExpect(jsonPath("$.data.operationResults", hasSize(1)))
                .andExpect(jsonPath("$.data.operationResults[0].operationType").value("MOVE"))
                .andExpect(jsonPath("$.data.operationResults[0].status").value("FAILED"))
                .andExpect(jsonPath("$.data.operationResults[0].reasonCode").value("NO_VALID_MOVE_PLACEMENT"));

        assertThat(layoutRepository.count()).isEqualTo(layoutsBeforeFeedback);
        assertThat(layoutRepository.findById(sourceLayoutId).orElseThrow().getFurniture().getFirst()
                .getPosition().getX()).isEqualTo(0.68);
    }

    @Test
    void feedback_withFloatingPointRightBoundaryNoOpKeepsSourceLayout() throws Exception {
        Long sourceLayoutId = createLayout();
        Layout source = layoutRepository.findById(sourceLayoutId).orElseThrow();
        Room room = roomRepository.findById(source.getRoomId()).orElseThrow();
        Furniture prototype = new Furniture("desk-right-boundary", "desk", "책상", 1.2, 0.6, 0.73,
                new Position(room.getWidth() / 2.0, 2.0), 0, FurnitureStatus.EXISTING);
        double safeMaxX = FurnitureBoundary.clamp(room, new Position(room.getWidth() + 10, 2.0), prototype)
                .orElseThrow().getX();
        double oneUlpInsideBoundary = Math.nextDown(safeMaxX);
        Furniture sourceDesk = new Furniture("desk-right-boundary", "desk", "책상", 1.2, 0.6, 0.73,
                new Position(oneUlpInsideBoundary, 2.0), 0, FurnitureStatus.EXISTING);
        source.setFurniture(new ArrayList<>(List.of(sourceDesk)));
        layoutRepository.save(source);
        long layoutsBeforeFeedback = layoutRepository.count();

        mockMvc.perform(post("/api/layouts/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "layoutId": %d,
                                  "feedback": "책상을 오른쪽으로 옮겨줘"
                                }
                                """.formatted(sourceLayoutId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.layoutId").value(sourceLayoutId))
                .andExpect(jsonPath("$.data.feedbackStatus").value("FAILED"))
                .andExpect(jsonPath("$.data.feedbackResult.applied").value(false))
                .andExpect(jsonPath("$.data.feedbackResult.noChangeReason").value("NO_VALID_MOVE_PLACEMENT"))
                .andExpect(jsonPath("$.data.operationResults[0].status").value("FAILED"))
                .andExpect(jsonPath("$.data.operationResults[0].reasonCode").value("NO_VALID_MOVE_PLACEMENT"));

        assertThat(safeMaxX).isNotEqualTo(oneUlpInsideBoundary);
        assertThat(layoutRepository.count()).isEqualTo(layoutsBeforeFeedback);
        assertThat(layoutRepository.findById(sourceLayoutId).orElseThrow().getFurniture().getFirst()
                .getPosition().getX()).isEqualTo(oneUlpInsideBoundary);
    }

    @Test
    void feedback_withNoOpMoveAndRotateKeepsSourceLayoutAtomically() throws Exception {
        Long sourceLayoutId = createLayout();
        Layout source = layoutRepository.findById(sourceLayoutId).orElseThrow();
        Furniture sourceDesk = new Furniture("desk-composite-boundary", "desk", "책상", 1.2, 0.6, 0.73,
                new Position(0.68, 2.0), 0, FurnitureStatus.EXISTING);
        source.setFurniture(new ArrayList<>(List.of(sourceDesk)));
        layoutRepository.save(source);
        long layoutsBeforeFeedback = layoutRepository.count();

        mockMvc.perform(post("/api/layouts/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "layoutId": %d,
                                  "feedback": "책상을 왼쪽으로 옮기고 90도 회전해줘"
                                }
                                """.formatted(sourceLayoutId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.layoutId").value(sourceLayoutId))
                .andExpect(jsonPath("$.data.feedbackStatus").value("FAILED"))
                .andExpect(jsonPath("$.data.feedbackResult.applied").value(false))
                .andExpect(jsonPath("$.data.feedbackResult.noChangeReason").value("NO_VALID_MOVE_PLACEMENT"))
                .andExpect(jsonPath("$.data.operationResults", hasSize(2)))
                .andExpect(jsonPath("$.data.operationResults[0].operationType").value("MOVE"))
                .andExpect(jsonPath("$.data.operationResults[0].status").value("FAILED"))
                .andExpect(jsonPath("$.data.operationResults[0].reasonCode").value("NO_VALID_MOVE_PLACEMENT"))
                .andExpect(jsonPath("$.data.operationResults[1].operationType").value("ROTATE"))
                .andExpect(jsonPath("$.data.operationResults[1].status").value("SKIPPED_DEPENDENCY"))
                .andExpect(jsonPath("$.data.operationResults[1].reasonCode").value("DEPENDENCY_NOT_APPLIED"));

        assertThat(layoutRepository.count()).isEqualTo(layoutsBeforeFeedback);
        assertThat(layoutRepository.findById(sourceLayoutId).orElseThrow().getFurniture().getFirst())
                .satisfies(unchanged -> {
                    assertThat(unchanged.getPosition().getX()).isEqualTo(0.68);
                    assertThat(unchanged.getRotation()).isZero();
                });
    }

    @Test
    void feedback_withAmbiguousTarget_returnsStructuredClarificationWithoutSnapshot() throws Exception {
        Long layoutId = createLayout();
        Layout layout = layoutRepository.findById(layoutId).orElseThrow();
        List<Furniture> furniture = new ArrayList<>(layout.getFurniture());
        furniture.add(new Furniture("desk-second", "desk", "보조 책상", 1.0, 0.6, 0.73,
                new Position(3.5, 3.5), 0, FurnitureStatus.RECOMMENDED));
        layout.setFurniture(furniture);
        layoutRepository.save(layout);

        mockMvc.perform(post("/api/layouts/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "layoutId": %d,
                                  "feedback": "책상 더 크게"
                                }
                                """.formatted(layoutId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.layoutId").value(layoutId))
                .andExpect(jsonPath("$.data.feedbackStatus").value("NEEDS_CLARIFICATION"))
                .andExpect(jsonPath("$.data.operationResults", hasSize(0)))
                .andExpect(jsonPath("$.data.clarification.reasonCode").value("AMBIGUOUS_TARGET"))
                .andExpect(jsonPath("$.data.clarification.requiredField").value("targetFurnitureId"))
                .andExpect(jsonPath("$.data.clarification.candidates", hasSize(2)))
                .andExpect(jsonPath("$.data.clarification.candidates[0].furnitureId").value("desk-1"))
                .andExpect(jsonPath("$.data.clarification.candidates[1].furnitureId").value("desk-second"))
                .andExpect(jsonPath("$.data.clarification.candidates[0].label").value(not("desk-1")))
                .andExpect(jsonPath("$.data.clarification.candidates[1].label").value("보조 책상"));
    }

    @Test
    void feedback_withNonAmbiguousClarificationDoesNotExposeTargetChooser() throws Exception {
        Long layoutId = createLayout();
        Layout layout = layoutRepository.findById(layoutId).orElseThrow();
        layout.setFurniture(new ArrayList<>(List.of(
                new Furniture("desk-only", "desk", "책상", 1.2, 0.6, 0.73,
                        new Position(2, 2), 0, FurnitureStatus.EXISTING))));
        layoutRepository.save(layout);

        mockMvc.perform(post("/api/layouts/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "layoutId": %d,
                                  "feedback": "소파를 옮겨줘"
                                }
                                """.formatted(layoutId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.layoutId").value(layoutId))
                .andExpect(jsonPath("$.data.feedbackStatus").value("NEEDS_CLARIFICATION"))
                .andExpect(jsonPath("$.data.clarification.reasonCode").value("NEEDS_CLARIFICATION"))
                .andExpect(jsonPath("$.data.clarification.requiredField").value(nullValue()))
                .andExpect(jsonPath("$.data.clarification.candidates", hasSize(0)));
    }

    @Test
    void feedback_withSelectedFurnitureMovesExistingFurnitureToCornerWithoutAdding() throws Exception {
        Long layoutId = createLayout();
        Layout layout = layoutRepository.findById(layoutId).orElseThrow();
        Furniture selectedChair = new Furniture("selected-chair", "desk_chair", "선택한 의자", 0.5, 0.5, 0.8,
                new Position(2.5, 2.5), 0, FurnitureStatus.EXISTING,
                "desk-chair-basic-01", List.of("minimal"), "desk-chair-basic");
        layout.setFurniture(new ArrayList<>(List.of(selectedChair)));
        layoutRepository.save(layout);

        mockMvc.perform(post("/api/layouts/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "layoutId": %d,
                                  "feedback": "가구를 모서리에 배치해줘",
                                  "selectedFurnitureId": "selected-chair"
                                }
                                """.formatted(layoutId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.feedbackStatus").value("SUCCESS"))
                .andExpect(jsonPath("$.data.operationResults", hasSize(1)))
                .andExpect(jsonPath("$.data.operationResults[0].operationType").value("MOVE"))
                .andExpect(jsonPath("$.data.operationResults[0].status").value("APPLIED"))
                .andExpect(jsonPath("$.data.operationResults[0].resultFurnitureId").value("selected-chair"))
                .andExpect(jsonPath("$.data.recommendedFurniture", hasSize(1)))
                .andExpect(jsonPath("$.data.recommendedFurniture[0].id").value("selected-chair"))
                .andExpect(jsonPath("$.data.recommendedFurniture[0].productId").value("desk-chair-basic-01"))
                .andExpect(jsonPath("$.data.recommendedFurniture[0].variantId").value("desk-chair-basic"))
                .andExpect(jsonPath("$.data.validationResult.collisionFree").value(true))
                .andExpect(jsonPath("$.data.validationResult.boundaryValid").value(true));
    }

    @Test
    void selectedChairCompositeCreatesOneIdempotentDerivedLayout() throws Exception {
        Long sourceLayoutId = createLayout();
        Layout source = layoutRepository.findById(sourceLayoutId).orElseThrow();
        source.setFurniture(new ArrayList<>(List.of(
                new Furniture("chair-1", "desk_chair", "의자 1", 0.5, 0.5, 0.8,
                        new Position(1, 1), 0, FurnitureStatus.EXISTING),
                new Furniture("chair-2", "desk_chair", "의자 2", 0.5, 0.5, 0.8,
                        new Position(4, 4), 0, FurnitureStatus.EXISTING))));
        layoutRepository.save(source);

        mockMvc.perform(post("/api/layouts/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "layoutId": %d,
                                  "feedback": "의자를 삭제하고 협탁을 추가해줘"
                                }
                                """.formatted(sourceLayoutId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.layoutId").value(sourceLayoutId))
                .andExpect(jsonPath("$.data.feedbackStatus").value("NEEDS_CLARIFICATION"))
                .andExpect(jsonPath("$.data.operationResults", hasSize(0)))
                .andExpect(jsonPath("$.data.clarification.reasonCode").value("AMBIGUOUS_TARGET"))
                .andExpect(jsonPath("$.data.clarification.candidates", hasSize(2)));

        long layoutsBeforeSelection = layoutRepository.count();
        String selectedResponse = mockMvc.perform(post("/api/layouts/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "layoutId": %d,
                                  "feedback": "의자를 삭제하고 협탁을 추가해줘",
                                  "selectedFurnitureId": "chair-1"
                                }
                                """.formatted(sourceLayoutId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.feedbackStatus").value("SUCCESS"))
                .andExpect(jsonPath("$.data.operationResults", hasSize(2)))
                .andExpect(jsonPath("$.data.operationResults[0].operationType").value("REMOVE_FURNITURE"))
                .andExpect(jsonPath("$.data.operationResults[1].operationType").value("ADD_FURNITURE"))
                .andReturn().getResponse().getContentAsString();

        Integer resultLayoutId = JsonPath.read(selectedResponse, "$.data.layoutId");
        Layout result = layoutRepository.findById(resultLayoutId.longValue()).orElseThrow();
        assertThat(result.getId()).isNotEqualTo(sourceLayoutId);
        assertThat(layoutRepository.count()).isEqualTo(layoutsBeforeSelection + 1);
        assertThat(result.getFurniture()).extracting(Furniture::getId).contains("chair-2").doesNotContain("chair-1");
        assertThat(result.getFurniture()).filteredOn(item -> "nightstand".equals(item.getType())).hasSize(1);
        assertThat(source.getFurniture()).extracting(Furniture::getId).containsExactly("chair-1", "chair-2");

        String retryResponse = mockMvc.perform(post("/api/layouts/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "layoutId": %d,
                                  "feedback": "의자를 삭제하고 협탁을 추가해줘",
                                  "selectedFurnitureId": "chair-1"
                                }
                                """.formatted(sourceLayoutId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.feedbackStatus").value("SUCCESS"))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<Integer>read(retryResponse, "$.data.layoutId").longValue()).isEqualTo(result.getId());
        assertThat(layoutRepository.count()).isEqualTo(layoutsBeforeSelection + 1);
    }

    private Long createLayout() throws Exception {
        String contextResponse = mockMvc.perform(post("/api/agent/context")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "roomId": 1,
                                  "lifestyleGoal": "STUDY_FOCUSED",
                                  "designStyle": ["MINIMAL"],
                                  "requiredItems": ["desk", "chair"],
                                  "optionalItems": [],
                                  "selectedImageIds": [1],
                                  "selectedProductIds": ["desk-01", "chair-01"]
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        Integer contextId = JsonPath.read(contextResponse, "$.data.contextId");

        String layoutResponse = mockMvc.perform(post("/api/layouts/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "roomId": 1,
                                  "contextId": %d
                                }
                                """.formatted(contextId)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        Integer layoutId = JsonPath.read(layoutResponse, "$.data.layoutId");
        removeNewRecommendedFurniture(layoutId.longValue());
        return layoutId.longValue();
    }

    private void removeNewRecommendedFurniture(Long layoutId) {
        Layout layout = layoutRepository.findById(layoutId).orElseThrow();
        layout.setFurniture(new java.util.ArrayList<>(layout.getFurniture().stream()
                .filter(item -> item.getStatus() != FurnitureStatus.RECOMMENDED)
                .toList()));
        layoutRepository.save(layout);
    }
}
