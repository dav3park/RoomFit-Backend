package com.roomfit.placement;

import com.roomfit.product.catalog.OriginalCatalogFixture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.roomfit.agent.domain.AgentContext;
import com.roomfit.agent.domain.DesignStyle;
import com.roomfit.agent.domain.LifestyleGoal;
import com.roomfit.llm.LlmClient;
import com.roomfit.product.repository.MockProductRepository;
import com.roomfit.room.Furniture;
import com.roomfit.room.FurnitureStatus;
import com.roomfit.room.Opening;
import com.roomfit.room.Position;
import com.roomfit.room.Room;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmLayoutFeedbackMvpTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DeterministicFeedbackExecutor executor =
            new DeterministicFeedbackExecutor(new ValidationService(), OriginalCatalogFixture.repository());

    @Test
    void fakeProvider_isCalledAndMoveChangesPositionWithoutChangingProductLifecycle() {
        AtomicInteger calls = new AtomicInteger();
        LlmFeedbackPlanInterpreter interpreter = interpreter(prompt -> {
            calls.incrementAndGet();
            assertThat(prompt).contains("Never output x, z, coordinates, position, distanceMeters");
            assertThat(prompt).contains("The only executable operation types are MOVE, ROTATE, REPLACE_PRODUCT");
            assertThat(prompt).contains("ADD_FURNITURE, REMOVE_FURNITURE, and SWAP_FURNITURE");
            return planJson("desk-1", "desk", """
                    {"type":"MOVE","placement":{"relation":"RIGHT","magnitude":"MEDIUM"}}
                    """);
        });
        Furniture before = compactDesk();

        FeedbackPlan plan = interpreter.interpret("책상을 오른쪽으로 옮겨줘", room(6, 6), List.of(before), context());
        FeedbackExecution execution = executor.execute(plan, room(6, 6), List.of(before));
        Furniture after = execution.furniture().getFirst();

        assertThat(calls).hasValue(1);
        assertThat(execution.result().applied()).isTrue();
        assertThat(execution.result().source()).isEqualTo(FeedbackSource.LLM);
        assertThat(execution.result().fallbackUsed()).isFalse();
        assertThat(after.getPosition().getX()).isEqualTo(2.4);
        assertThat(after.getPosition().getZ()).isEqualTo(before.getPosition().getZ());
        assertBackendIdentityAndAppearancePreserved(before, after);
        assertValid(room(6, 6), execution.furniture());
    }

    @Test
    void rotateChangesFootprintRotationWithoutChangingProductLifecycle() {
        Furniture before = compactDesk();
        FeedbackExecution execution = executor.execute(plan("desk-1", "desk", new FeedbackOperation(
                "op-1", FeedbackOperationType.ROTATE, null,
                new FeedbackPlacement(null, null, FeedbackOrientation.QUARTER_TURN_CW), null, List.of())),
                room(6, 6), List.of(before));
        Furniture after = execution.furniture().getFirst();

        assertThat(execution.result().applied()).isTrue();
        assertThat(after.getRotation()).isEqualTo(90);
        assertBackendIdentityAndAppearancePreserved(before, after);
        assertValid(room(6, 6), execution.furniture());
    }

    @Test
    void widerDeskReplacesProductAndNeverReselectsCurrentProduct() {
        Furniture before = compactDesk();
        FeedbackExecution execution = executor.execute(plan("desk-1", "desk", widerDesk()), room(6, 6), List.of(before));
        Furniture after = execution.furniture().getFirst();

        assertThat(execution.result().applied()).isTrue();
        assertThat(after.getWidth()).isGreaterThan(before.getWidth());
        assertThat(after.getProductId()).isNotEqualTo(before.getProductId());
        assertThat(after.getVariantId()).isNotEqualTo(before.getVariantId());
        assertValid(room(6, 6), execution.furniture());
    }

    @Test
    void widestDeskReturnsExplicitNoLargerProductNoOp() {
        Furniture widest = new Furniture("desk-1", "desk", "미드센추리 글라스 책상", 1.75, 0.74, 0.812,
                new Position(2.0, 2.0), 0, FurnitureStatus.RECOMMENDED,
                "desk-midcentury-glass-01", List.of("midcentury", "modern"), "desk-midcentury-glass");

        FeedbackExecution execution = executor.execute(plan("desk-1", "desk", widerDesk()), room(6, 6), List.of(widest));

        assertThat(execution.result().applied()).isFalse();
        assertThat(execution.result().noChangeReason()).isEqualTo("NO_LARGER_PRODUCT_AVAILABLE");
        assertThat(execution.furniture()).containsExactly(widest);
    }

    @Test
    void smallerDeskReplacementAndSmallestDeskFailureUseExplicitContracts() {
        Furniture widest = midcenturyDesk();
        FeedbackExecution replaced = executor.execute(plan("desk-rec-1", "desk", smallerDesk()),
                room(6, 6), List.of(widest));
        Furniture smallest = compactDesk();
        FeedbackExecution unavailable = executor.execute(plan("desk-1", "desk", smallerDesk()),
                room(6, 6), List.of(smallest));

        assertThat(replaced.result().applied()).isTrue();
        assertThat(replaced.furniture().getFirst().getWidth()).isLessThan(widest.getWidth());
        assertThat(unavailable.result().applied()).isFalse();
        assertThat(unavailable.result().noChangeReason()).isEqualTo("NO_SMALLER_PRODUCT_AVAILABLE");
        assertThat(unavailable.furniture()).containsExactly(smallest);
    }

    @Test
    void storageReplacementUsesDedicatedStorageDeskOnly() {
        FeedbackExecution execution = executor.execute(plan("desk-1", "desk", storageDesk()), room(6, 6), List.of(compactDesk()));
        Furniture after = execution.furniture().getFirst();

        assertThat(execution.result().applied()).isTrue();
        assertThat(after.getProductId()).isEqualTo("desk-storage-01");
        assertThat(after.getVariantId()).isEqualTo("desk-storage");
        assertValid(room(6, 6), execution.furniture());
    }

    @Test
    void fakeProviderStorageReplacementKeepsStorageSeparateFromLargerConstraints() {
        LlmFeedbackPlanInterpreter interpreter = interpreter(prompt -> {
            assertThat(prompt).contains("storagePreferred");
            assertThat(prompt).contains("largerThanCurrent");
            return planJson("desk-rec-1", "desk", """
                    {"type":"REPLACE_PRODUCT","constraints":{"storageRequired":true}}
                    """);
        });
        Furniture before = midcenturyDesk();

        FeedbackPlan plan = interpreter.interpret("수납공간이 많은 책상으로 바꿔줘",
                room(6, 6), List.of(before), context());
        FeedbackReplaceConstraints constraints = plan.operations().getFirst().constraints();
        FeedbackExecution execution = executor.execute(plan, room(6, 6), List.of(before));
        Furniture after = execution.furniture().getFirst();

        assertThat(plan.source()).isEqualTo(FeedbackSource.LLM);
        assertThat(plan.fallbackUsed()).isFalse();
        assertThat(constraints.furnitureType()).isEqualTo("desk");
        assertThat(constraints.storagePreferred()).isTrue();
        assertThat(constraints.largerThanCurrent()).isFalse();
        assertThat(constraints.minWidth()).isNull();
        assertThat(execution.result().applied()).isTrue();
        assertThat(execution.result().source()).isEqualTo(FeedbackSource.LLM);
        assertThat(execution.result().fallbackUsed()).isFalse();
        assertThat(execution.result().operationsRequested()).containsExactly("REPLACE_PRODUCT");
        assertThat(execution.result().operationsApplied()).containsExactly("REPLACE_PRODUCT");
        assertThat(after.getProductId()).isEqualTo("desk-storage-01");
        assertThat(after.getVariantId()).isEqualTo("desk-storage");
        assertThat(after.getWidth()).isLessThan(before.getWidth());
        assertValid(room(6, 6), execution.furniture());
    }

    @Test
    void storageFeedbackDoesNotInheritHallucinatedLargerConstraints() {
        FeedbackPlan plan = interpreter(prompt -> planJson("desk-rec-1", "desk", """
                {"type":"REPLACE_PRODUCT","constraints":{
                  "storagePreferred":true,
                  "largerThanCurrent":true,
                  "minWidth":1.8
                }}
                """)).interpret("수납공간이 많은 책상으로 바꿔줘",
                room(6, 6), List.of(midcenturyDesk()), context());

        FeedbackReplaceConstraints constraints = plan.operations().getFirst().constraints();

        assertThat(constraints.storagePreferred()).isTrue();
        assertThat(constraints.largerThanCurrent()).isFalse();
        assertThat(constraints.minWidth()).isNull();
    }

    @Test
    void largerFeedbackDoesNotBecomeStorageReplacement() {
        FeedbackPlan plan = interpreter(prompt -> planJson("desk-1", "desk", """
                {"type":"REPLACE_PRODUCT","constraints":{"largerThanCurrent":true}}
                """)).interpret("더 넓은 책상으로 바꿔줘",
                room(6, 6), List.of(compactDesk()), context());

        FeedbackReplaceConstraints constraints = plan.operations().getFirst().constraints();

        assertThat(constraints.largerThanCurrent()).isTrue();
        assertThat(constraints.storagePreferred()).isFalse();
    }

    @Test
    void existingDeskWithoutProductMetadataCanBeReplacedWithStorageProduct() {
        Furniture existing = new Furniture("desk-1", "desk", "기존 책상", 1.2, 0.6, 0.72,
                new Position(2.0, 2.0), 0, FurnitureStatus.EXISTING);

        FeedbackExecution execution = executor.execute(plan("desk-1", "desk", storageDesk()),
                room(6, 6), List.of(existing));
        Furniture after = execution.furniture().getFirst();

        assertThat(execution.result().applied()).isTrue();
        assertThat(after.getStatus()).isEqualTo(FurnitureStatus.EXISTING);
        assertThat(after.getProductId()).isEqualTo("desk-storage-01");
        assertThat(after.getVariantId()).isEqualTo("desk-storage");
        assertValid(room(6, 6), execution.furniture());
    }

    @Test
    void storageProductReturnsExplicitAlreadyMatchesNoOp() {
        Furniture storage = storageFurniture();

        FeedbackExecution execution = executor.execute(plan("desk-1", "desk", storageDesk()),
                room(6, 6), List.of(storage));

        assertThat(execution.result().applied()).isFalse();
        assertThat(execution.result().noChangeReason()).isEqualTo("CURRENT_PRODUCT_ALREADY_MATCHES");
        assertThat(execution.furniture()).containsExactly(storage);
    }

    @Test
    void missingStorageProductReturnsNoMatchingProduct() {
        Furniture sofa = new Furniture("sofa-1", "sofa", "소파", 1.8, 0.8, 0.8,
                new Position(3.0, 3.0), 0, FurnitureStatus.EXISTING);
        FeedbackOperation storageSofa = new FeedbackOperation("op-1", FeedbackOperationType.REPLACE_PRODUCT,
                null, null, new FeedbackReplaceConstraints("sofa", false, null, List.of(), List.of(), true), List.of());

        FeedbackExecution execution = executor.execute(plan("sofa-1", "sofa", storageSofa),
                room(6, 6), List.of(sofa));

        assertThat(execution.result().applied()).isFalse();
        assertThat(execution.result().noChangeReason()).isEqualTo("NO_MATCHING_PRODUCT");
    }

    @Test
    void invalidReplaceConstraintsAreRejectedBeforeExecution() {
        FeedbackOperation invalid = new FeedbackOperation("op-1", FeedbackOperationType.REPLACE_PRODUCT,
                null, null, new FeedbackReplaceConstraints("", false, null, List.of(), List.of(), false), List.of());

        assertThatThrownBy(() -> executor.execute(plan("desk-1", "desk", invalid),
                room(6, 6), List.of(compactDesk())))
                .isInstanceOf(com.roomfit.common.CustomException.class);
    }

    @Test
    void storageReplacementNeverFallsBackToGeneralDeskWhenNoValidPlacementExists() {
        Room blockedRoom = roomWithDoorCoveringTheFloor();
        FeedbackExecution execution = executor.execute(plan("desk-1", "desk", storageDesk()), blockedRoom, List.of(compactDesk()));

        assertThat(execution.result().applied()).isFalse();
        assertThat(execution.result().noChangeReason()).isEqualTo("NO_VALID_BOUNDARY_PLACEMENT");
        assertThat(execution.result().summary()).isEqualTo("가구 전체가 방 안에 들어오는 위치를 찾지 못했습니다.");
        assertThat(execution.furniture().getFirst().getProductId()).isEqualTo("desk-compact-01");
    }

    @Test
    void malformedAndUnsupportedProviderResponsesFallBackAndClarificationDoesNotInventAnOperation() {
        FallbackFeedbackPlanInterpreter fallback = new FallbackFeedbackPlanInterpreter(
                Optional.of(interpreter(prompt -> "{ malformed")), new RuleBasedFeedbackPlanInterpreter());
        FeedbackPlan fallbackPlan = fallback.interpret("책상 더 크게", room(6, 6), List.of(compactDesk()), context());
        assertThat(fallbackPlan.source()).isEqualTo(FeedbackSource.RULE_BASED);
        assertThat(fallbackPlan.fallbackUsed()).isTrue();

        FallbackFeedbackPlanInterpreter missingConstraintsFallback = new FallbackFeedbackPlanInterpreter(
                Optional.of(interpreter(prompt -> planJson("desk-1", "desk", """
                        {"type":"REPLACE_PRODUCT"}
                        """))), new RuleBasedFeedbackPlanInterpreter());
        FeedbackPlan normalizedFallback = missingConstraintsFallback.interpret(
                "수납공간이 많은 책상으로 바꿔줘", room(6, 6), List.of(compactDesk()), context());
        assertThat(normalizedFallback.source()).isEqualTo(FeedbackSource.RULE_BASED);
        assertThat(normalizedFallback.fallbackUsed()).isTrue();
        assertThat(normalizedFallback.operations().getFirst().constraints().storagePreferred()).isTrue();

        FallbackFeedbackPlanInterpreter unsupportedFallback = new FallbackFeedbackPlanInterpreter(
                Optional.of(interpreter(prompt -> planJson("desk-1", "desk", """
                        {"type":"WARP_FURNITURE"}
                        """))), new RuleBasedFeedbackPlanInterpreter());
        FeedbackPlan unsupportedPlan = unsupportedFallback.interpret(
                "책상 더 크게", room(6, 6), List.of(compactDesk()), context());
        assertThat(unsupportedPlan.source()).isEqualTo(FeedbackSource.RULE_BASED);
        assertThat(unsupportedPlan.fallbackUsed()).isTrue();

        FeedbackPlan clarification = interpreter(prompt -> """
                {"version":"2.0","requestKind":"CLARIFICATION","operations":[],"goals":[],
                 "clarification":{"question":"어떤 가구를 옮길까요?"},"reason":"target is ambiguous"}
                """).interpret("옮겨줘", room(6, 6), List.of(compactDesk()), context());
        FeedbackExecution clarificationExecution = executor.execute(clarification, room(6, 6), List.of(compactDesk()));
        assertThat(clarificationExecution.result().applied()).isFalse();
        assertThat(clarificationExecution.result().noChangeReason()).isEqualTo("NEEDS_CLARIFICATION");
    }

    @Test
    void providerFailureFallsBackToRuleBasedPlan() {
        FallbackFeedbackPlanInterpreter fallback = new FallbackFeedbackPlanInterpreter(
                Optional.of(interpreter(prompt -> { throw new IllegalStateException("provider timeout"); })),
                new RuleBasedFeedbackPlanInterpreter());

        FeedbackPlan plan = fallback.interpret("수납 늘려줘", room(6, 6), List.of(compactDesk()), context());

        assertThat(plan.source()).isEqualTo(FeedbackSource.RULE_BASED);
        assertThat(plan.fallbackUsed()).isTrue();
        assertThat(plan.operations()).extracting(operation -> operation.type())
                .containsExactly(FeedbackOperationType.REPLACE_PRODUCT);
    }

    private LlmFeedbackPlanInterpreter interpreter(LlmClient client) {
        return new LlmFeedbackPlanInterpreter(client, objectMapper);
    }

    private FeedbackPlan plan(String furnitureId, String furnitureType, FeedbackOperation operation) {
        FeedbackOperation targeted = new FeedbackOperation(operation.operationId(), operation.type(),
                new FeedbackTargetSelector(furnitureId, furnitureType, ""), operation.placement(),
                operation.constraints(), operation.dependsOn());
        return new FeedbackPlan("2.0", FeedbackRequestKind.DIRECT, List.of(targeted), List.of(), null,
                "test", FeedbackSource.LLM, false);
    }

    private FeedbackOperation widerDesk() {
        return new FeedbackOperation("op-1", FeedbackOperationType.REPLACE_PRODUCT, null, null,
                new FeedbackReplaceConstraints("desk", true, null, List.of(), List.of(), false), List.of());
    }

    private FeedbackOperation smallerDesk() {
        return new FeedbackOperation("op-1", FeedbackOperationType.REPLACE_PRODUCT, null, null,
                new FeedbackReplaceConstraints("desk", false, true, null, List.of(), List.of(), false), List.of());
    }

    private FeedbackOperation storageDesk() {
        return new FeedbackOperation("op-1", FeedbackOperationType.REPLACE_PRODUCT, null, null,
                new FeedbackReplaceConstraints("desk", false, null, List.of(), List.of(), true), List.of());
    }

    private Furniture compactDesk() {
        return new Furniture("desk-1", "desk", "컴팩트 책상", 1.2, 0.6, 0.73,
                new Position(2.0, 2.0), 0, FurnitureStatus.RECOMMENDED,
                "desk-compact-01", List.of("minimal", "classic"), "desk-compact");
    }

    private Furniture midcenturyDesk() {
        return new Furniture("desk-rec-1", "desk", "미드센추리 글라스 책상", 1.75, 0.74, 0.812,
                new Position(2.3, 1.0), 0, FurnitureStatus.RECOMMENDED,
                "desk-midcentury-glass-01", List.of("midcentury", "modern"), "desk-midcentury-glass");
    }

    private Furniture storageFurniture() {
        return new Furniture("desk-1", "desk", "수납 결합 책상", 1.4, 0.62, 0.73,
                new Position(2.0, 2.0), 0, FurnitureStatus.RECOMMENDED,
                "desk-storage-01", List.of("natural", "classic"), "desk-storage");
    }

    private Room room(double width, double depth) {
        return new Room(null, width, depth, 2.4, "meter", List.of(), List.of());
    }

    private Room roomWithDoorCoveringTheFloor() {
        return new Room(null, 2.0, 0.7, 2.4, "meter",
                List.of(new Opening("door-1", "door", "south", 0, 2.0, 2.0, null)), List.of());
    }

    private AgentContext context() {
        return new AgentContext(1L, LifestyleGoal.STUDY_FOCUSED, List.of(DesignStyle.MINIMAL),
                List.of("desk"), List.of(), List.of(1L), List.of("desk-compact-01"), List.of("minimal"));
    }

    private void assertBackendIdentityAndAppearancePreserved(Furniture before, Furniture after) {
        assertThat(after.getId()).isEqualTo(before.getId());
        assertThat(after.getType()).isEqualTo(before.getType());
        assertThat(after.getLabel()).isEqualTo(before.getLabel());
        assertThat(after.getWidth()).isEqualTo(before.getWidth());
        assertThat(after.getDepth()).isEqualTo(before.getDepth());
        assertThat(after.getHeight()).isEqualTo(before.getHeight());
        assertThat(after.getStatus()).isEqualTo(before.getStatus());
        assertThat(after.getProductId()).isEqualTo(before.getProductId());
        assertThat(after.getVariantId()).isEqualTo(before.getVariantId());
        assertThat(after.getStyleTags()).containsExactlyElementsOf(before.getStyleTags());
    }

    private void assertValid(Room room, List<Furniture> furniture) {
        ValidationResult result = new ValidationService().validate(room, furniture);
        assertThat(result.isCollisionFree()).isTrue();
        assertThat(result.isBoundaryValid()).isTrue();
        assertThat(result.isDoorClearance()).isTrue();
        assertThat(result.isWindowClearance()).isTrue();
        assertThat(result.isPathSecured()).isTrue();
    }

    private String planJson(String furnitureId, String furnitureType, String operation) {
        String operationFields = operation.trim();
        operationFields = operationFields.substring(1, operationFields.length() - 1);
        return """
                {"version":"2.0","requestKind":"DIRECT","operations":[{"operationId":"op-1","target":{"furnitureId":"%s","furnitureType":"%s","labelKeyword":""},"dependsOn":[],%s}],"goals":[],"clarification":null,"reason":"test"}
                """.formatted(furnitureId, furnitureType, operationFields);
    }
}
