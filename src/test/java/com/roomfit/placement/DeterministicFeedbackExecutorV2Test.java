package com.roomfit.placement;

import com.roomfit.product.catalog.OriginalCatalogFixture;

import com.roomfit.product.repository.MockProductRepository;
import com.roomfit.room.Furniture;
import com.roomfit.room.FurnitureBoundary;
import com.roomfit.room.FurnitureStatus;
import com.roomfit.room.Position;
import com.roomfit.room.Room;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DeterministicFeedbackExecutorV2Test {

    private final DeterministicFeedbackExecutor executor =
            new DeterministicFeedbackExecutor(new ValidationService(), OriginalCatalogFixture.repository());

    @ParameterizedTest
    @CsvSource({
            "SMALL,2.2",
            "MEDIUM,2.4",
            "LARGE,2.6"
    })
    void semanticMoveMagnitudeUsesDeterministicBackendDistance(FeedbackMagnitude magnitude, double expectedX) {
        Furniture before = desk("desk-1", 2, 2, 0);

        FeedbackExecution execution = executor.execute(direct(move("op-1",
                new FeedbackTargetSelector("desk-1", "desk", ""), FeedbackRelation.RIGHT, magnitude)),
                room(6, 6), List.of(before));

        assertThat(execution.result().applied()).isTrue();
        assertThat(execution.furniture().getFirst().getPosition().getX()).isEqualTo(expectedX);
    }

    @ParameterizedTest
    @CsvSource({
            "'책상을 왼쪽으로 옮겨줘',desk,-1",
            "'책상을 오른쪽으로 옮겨줘',desk,1",
            "'의자를 좌측으로 옮겨줘',desk_chair,-1",
            "'의자를 우측으로 옮겨줘',desk_chair,1",
            "'침대를 왼편으로 옮겨줘',bed,-1",
            "'침대를 오른편으로 옮겨줘',bed,1"
    })
    void parserProducedHorizontalMoveAliasesMatchTheRequestedCoordinateDirection(
            String feedback, String furnitureType, int direction) {
        Furniture before = new Furniture(furnitureType + "-1", furnitureType, furnitureType,
                0.8, 0.5, 0.8, new Position(3, 3), 0, FurnitureStatus.EXISTING);
        Room room = room(6, 6);
        FeedbackPlan plan = new RuleBasedFeedbackPlanInterpreter().interpret(
                feedback, room, List.of(before), null);

        FeedbackExecution execution = executor.execute(plan, room, List.of(before));

        assertThat(execution.result().applied()).as(feedback).isTrue();
        assertThat(execution.furniture().getFirst().getPosition().getX()).as(feedback)
                .isEqualTo(3.0 + direction * FeedbackPlacementPolicy.movementDistance(FeedbackMagnitude.MEDIUM));
    }

    @ParameterizedTest
    @CsvSource({
            "QUARTER_TURN_CW,90",
            "QUARTER_TURN_CCW,270",
            "HALF_TURN,180"
    })
    void semanticOrientationUsesDeterministicBackendRotation(FeedbackOrientation orientation, double expectedRotation) {
        Furniture before = desk("desk-1", 2, 2, 0);

        FeedbackExecution execution = executor.execute(direct(rotate("op-1",
                new FeedbackTargetSelector("desk-1", "desk", ""), orientation, List.of())),
                room(6, 6), List.of(before));

        assertThat(execution.result().applied()).isTrue();
        assertThat(execution.furniture().getFirst().getRotation()).isEqualTo(expectedRotation);
    }

    @Test
    void alignWithWallTriesDeterministicCardinalCandidates() {
        Furniture before = desk("desk-1", 2, 2, 45);

        FeedbackExecution execution = executor.execute(direct(rotate("op-1",
                new FeedbackTargetSelector("desk-1", "desk", ""), FeedbackOrientation.ALIGN_WITH_WALL, List.of())),
                room(6, 6), List.of(before));

        assertThat(execution.result().applied()).isTrue();
        assertThat(execution.furniture().getFirst().getRotation()).isEqualTo(0);
    }

    @Test
    void compositeOperationsApplyInOrderToLatestSnapshotWithoutMutatingInput() {
        Furniture before = desk("desk-1", 2, 2, 0);
        FeedbackTargetSelector target = new FeedbackTargetSelector("", "desk", "");
        FeedbackOperation move = move("op-1", target, FeedbackRelation.RIGHT, FeedbackMagnitude.MEDIUM);
        FeedbackOperation rotate = rotate("op-2", target, FeedbackOrientation.QUARTER_TURN_CW, List.of("op-1"));
        FeedbackPlan plan = new FeedbackPlan("2.0", FeedbackRequestKind.COMPOSITE,
                List.of(move, rotate), List.of(), null, "move and rotate", FeedbackSource.LLM, false);

        FeedbackExecution execution = executor.execute(plan, room(6, 6), List.of(before));
        Furniture after = execution.furniture().getFirst();

        assertThat(execution.result().operationsApplied()).containsExactly("MOVE", "ROTATE");
        assertThat(after.getPosition().getX()).isEqualTo(2.4);
        assertThat(after.getRotation()).isEqualTo(90);
        assertThat(before.getPosition().getX()).isEqualTo(2);
        assertThat(before.getRotation()).isEqualTo(0);
    }

    @Test
    void selectedCompositeRemoveAndAddChangesOnlyTheSelectedChairAtomically() {
        List<Furniture> before = List.of(
                new Furniture("chair-1", "desk_chair", "의자 1", 0.5, 0.5, 0.8,
                        new Position(1, 1), 0, FurnitureStatus.EXISTING),
                new Furniture("chair-2", "desk_chair", "의자 2", 0.5, 0.5, 0.8,
                        new Position(4, 4), 0, FurnitureStatus.EXISTING));
        Room room = room(6, 6);
        RuleBasedFeedbackPlanInterpreter interpreter = new RuleBasedFeedbackPlanInterpreter();

        FeedbackPlan noSelection = interpreter.interpret("의자를 삭제하고 협탁을 추가해줘", room, before, null);
        FeedbackExecution noSelectionExecution = executor.execute(noSelection, room, before);
        assertThat(noSelectionExecution.result().applied()).isFalse();
        assertThat(noSelectionExecution.furniture()).containsExactlyElementsOf(before);

        for (String selectedId : List.of("chair-1", "chair-2")) {
            FeedbackPlan selected = interpreter.interpret("의자를 삭제하고 협탁을 추가해줘",
                    room, before, null, selectedId);
            FeedbackExecution execution = executor.execute(selected, room, before);

            assertThat(execution.result().operationsApplied()).containsExactly("REMOVE_FURNITURE", "ADD_FURNITURE");
            assertThat(execution.furniture()).extracting(Furniture::getId).doesNotContain(selectedId);
            assertThat(execution.furniture()).extracting(Furniture::getId)
                    .contains(selectedId.equals("chair-1") ? "chair-2" : "chair-1");
            assertThat(execution.furniture()).filteredOn(item -> "nightstand".equals(item.getType())).hasSize(1);
        }
    }

    @Test
    void parserProducedMoveAndRotatePlanAppliesBothOperations() {
        Furniture before = new Furniture("bed-1", "bed", "침대", 1.0, 1.0, 0.6,
                new Position(3, 3), 0, FurnitureStatus.EXISTING);
        FeedbackPlan plan = new RuleBasedFeedbackPlanInterpreter().interpret(
                "침대를 모서리로 옮기고 90도 회전해줘", room(6, 6), List.of(before), null);

        FeedbackExecution execution = executor.execute(plan, room(6, 6), List.of(before));

        assertThat(execution.result().operationsApplied()).containsExactly("MOVE", "ROTATE");
        assertThat(execution.furniture().getFirst().getRotation()).isEqualTo(90);
    }

    @Test
    void semanticBindingKeepsSwapTargetTypeAndMovesOnlyThatTargetRelativeToReference() {
        Furniture bookshelf = new Furniture("bookshelf-1", "bookshelf", "책장", 0.8, 0.4, 1.2,
                new Position(1.5, 1.5), 0, FurnitureStatus.EXISTING, "legacy-bookshelf", List.of(), null);
        Furniture desk = desk("desk-1", 4, 3, 0);
        FeedbackPlan plan = new RuleBasedFeedbackPlanInterpreter().interpret(
                "책장을 다른 디자인으로 바꾸고 책상 우측으로 옮겨줘",
                room(8, 6), List.of(bookshelf, desk), null);

        FeedbackExecution execution = executor.execute(plan, room(8, 6), List.of(bookshelf, desk));

        assertThat(execution.result().operationsApplied()).containsExactly("SWAP_FURNITURE", "MOVE");
        Furniture changed = execution.furniture().stream()
                .filter(item -> item.getId().equals("bookshelf-1")).findFirst().orElseThrow();
        Furniture unchangedDesk = execution.furniture().stream()
                .filter(item -> item.getId().equals("desk-1")).findFirst().orElseThrow();
        assertThat(changed.getType()).isEqualTo("bookshelf");
        assertThat(changed.getPosition().getX()).isGreaterThan(unchangedDesk.getPosition().getX());
        assertThat(unchangedDesk.getPosition().getX()).isEqualTo(desk.getPosition().getX());
        assertThat(unchangedDesk.getPosition().getZ()).isEqualTo(desk.getPosition().getZ());
    }

    @Test
    void furnitureIdTakesPriorityWhenMultipleFurnitureShareTheSameType() {
        Furniture first = desk("desk-1", 1, 1, 0);
        Furniture second = desk("desk-2", 4, 4, 0);

        FeedbackExecution execution = executor.execute(direct(move("op-1",
                new FeedbackTargetSelector("desk-2", "desk", ""), FeedbackRelation.RIGHT, FeedbackMagnitude.SMALL)),
                room(6, 6), List.of(first, second));

        assertThat(execution.furniture().get(0).getPosition().getX()).isEqualTo(1);
        assertThat(execution.furniture().get(1).getPosition().getX()).isEqualTo(4.2);
    }

    @Test
    void uniqueFurnitureTypeResolvesWithoutFurnitureId() {
        Furniture desk = desk("desk-1", 2, 2, 0);
        Furniture chair = new Furniture("chair-1", "chair", "의자", 0.5, 0.5, 0.8,
                new Position(4, 4), 0, FurnitureStatus.EXISTING);

        FeedbackExecution execution = executor.execute(direct(move("op-1",
                new FeedbackTargetSelector("", "desk", ""), FeedbackRelation.LEFT, FeedbackMagnitude.SMALL)),
                room(6, 6), List.of(desk, chair));

        assertThat(execution.result().applied()).isTrue();
        assertThat(execution.furniture().getFirst().getPosition().getX()).isEqualTo(1.8);
    }

    @Test
    void blockedLeftMoveNeverFallsBackToTheOppositeDirection() {
        Room room = room(3, 4);
        Furniture prototype = desk("desk-1", 1.5, 2, 0);
        double safeMinX = FurnitureBoundary.clamp(room, new Position(-10, 2), prototype)
                .orElseThrow().getX();
        Furniture before = desk("desk-1", safeMinX, 2, 0);
        FeedbackPlan plan = new RuleBasedFeedbackPlanInterpreter().interpret(
                "책상을 왼쪽으로 옮겨줘", room, List.of(before), null);

        FeedbackExecution execution = executor.execute(plan, room, List.of(before));

        assertThat(execution.result().applied()).isFalse();
        assertThat(execution.result().noChangeReason()).isEqualTo("NO_VALID_MOVE_PLACEMENT");
        assertThat(execution.furniture()).containsExactly(before);
        assertThat(execution.furniture().getFirst().getPosition().getX()).isEqualTo(safeMinX);
    }

    @Test
    void floatingPointLeftBoundaryNoOpIsNotApplied() {
        Room room = room(3, 4);
        Furniture before = boundaryDesk("desk-1", 0.68, 2, 0);

        FeedbackExecution execution = executor.execute(direct(move("op-1",
                new FeedbackTargetSelector("desk-1", "desk", ""),
                FeedbackRelation.LEFT, FeedbackMagnitude.MEDIUM)), room, List.of(before));

        assertThat(execution.result().applied()).isFalse();
        assertThat(execution.result().noChangeReason()).isEqualTo("NO_VALID_MOVE_PLACEMENT");
        assertThat(execution.furniture()).containsExactly(before);
        assertThat(execution.operationResults()).singleElement().satisfies(result -> {
            assertThat(result.status()).isEqualTo(FeedbackOperationExecution.Status.FAILED);
            assertThat(result.reasonCode()).isEqualTo("NO_VALID_MOVE_PLACEMENT");
        });
    }

    @Test
    void floatingPointRightBoundaryNoOpIsNotApplied() {
        Room room = room(3, 4);
        Furniture prototype = desk("desk-1", 1.5, 2, 0);
        double safeMaxX = FurnitureBoundary.clamp(room, new Position(10, 2), prototype)
                .orElseThrow().getX();
        double oneUlpInsideBoundary = Math.nextDown(safeMaxX);
        Furniture before = desk("desk-1", oneUlpInsideBoundary, 2, 0);

        FeedbackExecution execution = executor.execute(direct(move("op-1",
                new FeedbackTargetSelector("desk-1", "desk", ""),
                FeedbackRelation.RIGHT, FeedbackMagnitude.MEDIUM)), room, List.of(before));

        assertThat(safeMaxX).isNotEqualTo(oneUlpInsideBoundary);
        assertThat(safeMaxX - oneUlpInsideBoundary).isLessThan(1.0e-9);
        assertThat(execution.result().applied()).isFalse();
        assertThat(execution.result().noChangeReason()).isEqualTo("NO_VALID_MOVE_PLACEMENT");
        assertThat(execution.furniture()).containsExactly(before);
    }

    @Test
    void movementWithinMetricToleranceUsesTheSameNoOpPolicyAsPositionComparison() {
        Room room = room(3, 4);
        Furniture prototype = desk("desk-1", 1.5, 2, 0);
        double safeMinX = FurnitureBoundary.clamp(room, new Position(-10, 2), prototype)
                .orElseThrow().getX();
        Furniture before = desk("desk-1", safeMinX + 5.0e-10, 2, 0);

        FeedbackExecution execution = executor.execute(direct(move("op-1",
                new FeedbackTargetSelector("desk-1", "desk", ""),
                FeedbackRelation.LEFT, FeedbackMagnitude.MEDIUM)), room, List.of(before));

        assertThat(execution.result().applied()).isFalse();
        assertThat(execution.result().noChangeReason()).isEqualTo("NO_VALID_MOVE_PLACEMENT");
        assertThat(execution.furniture()).containsExactly(before);
    }

    @Test
    void movementBeyondMetricToleranceRemainsApplied() {
        Room room = room(3, 4);
        Furniture prototype = desk("desk-1", 1.5, 2, 0);
        double safeMinX = FurnitureBoundary.clamp(room, new Position(-10, 2), prototype)
                .orElseThrow().getX();
        Furniture before = desk("desk-1", safeMinX + 2.0e-9, 2, 0);

        FeedbackExecution execution = executor.execute(direct(move("op-1",
                new FeedbackTargetSelector("desk-1", "desk", ""),
                FeedbackRelation.LEFT, FeedbackMagnitude.MEDIUM)), room, List.of(before));

        assertThat(execution.result().applied()).isTrue();
        assertThat(execution.furniture().getFirst().getPosition().getX()).isEqualTo(safeMinX);
        assertThat(execution.operationResults()).singleElement().satisfies(result ->
                assertThat(result.status()).isEqualTo(FeedbackOperationExecution.Status.APPLIED));
    }

    @Test
    void noOpMovePreventsCompositeRotationFromBeingStored() {
        Room room = room(3, 4);
        Furniture before = boundaryDesk("desk-1", 0.68, 2, 0);
        FeedbackTargetSelector target = new FeedbackTargetSelector("desk-1", "desk", "");
        FeedbackPlan plan = new FeedbackPlan("2.0", FeedbackRequestKind.COMPOSITE,
                List.of(
                        move("op-1", target, FeedbackRelation.LEFT, FeedbackMagnitude.MEDIUM),
                        rotate("op-2", target, FeedbackOrientation.QUARTER_TURN_CW, List.of("op-1"))),
                List.of(), null, "move then rotate", FeedbackSource.LLM, false);

        FeedbackExecution execution = executor.execute(plan, room, List.of(before));

        assertThat(execution.result().applied()).isFalse();
        assertThat(execution.result().noChangeReason()).isEqualTo("NO_VALID_MOVE_PLACEMENT");
        assertThat(execution.furniture()).containsExactly(before);
        assertThat(execution.furniture().getFirst().getRotation()).isZero();
        assertThat(execution.operationResults()).singleElement().satisfies(result -> {
            assertThat(result.type()).isEqualTo(FeedbackOperationType.MOVE);
            assertThat(result.status()).isEqualTo(FeedbackOperationExecution.Status.FAILED);
        });
    }

    @Test
    void noOpMoveRollsBackAnEarlierCompositeSwap() {
        Room room = room(8, 8);
        Furniture bed = new Furniture("bed-1", "bed", "침대", 0.94, 2.06, 0.85,
                new Position(4, 4), 0, FurnitureStatus.EXISTING,
                "bed-fabric-headboard-01", List.of("natural", "minimal"), "bed-fabric-headboard");
        Furniture desk = boundaryDesk("desk-1", 0.68, 6, 0);
        List<Furniture> before = List.of(bed, desk);
        FeedbackOperation swap = new FeedbackOperation("op-1", FeedbackOperationType.SWAP_FURNITURE,
                new FeedbackTargetSelector("bed-1", "bed", ""), null, null, null, null,
                new FeedbackProductRequirements("bed", FeedbackSizePreference.ANY, false, List.of()), List.of());
        FeedbackOperation move = new FeedbackOperation("op-2", FeedbackOperationType.MOVE,
                new FeedbackTargetSelector("desk-1", "desk", ""),
                new FeedbackPlacement(FeedbackRelation.LEFT, FeedbackMagnitude.MEDIUM, null),
                null, List.of("op-1"));
        FeedbackPlan plan = new FeedbackPlan("2.0", FeedbackRequestKind.COMPOSITE,
                List.of(swap, move), List.of(), null, "swap then move", FeedbackSource.LLM, false);

        FeedbackExecution execution = executor.execute(plan, room, before);

        assertThat(plan.operations()).extracting(FeedbackOperation::type)
                .containsExactly(FeedbackOperationType.SWAP_FURNITURE, FeedbackOperationType.MOVE);
        assertThat(execution.result().applied()).isFalse();
        assertThat(execution.result().noChangeReason()).isEqualTo("NO_VALID_MOVE_PLACEMENT");
        assertThat(execution.furniture()).containsExactlyElementsOf(before);
        assertThat(execution.operationResults()).hasSize(2);
        assertThat(execution.operationResults().get(0)).satisfies(result -> {
            assertThat(result.type()).isEqualTo(FeedbackOperationType.SWAP_FURNITURE);
            assertThat(result.status()).isEqualTo(FeedbackOperationExecution.Status.FAILED);
            assertThat(result.reasonCode()).isEqualTo("ATOMIC_ROLLBACK");
        });
        assertThat(execution.operationResults().get(1)).satisfies(result -> {
            assertThat(result.type()).isEqualTo(FeedbackOperationType.MOVE);
            assertThat(result.status()).isEqualTo(FeedbackOperationExecution.Status.FAILED);
            assertThat(result.reasonCode()).isEqualTo("NO_VALID_MOVE_PLACEMENT");
        });
    }

    @Test
    void ambiguousFurnitureTypeDoesNotSelectTheFirstFurniture() {
        List<Furniture> before = List.of(desk("desk-1", 1, 1, 0), desk("desk-2", 4, 4, 0));

        FeedbackExecution execution = executor.execute(direct(move("op-1",
                new FeedbackTargetSelector("", "desk", ""), FeedbackRelation.RIGHT, FeedbackMagnitude.SMALL)),
                room(6, 6), before);

        assertThat(execution.result().applied()).isFalse();
        assertThat(execution.result().noChangeReason()).isEqualTo("AMBIGUOUS_TARGET");
        assertThat(execution.furniture()).containsExactlyElementsOf(before);
    }

    @Test
    void invalidMoveCandidateKeepsOriginalFurnitureState() {
        Room room = room(3, 4);
        Furniture prototype = desk("desk-1", 1.5, 2, 0);
        double safeMaxX = FurnitureBoundary.clamp(room, new Position(10, 2), prototype)
                .orElseThrow().getX();
        Furniture before = desk("desk-1", safeMaxX, 2, 0);

        FeedbackExecution execution = executor.execute(direct(move("op-1",
                new FeedbackTargetSelector("desk-1", "desk", ""), FeedbackRelation.RIGHT, FeedbackMagnitude.LARGE)),
                room, List.of(before));

        assertThat(execution.result().applied()).as(execution.result().noChangeReason()).isFalse();
        assertThat(execution.furniture().getFirst().getPosition().getX()).isEqualTo(safeMaxX);
    }

    @Test
    void rotateNearWallMovesFurnitureInwardByTheMinimumRequiredDistance() {
        Furniture before = desk("desk-1", 0.8, 0.38, 0);

        FeedbackExecution execution = executor.execute(direct(rotate("op-1",
                new FeedbackTargetSelector("desk-1", "desk", ""),
                FeedbackOrientation.QUARTER_TURN_CW, List.of())), room(3, 3), List.of(before));

        assertThat(execution.result().applied()).as(execution.result().noChangeReason()).isTrue();
        assertThat(execution.furniture().getFirst().getRotation()).isEqualTo(90);
        assertThat(execution.furniture().getFirst().getPosition().getZ())
                .isCloseTo(0.58, org.assertj.core.data.Offset.offset(1.0e-7));
    }

    @Test
    void moveFurnitureLargerThanRoomReportsBoundaryFailureAndKeepsSnapshot() {
        Furniture oversized = new Furniture("desk-1", "desk", "책상", 3.0, 0.6, 0.73,
                new Position(1.5, 1.5), 0, FurnitureStatus.RECOMMENDED);

        FeedbackExecution execution = executor.execute(direct(move("op-1",
                new FeedbackTargetSelector("desk-1", "desk", ""),
                FeedbackRelation.RIGHT, FeedbackMagnitude.SMALL)), room(3, 3), List.of(oversized));

        assertThat(execution.result().applied()).isFalse();
        assertThat(execution.result().noChangeReason()).isEqualTo("NO_VALID_BOUNDARY_PLACEMENT");
        assertThat(execution.furniture()).containsExactly(oversized);
    }

    @Test
    void rotateThatCannotFitReportsRotationOutOfBoundsAndKeepsSnapshot() {
        Furniture before = new Furniture("desk-1", "desk", "책상", 1.0, 2.5, 0.73,
                new Position(0.55, 1.5), 0, FurnitureStatus.RECOMMENDED);

        FeedbackExecution execution = executor.execute(direct(rotate("op-1",
                new FeedbackTargetSelector("desk-1", "desk", ""),
                FeedbackOrientation.QUARTER_TURN_CW, List.of())), room(1.1, 3), List.of(before));

        assertThat(execution.result().applied()).isFalse();
        assertThat(execution.result().noChangeReason()).isEqualTo("ROTATION_OUT_OF_BOUNDS");
        assertThat(execution.furniture()).containsExactly(before);
    }

    @Test
    void furnitureAdditionPathAllowsCanonicalRugToOverlapExistingFurniture() {
        Furniture bed = oversizedBedCoveringCandidateArea();
        FeedbackOperation addRug = add("op-1", "rug");

        FeedbackExecution execution = executor.execute(direct(addRug), room(6, 6), List.of(bed), null,
                FurnitureAdditionPolicy.MAX_NEW_ADDITIONS);

        assertThat(execution.result().applied()).isTrue();
        assertThat(execution.furniture()).anyMatch(FurnitureDomainPolicy::isRug);
        assertThat(new ValidationService().validate(room(6, 6), execution.furniture()).isCollisionFree()).isTrue();
    }

    @Test
    void naturalLanguageFeedbackPathUsesTheSameRugOverlayPolicy() {
        Furniture bed = oversizedBedCoveringCandidateArea();
        Room room = room(6, 6);
        FeedbackPlan plan = new RuleBasedFeedbackPlanInterpreter().interpret(
                "러그를 하나 추가해줘", room, List.of(bed), null);

        FeedbackExecution execution = executor.execute(plan, room, List.of(bed), null);

        assertThat(plan.operations()).extracting(FeedbackOperation::type)
                .containsExactly(FeedbackOperationType.ADD_FURNITURE);
        assertThat(execution.result().applied()).isTrue();
        assertThat(execution.furniture()).anyMatch(FurnitureDomainPolicy::isRug);
        assertThat(new ValidationService().validate(room, execution.furniture()).isCollisionFree()).isTrue();
    }

    @Test
    void naturalLanguageMoveKeepsFurnitureCountIdAndProductVariant() {
        Furniture chair = new Furniture("chair-1", "desk_chair", "기존 의자", 0.5, 0.5, 0.8,
                new Position(2, 2), 0, FurnitureStatus.EXISTING,
                "desk-chair-basic-01", List.of("minimal"), "desk-chair-basic");
        FeedbackPlan plan = new RuleBasedFeedbackPlanInterpreter().interpret(
                "의자를 오른쪽으로 배치해줘", room(6, 6), List.of(chair), null);

        FeedbackExecution execution = executor.execute(plan, room(6, 6), List.of(chair));

        assertThat(plan.operations()).singleElement().satisfies(operation ->
                assertThat(operation.type()).isEqualTo(FeedbackOperationType.MOVE));
        assertThat(execution.result().applied()).isTrue();
        assertThat(execution.furniture()).singleElement().satisfies(after -> {
            assertThat(after.getId()).isEqualTo("chair-1");
            assertThat(after.getProductId()).isEqualTo("desk-chair-basic-01");
            assertThat(after.getVariantId()).isEqualTo("desk-chair-basic");
        });
    }

    @Test
    void cornerMoveKeepsExistingFurnitureIdentityProductAndCount() {
        Furniture chair = new Furniture("chair-1", "desk_chair", "기존 의자", 0.5, 0.5, 0.8,
                new Position(2, 2), 0, FurnitureStatus.EXISTING,
                "desk-chair-basic-01", List.of("minimal"), "desk-chair-basic");
        FeedbackOperation move = new FeedbackOperation("op-1", FeedbackOperationType.MOVE,
                new FeedbackTargetSelector("chair-1", "desk_chair", ""), null,
                new FeedbackPlacement(FeedbackRelation.IN_CORNER, null, null), null, null, null, List.of());

        FeedbackExecution execution = executor.execute(direct(move), room(6, 6), List.of(chair));

        assertThat(execution.result().applied()).as(execution.result().noChangeReason()).isTrue();
        assertThat(execution.furniture()).singleElement().satisfies(after -> {
            assertThat(after.getId()).isEqualTo(chair.getId());
            assertThat(after.getProductId()).isEqualTo(chair.getProductId());
            assertThat(after.getVariantId()).isEqualTo(chair.getVariantId());
            assertThat(after.getPosition()).isNotEqualTo(chair.getPosition());
        });
        ValidationResult validation = new ValidationService().validate(room(6, 6), execution.furniture());
        assertThat(validation.isCollisionFree()).isTrue();
        assertThat(validation.isBoundaryValid()).isTrue();
    }

    @Test
    void referenceMoveKeepsExistingFurnitureIdentityAndDoesNotAddFurniture() {
        Furniture monitor = new Furniture("monitor-1", "monitor", "기존 모니터", 0.5, 0.25, 0.4,
                new Position(1, 1), 0, FurnitureStatus.EXISTING,
                "monitor-basic-01", List.of("minimal"), "monitor-basic");
        Furniture desk = desk("desk-1", 3, 3, 0);
        FeedbackOperation move = new FeedbackOperation("op-1", FeedbackOperationType.MOVE,
                new FeedbackTargetSelector("monitor-1", "monitor", ""),
                new FeedbackTargetSelector("desk-1", "desk", ""),
                new FeedbackPlacement(FeedbackRelation.NEXT_TO, null, null), null, null, null, List.of());

        FeedbackExecution execution = executor.execute(direct(move), room(6, 6), List.of(monitor, desk));

        assertThat(execution.result().applied()).as(execution.result().noChangeReason()).isTrue();
        assertThat(execution.furniture()).hasSize(2);
        assertThat(execution.furniture().getFirst()).satisfies(after -> {
            assertThat(after.getId()).isEqualTo("monitor-1");
            assertThat(after.getProductId()).isEqualTo("monitor-basic-01");
            assertThat(after.getVariantId()).isEqualTo("monitor-basic");
        });
        ValidationResult validation = new ValidationService().validate(room(6, 6), execution.furniture());
        assertThat(validation.isCollisionFree()).isTrue();
        assertThat(validation.isBoundaryValid()).isTrue();
    }

    @Test
    void differentDesignBedSwapKeepsCountIdTypeAndPositionWhileChangingProductVariant() {
        Furniture bed = new Furniture("bed-1", "bed", "기존 침대", 0.94, 2.06, 0.85,
                new Position(4, 4), 0, FurnitureStatus.EXISTING,
                "bed-fabric-headboard-01", List.of("natural", "minimal"), "bed-fabric-headboard");
        FeedbackPlan plan = new RuleBasedFeedbackPlanInterpreter().interpret(
                "침대를 다른 디자인으로 바꿔줘", room(8, 8), List.of(bed), null);

        FeedbackExecution execution = executor.execute(plan, room(8, 8), List.of(bed));

        assertThat(plan.operations()).singleElement().satisfies(operation ->
                assertThat(operation.type()).isEqualTo(FeedbackOperationType.SWAP_FURNITURE));
        assertThat(execution.result().applied()).as(execution.result().noChangeReason()).isTrue();
        assertThat(execution.furniture()).singleElement().satisfies(after -> {
            assertThat(after.getId()).isEqualTo("bed-1");
            assertThat(after.getType()).isEqualTo("bed");
            assertThat(after.getProductId()).isEqualTo("bed-01");
            assertThat(after.getVariantId()).isNull();
            assertThat(after.getPosition().getX()).isEqualTo(bed.getPosition().getX());
            assertThat(after.getPosition().getZ()).isEqualTo(bed.getPosition().getZ());
        });
        ValidationResult validation = new ValidationService().validate(room(8, 8), execution.furniture());
        assertThat(validation.isCollisionFree()).isTrue();
        assertThat(validation.isBoundaryValid()).isTrue();
    }

    @Test
    void metadataConstrainedDrawerSwapUsesTheOnlyMatchingCatalogCandidateAndKeepsIdentity() {
        Furniture drawer = new Furniture("drawer-1", "drawer_chest", "기존 수납장", 0.8, 0.4, 1.0,
                new Position(3, 3), 90, FurnitureStatus.EXISTING,
                "drawer-chest-bedside-01", List.of("minimal"), "drawer-chest-bedside");
        FeedbackPlan plan = new RuleBasedFeedbackPlanInterpreter().interpret(
                "밝은 색 수납장으로 바꿔줘", room(7, 7), List.of(drawer), null);

        FeedbackExecution execution = executor.execute(plan, room(7, 7), List.of(drawer));

        assertThat(plan.operations()).singleElement().satisfies(operation -> {
            assertThat(operation.type()).isEqualTo(FeedbackOperationType.SWAP_FURNITURE);
            assertThat(operation.replacementRequirements().styleKeywords()).containsExactly("paintedWhite");
        });
        assertThat(execution.result().applied()).as(execution.result().noChangeReason()).isTrue();
        assertThat(execution.furniture()).singleElement().satisfies(after -> {
            assertThat(after.getId()).isEqualTo("drawer-1");
            assertThat(after.getType()).isEqualTo("drawer_chest");
            assertThat(after.getPosition().getX()).isEqualTo(3);
            assertThat(after.getPosition().getZ()).isEqualTo(3);
            assertThat(after.getRotation()).isEqualTo(90);
            assertThat(after.getProductId()).isEqualTo("drawer-chest-classic-gullaberg-01");
            assertThat(after.getVariantId()).isEqualTo("drawer-chest-classic-gullaberg");
            assertThat(after.getStyleTags()).containsExactly("classic", "natural");
        });
        ValidationResult validation = new ValidationService().validate(room(7, 7), execution.furniture());
        assertThat(validation.isCollisionFree()).isTrue();
        assertThat(validation.isBoundaryValid()).isTrue();
    }

    @Test
    void metadataSwapWithMultipleCandidatesReturnsNoSafeCandidateWithoutChangingFurniture() {
        Furniture drawer = new Furniture("drawer-1", "drawer_chest", "기존 수납장", 0.8, 0.4, 1.0,
                new Position(3, 3), 0, FurnitureStatus.EXISTING,
                "drawer-chest-classic-gullaberg-01", List.of("classic"), "drawer-chest-classic-gullaberg");
        FeedbackPlan plan = new RuleBasedFeedbackPlanInterpreter().interpret(
                "수납장을 우드 톤으로 바꿔줘", room(7, 7), List.of(drawer), null);

        FeedbackExecution execution = executor.execute(plan, room(7, 7), List.of(drawer));

        assertThat(execution.result().applied()).isFalse();
        assertThat(execution.result().noChangeReason()).isEqualTo("NO_SAFE_SWAP_CANDIDATE");
        assertThat(execution.furniture()).containsExactly(drawer);
    }

    @Test
    void ambiguousMetadataSwapPreventsPartialCompositeMoveExecution() {
        Furniture bed = new Furniture("bed-1", "bed", "침대", 1.0, 2.0, 0.5,
                new Position(2, 2), 0, FurnitureStatus.EXISTING);
        Furniture chair = new Furniture("chair-1", "desk_chair", "의자", 0.5, 0.5, 0.8,
                new Position(5, 5), 0, FurnitureStatus.EXISTING,
                "chair-classic-tonstad-01", List.of("classic"), "chair-classic-tonstad");
        FeedbackPlan plan = new RuleBasedFeedbackPlanInterpreter().interpret(
                "침대는 창가 쪽으로 옮겨 주고 의자는 금속 소재로 바꿔줘", room(8, 8), List.of(bed, chair), null);

        FeedbackExecution execution = executor.execute(plan, room(8, 8), List.of(bed, chair));

        assertThat(plan.requestKind()).isEqualTo(FeedbackRequestKind.COMPOSITE);
        assertThat(execution.result().applied()).isFalse();
        assertThat(execution.result().noChangeReason()).isEqualTo("NO_SAFE_SWAP_CANDIDATE");
        assertThat(execution.furniture()).containsExactly(bed, chair);
        assertThat(execution.operationResults()).singleElement().satisfies(result -> {
            assertThat(result.type()).isEqualTo(FeedbackOperationType.SWAP_FURNITURE);
            assertThat(result.status()).isEqualTo(FeedbackOperationExecution.Status.FAILED);
        });
    }

    private FeedbackPlan direct(FeedbackOperation operation) {
        return new FeedbackPlan("2.0", FeedbackRequestKind.DIRECT, List.of(operation), List.of(), null,
                "test", FeedbackSource.LLM, false);
    }

    private FeedbackOperation move(String operationId, FeedbackTargetSelector target,
                                   FeedbackRelation relation, FeedbackMagnitude magnitude) {
        return new FeedbackOperation(operationId, FeedbackOperationType.MOVE, target,
                new FeedbackPlacement(relation, magnitude, null), null, List.of());
    }

    private FeedbackOperation rotate(String operationId, FeedbackTargetSelector target,
                                     FeedbackOrientation orientation, List<String> dependsOn) {
        return new FeedbackOperation(operationId, FeedbackOperationType.ROTATE, target,
                new FeedbackPlacement(null, null, orientation), null, dependsOn);
    }

    private FeedbackOperation add(String operationId, String type) {
        return new FeedbackOperation(operationId, FeedbackOperationType.ADD_FURNITURE,
                new FeedbackTargetSelector("", type, ""), null,
                new FeedbackPlacement(FeedbackRelation.NEAR_WALL, null, null, null), null,
                new FeedbackProductRequirements(type, FeedbackSizePreference.ANY, false, List.of()),
                null, List.of());
    }

    private Furniture oversizedBedCoveringCandidateArea() {
        return new Furniture("bed-1", "bed", "큰 침대", 5.0, 5.0, 0.5,
                new Position(3.0, 3.0), 0, FurnitureStatus.EXISTING);
    }

    private Furniture desk(String id, double x, double z, double rotation) {
        return new Furniture(id, "desk", "책상", 1.0, 0.6, 0.73,
                new Position(x, z), rotation, FurnitureStatus.RECOMMENDED,
                "desk-compact-01", List.of("minimal"), "desk-compact");
    }

    private Furniture boundaryDesk(String id, double x, double z, double rotation) {
        return new Furniture(id, "desk", "책상", 1.2, 0.6, 0.73,
                new Position(x, z), rotation, FurnitureStatus.EXISTING);
    }


    private Room room(double width, double depth) {
        return new Room(null, width, depth, 2.4, "meter", List.of(), List.of());
    }
}
