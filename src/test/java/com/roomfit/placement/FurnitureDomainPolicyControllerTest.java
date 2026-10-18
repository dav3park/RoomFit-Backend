package com.roomfit.placement;

import com.roomfit.agent.domain.AgentContext;
import com.roomfit.agent.domain.DesignStyle;
import com.roomfit.agent.domain.LifestyleGoal;
import com.roomfit.agent.repository.AgentContextRepository;
import com.roomfit.room.Furniture;
import com.roomfit.room.FurnitureStatus;
import com.roomfit.room.Position;
import com.roomfit.room.Room;
import com.roomfit.room.RoomRepository;
import com.roomfit.room.RoomSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "roomfit.llm.feedback.enabled=false",
        "roomfit.llm.api-key=",
        "roomfit.llm.base-url=",
        "roomfit.llm.model="
})
@AutoConfigureMockMvc
@Transactional
class FurnitureDomainPolicyControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private RoomRepository roomRepository;
    @Autowired
    private AgentContextRepository agentContextRepository;
    @Autowired
    private LayoutRepository layoutRepository;

    @MockitoSpyBean
    private PlacementService placementService;
    @MockitoSpyBean
    private FeedbackPlanInterpreter feedbackPlanInterpreter;
    @MockitoSpyBean
    private DeterministicFeedbackExecutor feedbackExecutor;

    @Test
    void initialRecommendationAllowsLoftBedWithoutSeparateDesk() throws Exception {
        TestState state = createState(List.of(), List.of("bed"));
        doReturn(successfulPlacement(List.of(loftBed())))
                .when(placementService).recommend(any(), any());

        mockMvc.perform(post("/api/layouts/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(recommendBody(state)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.recommendedFurniture[0].variantId").value("bed-loft-desk"));
    }

    @Test
    void initialRecommendationAllowsCanonicalDeskWithoutLoftBed() throws Exception {
        TestState state = createState(List.of(), List.of("desk"));
        doReturn(successfulPlacement(List.of(desk())))
                .when(placementService).recommend(any(), any());

        mockMvc.perform(post("/api/layouts/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(recommendBody(state)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.recommendedFurniture[0].type").value("desk"));
    }

    @Test
    void initialRecommendationRejectsCombinedFinalStateWithoutSavingSnapshot() throws Exception {
        TestState state = createState(List.of(), List.of("bed", "desk"));
        long layoutsBefore = layoutRepository.count();
        doReturn(successfulPlacement(List.of(loftBed(), desk())))
                .when(placementService).recommend(any(), any());

        expectDomainConflict(post("/api/layouts/recommend")
                .contentType(MediaType.APPLICATION_JSON)
                .content(recommendBody(state)));

        assertThat(layoutRepository.count()).isEqualTo(layoutsBefore);
    }

    @ParameterizedTest
    @MethodSource("additionConflictStates")
    void furnitureAdditionRejectsExistingAndNewConflictInEitherDirection(List<Furniture> before,
                                                                          List<String> requestedTypes,
                                                                          List<Furniture> after)
            throws Exception {
        TestState state = createState(before, requestedTypes);
        List<FurnitureSnapshot> savedBefore = snapshot(state.layoutId());
        reset(feedbackExecutor);
        doAnswer(invocation -> {
            FeedbackOperation operation = ((FeedbackPlan) invocation.getArgument(0)).operations().getFirst();
            FeedbackResult result = appliedExecution(after, operation.type().name()).result();
            String addedId = after.stream()
                    .filter(item -> operation.productRequirements().furnitureType().equals(item.getType()))
                    .findFirst().orElseThrow().getId();
            return new FeedbackExecution(after, result,
                    List.of(FeedbackOperationExecution.applied(operation, addedId)));
        }).when(feedbackExecutor).execute(any(), any(), anyList(), any(), anyInt());

        expectDomainConflict(post("/api/layouts/{layoutId}/furniture-additions", state.layoutId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"contextId\":%d}".formatted(state.contextId())));

        assertThat(snapshot(state.layoutId())).containsExactlyElementsOf(savedBefore);
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> additionConflictStates() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(
                        List.of(loftBed()), List.of("desk"), List.of(loftBed(), desk())),
                org.junit.jupiter.params.provider.Arguments.of(
                        List.of(desk()), List.of("bed"), List.of(desk(), loftBed())),
                org.junit.jupiter.params.provider.Arguments.of(
                        List.of(), List.of("bed", "desk"), List.of(loftBed(), desk()))
        );
    }

    @Test
    void directValidationAndSaveRejectConflictWithoutChangingLayout() throws Exception {
        TestState state = createState(List.of(loftBed(), desk()), List.of());
        List<FurnitureSnapshot> savedBefore = snapshot(state.layoutId());

        expectDomainConflict(post("/api/layouts/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validateBody(state.layoutId(), "RECOMMENDED")));
        expectDomainConflict(put("/api/layouts/{layoutId}", state.layoutId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateBody("RECOMMENDED")));

        assertThat(snapshot(state.layoutId())).containsExactlyElementsOf(savedBefore);
    }

    @Test
    void finalMultiOperationFeedbackConflictRollsBackEveryOperation() throws Exception {
        TestState state = createState(List.of(loftBed()), List.of());
        List<FurnitureSnapshot> savedBefore = snapshot(state.layoutId());
        long layoutCountBefore = layoutRepository.count();
        FeedbackPlan plan = compositeAdditionPlan();
        List<Furniture> finalState = List.of(loftBed(), plant(), desk());
        doReturn(plan).when(feedbackPlanInterpreter).interpret(anyString(), any(), anyList(), any());
        doReturn(appliedExecution(finalState, "ADD_FURNITURE", "ADD_FURNITURE"))
                .when(feedbackExecutor).execute(any(), any(), anyList(), any());

        expectDomainConflict(post("/api/layouts/feedback")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"layoutId\":%d,\"feedback\":\"식물과 책상을 추가해줘\"}"
                        .formatted(state.layoutId())));

        assertThat(layoutRepository.count()).isEqualTo(layoutCountBefore);
        assertThat(snapshot(state.layoutId())).containsExactlyElementsOf(savedBefore);
    }

    @Test
    void conflictingLegacyLayoutCannotBeConfirmedOrReconfirmed() throws Exception {
        TestState draft = createState(List.of(loftBed(), desk()), List.of());
        expectDomainConflict(post("/api/layouts/{layoutId}/confirm", draft.layoutId()));
        assertThat(layoutRepository.findById(draft.layoutId()).orElseThrow().isConfirmed()).isFalse();

        TestState confirmed = createState(List.of(loftBed(), desk()), List.of());
        Layout confirmedLayout = layoutRepository.findById(confirmed.layoutId()).orElseThrow();
        confirmedLayout.confirm();
        layoutRepository.save(confirmedLayout);

        expectDomainConflict(post("/api/layouts/{layoutId}/confirm", confirmed.layoutId()));
        assertThat(layoutRepository.findById(confirmed.layoutId()).orElseThrow().isConfirmed()).isTrue();
    }

    @Test
    void deletingDeskFromConflictThenSavingAndConfirmingSucceeds() throws Exception {
        TestState state = createState(List.of(loftBed(), desk()), List.of());

        mockMvc.perform(put("/api/layouts/{layoutId}", state.layoutId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("DELETED")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recommendedFurniture[?(@.id == 'desk-1')].status")
                        .value(org.hamcrest.Matchers.hasItem("DELETED")));

        mockMvc.perform(post("/api/layouts/{layoutId}/confirm", state.layoutId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.confirmed").value(true));

        assertThat(roomRepository.findById(state.roomId()).orElseThrow().getFurniture())
                .anySatisfy(item -> {
                    assertThat(item.getId()).isEqualTo("desk-1");
                    assertThat(item.getStatus()).isEqualTo(FurnitureStatus.DELETED);
                });
    }

    private void expectDomainConflict(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value((Object) null))
                .andExpect(jsonPath("$.error.code").value("FURNITURE_DOMAIN_CONFLICT"))
                .andExpect(jsonPath("$.error.message")
                        .value("로프트 침대와 일반 책상은 동시에 배치할 수 없습니다."));
    }

    private TestState createState(List<Furniture> furniture, List<String> requestedTypes) {
        Room room = roomRepository.save(new Room(null, "Domain policy " + java.util.UUID.randomUUID(),
                8.0, 8.0, 2.7, "meter", List.of(), List.of(), furniture,
                RoomSource.ROOMPLAN, LocalDateTime.now(), null));
        AgentContext context = agentContextRepository.save(new AgentContext(
                room.getId(), LifestyleGoal.STUDY_FOCUSED, List.of(DesignStyle.MINIMAL),
                requestedTypes, List.of(), List.of(1L), List.of(), List.of("minimal")));
        Layout layout = layoutRepository.save(new Layout(room.getId(), context.getId(), deepCopy(furniture)));
        return new TestState(room.getId(), context.getId(), layout.getId());
    }

    private PlacementResult successfulPlacement(List<Furniture> furniture) {
        return new PlacementResult(RecommendationStatus.SUCCESS, furniture, ScoreSummary.defaultSummary(),
                furniture.size(), furniture.size(), List.of(), RecommendationExecutionStatus.SUCCESS,
                null, "추천 가구를 배치했습니다.");
    }

    private FeedbackExecution appliedExecution(List<Furniture> furniture, String... operations) {
        List<String> appliedOperations = java.util.Arrays.asList(operations);
        return new FeedbackExecution(furniture, new FeedbackResult(true, FeedbackSource.RULE_BASED, true,
                "test", appliedOperations, appliedOperations, null));
    }

    private FeedbackPlan compositeAdditionPlan() {
        FeedbackOperation plant = addOperation("op-1", "plant", List.of());
        FeedbackOperation desk = addOperation("op-2", "desk", List.of("op-1"));
        return new FeedbackPlan("2.0", FeedbackRequestKind.COMPOSITE, List.of(plant, desk), List.of(),
                null, "test", FeedbackSource.RULE_BASED, true);
    }

    private FeedbackOperation addOperation(String id, String type, List<String> dependsOn) {
        return new FeedbackOperation(id, FeedbackOperationType.ADD_FURNITURE,
                new FeedbackTargetSelector("", type, ""), null,
                new FeedbackPlacement(FeedbackRelation.NEAR_WALL, null, null, null), null,
                new FeedbackProductRequirements(type, FeedbackSizePreference.ANY, false, List.of()),
                null, dependsOn);
    }

    private String recommendBody(TestState state) {
        return "{\"roomId\":%d,\"contextId\":%d}".formatted(state.roomId(), state.contextId());
    }

    private String updateBody(String deskStatus) {
        return """
                {
                  "furniture": [
                    { "id": "loft-1", "position": { "x": 2.0, "z": 2.0 }, "rotation": 0, "status": "RECOMMENDED" },
                    { "id": "desk-1", "position": { "x": 5.0, "z": 5.0 }, "rotation": 0, "status": "%s" }
                  ]
                }
                """.formatted(deskStatus);
    }

    private String validateBody(Long layoutId, String deskStatus) {
        return """
                {
                  "layoutId": %d,
                  "furniture": [
                    { "id": "loft-1", "position": { "x": 2.0, "z": 2.0 }, "rotation": 0, "status": "RECOMMENDED" },
                    { "id": "desk-1", "position": { "x": 5.0, "z": 5.0 }, "rotation": 0, "status": "%s" }
                  ]
                }
                """.formatted(layoutId, deskStatus);
    }

    private List<FurnitureSnapshot> snapshot(Long layoutId) {
        return layoutRepository.findById(layoutId).orElseThrow().getFurniture().stream()
                .map(FurnitureSnapshot::from)
                .toList();
    }

    private static List<Furniture> deepCopy(List<Furniture> furniture) {
        return furniture.stream().map(item -> new Furniture(item.getId(), item.getType(), item.getLabel(),
                        item.getWidth(), item.getDepth(), item.getHeight(),
                        new Position(item.getPosition().getX(), item.getPosition().getZ()), item.getRotation(),
                        item.getStatus(), item.getProductId(), item.getStyleTags(), item.getVariantId()))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    private static Furniture loftBed() {
        return FurnitureDomainPolicyTest.loftBed();
    }

    private static Furniture desk() {
        return FurnitureDomainPolicyTest.desk(FurnitureStatus.RECOMMENDED);
    }

    private static Furniture plant() {
        return new Furniture("plant-1", "plant", "식물", 0.4, 0.4, 0.8,
                new Position(6.0, 2.0), 0, FurnitureStatus.RECOMMENDED,
                "plant-corner-01", List.of(), "plant-corner");
    }

    private record TestState(Long roomId, Long contextId, Long layoutId) {
    }

    private record FurnitureSnapshot(String id, String type, String label,
                                     double width, double depth, double height,
                                     String productId, String variantId, List<String> styleTags,
                                     double x, double z, double rotation, FurnitureStatus status) {
        static FurnitureSnapshot from(Furniture furniture) {
            return new FurnitureSnapshot(furniture.getId(), furniture.getType(), furniture.getLabel(),
                    furniture.getWidth(), furniture.getDepth(), furniture.getHeight(),
                    furniture.getProductId(), furniture.getVariantId(), furniture.getStyleTags(),
                    furniture.getPosition().getX(), furniture.getPosition().getZ(),
                    furniture.getRotation(), furniture.getStatus());
        }
    }
}
