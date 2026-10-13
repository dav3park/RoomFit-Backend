package com.roomfit.placement;

import com.roomfit.common.CustomException;
import com.roomfit.common.ErrorCode;
import com.roomfit.placement.dto.WorkspaceFeedbackPreviewRequest;
import com.roomfit.product.repository.MockProductRepository;
import com.roomfit.room.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkspaceFeedbackPreviewServiceTest {
    private final RoomAccessService access = mock(RoomAccessService.class);
    private final OpenAiWorkspaceFeedbackInterpreter interpreter = mock(OpenAiWorkspaceFeedbackInterpreter.class);
    private final ValidationService validation = new ValidationService();
    private final DeterministicFeedbackExecutor executor = new DeterministicFeedbackExecutor(validation, new MockProductRepository());
    private final WorkspaceFeedbackPreviewService service = new WorkspaceFeedbackPreviewService(access, interpreter, executor, validation, new FurnitureDomainPolicy());
    private final Furniture persisted = new Furniture("persisted", "desk", "DB 책상", 1.2, 0.6, 0.72, new Position(4, 4), 0, FurnitureStatus.EXISTING);
    private final Room room = new Room(1L, 6, 6, 2.7, "m", List.of(), List.of(persisted));

    @BeforeEach
    void readableRoom() { when(access.findReadableRoom(1L)).thenReturn(room); }

    @Test
    void executesLatestDraftAndReturnsRevisionWithoutTouchingRoom() {
        plan(List.of(move("op-1", "desk-1")), Map.of());
        var request = request(List.of(desk("desk-1", 2, 2)), List.of());
        var result = service.preview(request);
        assertThat(result.status()).isEqualTo(FeedbackStatus.SUCCESS);
        assertThat(result.revision()).isEqualTo("draft-7");
        assertThat(result.source()).isEqualTo("LLM");
        assertThat(result.furniture().getFirst().getPosition().getX()).isEqualTo(2.2);
        assertThat(request.furniture().getFirst().position().x()).isEqualTo(2);
        assertThat(persisted.getPosition().getX()).isEqualTo(4);
        assertThat(room.getFurniture()).containsExactly(persisted);
        verify(interpreter, times(1)).interpret(eq(request), same(room), anyList());
    }

    @Test
    void lockAndExplicitKeepReturnOriginalEvenIfExecutorFindsCandidate() {
        plan(List.of(move("op-1", "desk-1")), Map.of());
        var locked = service.preview(request(List.of(desk("desk-1", 2, 2)), List.of("desk-1")));
        assertThat(locked.reasonCode()).isEqualTo("LOCKED_FURNITURE");
        assertThat(locked.furniture().getFirst().getPosition().getX()).isEqualTo(2);
        assertThat(locked.operationResults().getFirst().reasonCode()).isEqualTo("ATOMIC_ROLLBACK");
        plan(List.of(move("op-1", "desk-1")), Map.of("desk-1", Set.of("POSITION")));
        assertThat(service.preview(request(List.of(desk("desk-1", 2, 2)), List.of())).reasonCode()).isEqualTo("PRESERVATION_CONFLICT");
    }

    @Test
    void untouchedFurnitureMetadataAndGeometryArePreserved() {
        plan(List.of(move("op-1", "desk-1")), Map.of());
        var result = service.preview(selected(request(List.of(desk("desk-1", 2, 2), desk("desk-2", 4, 4)), List.of()), "desk-1"));
        assertThat(result.status()).isEqualTo(FeedbackStatus.SUCCESS);
        Furniture untouched = result.furniture().get(1);
        assertThat(untouched.getPosition().getX()).isEqualTo(4);
        assertThat(untouched.getProductId()).isEqualTo("desk-compact-01");
        assertThat(untouched.getVariantId()).isEqualTo("desk-compact");
        assertThat(untouched.getStyleTags()).containsExactly("minimal");
    }

    @Test
    void compositeFailureRollsBackEarlierMovement() {
        plan(List.of(move("op-1", "desk-1"), move("op-2", "absent")), Map.of());
        var result = service.preview(request(List.of(desk("desk-1", 2, 2)), List.of()));
        assertThat(result.status()).isEqualTo(FeedbackStatus.NEEDS_CLARIFICATION);
        assertThat(result.furniture().getFirst().getPosition().getX()).isEqualTo(2);
        assertThat(result.operationResults()).noneMatch(op -> op.status() == FeedbackOperationExecution.Status.APPLIED);
    }

    @Test
    void typeOnlyDuplicateTargetAsksInsteadOfPickingFirst() {
        FeedbackOperation ambiguous = new FeedbackOperation("op-1", FeedbackOperationType.MOVE,
                new FeedbackTargetSelector("", "desk", ""), new FeedbackPlacement(FeedbackRelation.RIGHT, FeedbackMagnitude.SMALL, null), null, List.of());
        plan(List.of(ambiguous), Map.of());
        var result = service.preview(request(List.of(desk("desk-1", 2, 2), desk("desk-2", 4, 4)), List.of()));
        assertThat(result.status()).isEqualTo(FeedbackStatus.NEEDS_CLARIFICATION);
        assertThat(result.clarification().candidates()).extracting(candidate -> candidate.furnitureId()).containsExactly("desk-1", "desk-2");
        assertThat(result.furniture().getFirst().getPosition().getX()).isEqualTo(2);
    }

    @Test
    void rotatingNearBoundaryDoesNotSilentlyRelocateFurniture() {
        FeedbackOperation rotate = new FeedbackOperation("op-1", FeedbackOperationType.ROTATE,
                new FeedbackTargetSelector("desk-1", "desk", ""), new FeedbackPlacement(null, null, FeedbackOrientation.QUARTER_TURN_CW), null, List.of());
        plan(List.of(rotate), Map.of());
        var base = request(List.of(desk("desk-1", 2, 0.3)), List.of());
        var result = service.preview(new WorkspaceFeedbackPreviewRequest(base.roomId(), base.revision(), "책상을 시계 방향으로 90도 회전해줘",
                base.roomDimensions(), base.furniture(), List.of(), null, base.preferences()));
        assertThat(result.status()).isEqualTo(FeedbackStatus.FAILED);
        assertThat(result.reasonCode()).isIn("UNREQUESTED_CHANGE", "NO_SAFE_ORIENTATION_CANDIDATE");
        assertThat(result.furniture().getFirst().getRotation()).isZero();
        assertThat(result.furniture().getFirst().getPosition().getZ()).isEqualTo(0.3);
    }

    @Test
    void existingUnsafeUntouchedFurniturePreventsWholeCandidateSuccess() {
        plan(List.of(move("op-1", "desk-1")), Map.of());
        var result = service.preview(selected(request(List.of(desk("desk-1", 2, 2), desk("desk-2", 10, 10)), List.of()), "desk-1"));
        assertThat(result.status()).isEqualTo(FeedbackStatus.FAILED);
        assertThat(result.reasonCode()).isEqualTo("NO_SAFE_CANDIDATE");
        assertThat(result.furniture().getFirst().getPosition().getX()).isEqualTo(2);
    }

    @Test
    void zeroOrNonfiniteDimensionIsRejectedBeforeProvider() {
        var valid = desk("desk-1", 2, 2);
        for (double size : List.of(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            var invalid = new WorkspaceFeedbackPreviewRequest.FurnitureSnapshot(valid.id(), valid.type(), valid.label(), size,
                    valid.depth(), valid.height(), valid.position(), valid.rotation(), valid.status(), valid.productId(), valid.variantId(), valid.styleTags());
            assertThatThrownBy(() -> service.preview(request(List.of(invalid), List.of())))
                    .isInstanceOfSatisfying(CustomException.class, error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST_BODY));
        }
        verifyNoInteractions(interpreter);
    }

    @Test
    void duplicateIdsUnknownLocksAndActiveLimitAreRejectedBeforeProvider() {
        List<WorkspaceFeedbackPreviewRequest> invalid = List.of(
                request(List.of(desk("same", 2, 2), desk("same", 4, 4)), List.of()),
                request(List.of(desk("desk-1", 2, 2)), List.of("unknown")),
                request(List.of(desk("desk-1", 2, 2)), List.of("desk-1", "desk-1")),
                request(IntStream.range(0, 13).mapToObj(i -> desk("desk-" + i, 2, 2)).toList(), List.of()));
        for (var request : invalid) assertThatThrownBy(() -> service.preview(request)).isInstanceOf(CustomException.class);
        verifyNoInteractions(interpreter);
    }

    @Test
    void staleRoomDimensionsAndWrongOwnershipDoNotCallProvider() {
        var request = request(List.of(desk("desk-1", 2, 2)), List.of());
        var stale = new WorkspaceFeedbackPreviewRequest(1L, request.revision(), request.feedback(),
                new WorkspaceFeedbackPreviewRequest.RoomDimensions(5, 6, 2.7), request.furniture(), List.of(), null, request.preferences());
        assertThatThrownBy(() -> service.preview(stale)).isInstanceOfSatisfying(CustomException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.FEEDBACK_ROOM_CHANGED));
        when(access.findReadableRoom(1L)).thenThrow(new CustomException(ErrorCode.ROOM_NOT_FOUND));
        assertThatThrownBy(() -> service.preview(request)).isInstanceOf(CustomException.class);
        verifyNoInteractions(interpreter);
    }

    @ParameterizedTest
    @ValueSource(strings = {"FEEDBACK_LLM_NOT_CONFIGURED", "FEEDBACK_LLM_FAILED"})
    void providerFailureDoesNotRunExecutorOrAlterSnapshot(String code) {
        when(interpreter.interpret(any(), any(), anyList())).thenThrow(new CustomException(ErrorCode.valueOf(code)));
        var request = request(List.of(desk("desk-1", 2, 2)), List.of());
        assertThatThrownBy(() -> service.preview(request)).isInstanceOf(CustomException.class);
        assertThat(request.furniture().getFirst().position().x()).isEqualTo(2);
        assertThat(persisted.getPosition().getX()).isEqualTo(4);
    }

    @Test
    void addedProductAliasesBecomeCanonicalInPreviewResponse() {
        FeedbackOperation add = new FeedbackOperation("op-1", FeedbackOperationType.ADD_FURNITURE,
                new FeedbackTargetSelector("", "mood_lamp", ""), null,
                new FeedbackPlacement(FeedbackRelation.CENTER, null, null), null,
                new FeedbackProductRequirements("mood_lamp", FeedbackSizePreference.ANY, false, List.of()), null, List.of());
        plan(List.of(add), Map.of());
        var base = request(List.of(), List.of());
        var result = service.preview(new WorkspaceFeedbackPreviewRequest(base.roomId(), base.revision(), "무드등 하나 추가해줘",
                base.roomDimensions(), base.furniture(), List.of(), null, base.preferences()));
        assertThat(result.status()).isEqualTo(FeedbackStatus.SUCCESS);
        assertThat(result.furniture()).extracting(Furniture::getType).containsExactly("mood_lamp");
    }

    @Test
    void providerGuessedIdCannotResolveDuplicateUnqualifiedTargets() {
        plan(List.of(move("op-1", "desk-1")), Map.of());
        var result = service.preview(request(List.of(desk("desk-1", 2, 2), desk("desk-2", 4, 4)), List.of()));
        assertThat(result.status()).isEqualTo(FeedbackStatus.NEEDS_CLARIFICATION);
        assertThat(result.clarification().requiredField()).isEqualTo("targetFurnitureId");
        assertThat(result.clarification().candidates()).extracting(candidate -> candidate.furnitureId()).containsExactly("desk-1", "desk-2");
        assertThat(result.operationResults()).isEmpty();
        assertThat(result.furniture().getFirst().getPosition().getX()).isEqualTo(2);
    }

    @Test
    void explicitRoleAnswerAllowsOnlyChosenDuplicateTarget() {
        plan(List.of(move("op-1", "desk-2")), Map.of());
        var base = request(List.of(desk("desk-1", 2, 2), desk("desk-2", 4, 4)), List.of());
        var answered = new WorkspaceFeedbackPreviewRequest(base.roomId(), base.revision(), base.feedback(), base.roomDimensions(),
                base.furniture(), base.lockedFurnitureIds(), null, base.preferences(), List.of(
                new WorkspaceFeedbackPreviewRequest.ClarificationSelection(base.revision(), base.feedback(), "targetFurnitureId", "desk-2")));
        var result = service.preview(answered);
        assertThat(result.status()).isEqualTo(FeedbackStatus.SUCCESS);
        assertThat(result.furniture().getFirst().getPosition().getX()).isEqualTo(2);
        assertThat(result.furniture().get(1).getPosition().getX()).isEqualTo(4.2);
    }

    @Test
    void toolbarSelectionResolvesIdenticallyNamedTargets() {
        plan(List.of(move("op-1", "desk-1")), Map.of());
        var desks = List.of(desk("desk-1", 2, 2), desk("desk-2", 4, 4)).stream().map(item ->
                new WorkspaceFeedbackPreviewRequest.FurnitureSnapshot(item.id(), item.type(), "책상", item.width(), item.depth(), item.height(),
                        item.position(), item.rotation(), item.status(), item.productId(), item.variantId(), item.styleTags())).toList();
        var base = request(desks, List.of());
        var selected = new WorkspaceFeedbackPreviewRequest(base.roomId(), base.revision(), "책상을 방 기준 오른쪽으로 이동", base.roomDimensions(),
                desks, List.of(), "desk-1", base.preferences());
        assertThat(service.preview(selected).status()).isEqualTo(FeedbackStatus.SUCCESS);
    }

    @Test
    void uniqueNameInUserTextResolvesTargetButConflictingToolbarDoesNotOverrideIt() {
        plan(List.of(move("op-1", "desk-1")), Map.of());
        var base = request(List.of(desk("desk-1", 2, 2), desk("desk-2", 4, 4)), List.of());
        var named = new WorkspaceFeedbackPreviewRequest(base.roomId(), base.revision(), "책상 desk-1을 방 기준 오른쪽으로 이동", base.roomDimensions(),
                base.furniture(), List.of(), "desk-2", base.preferences());
        assertThat(service.preview(named).status()).isEqualTo(FeedbackStatus.SUCCESS);
        plan(List.of(move("op-1", "desk-2")), Map.of());
        assertThat(service.preview(named).status()).isEqualTo(FeedbackStatus.NEEDS_CLARIFICATION);
    }

    @Test
    void inventedLabelDiscriminatorDoesNotResolveAnUnqualifiedRequest() {
        FeedbackOperation guessed = new FeedbackOperation("op-1", FeedbackOperationType.MOVE,
                new FeedbackTargetSelector("desk-1", "desk", "desk-1"),
                new FeedbackPlacement(FeedbackRelation.RIGHT, FeedbackMagnitude.SMALL, null), null, List.of());
        plan(List.of(guessed), Map.of());
        assertThat(service.preview(request(List.of(desk("desk-1", 2, 2), desk("desk-2", 4, 4)), List.of())).status())
                .isEqualTo(FeedbackStatus.NEEDS_CLARIFICATION);
    }

    @Test
    void toolbarTargetSelectionNeverResolvesAnAmbiguousReference() {
        var chair = new WorkspaceFeedbackPreviewRequest.FurnitureSnapshot("chair-1", "desk_chair", "의자", .5, .5, .8,
                new WorkspaceFeedbackPreviewRequest.PositionSnapshot(3, 2), 0, FurnitureStatus.EXISTING, null, null, List.of());
        FeedbackOperation move = new FeedbackOperation("op-1", FeedbackOperationType.MOVE,
                new FeedbackTargetSelector("chair-1", "desk_chair", ""), new FeedbackTargetSelector("desk-1", "desk", ""),
                new FeedbackPlacement(FeedbackRelation.NEXT_TO, null, null), null, null, null, List.of());
        plan(List.of(move), Map.of());
        var result = service.preview(selected(request(List.of(chair, desk("desk-1", 2, 2), desk("desk-2", 4, 4)), List.of()), "chair-1"));
        assertThat(result.status()).isEqualTo(FeedbackStatus.NEEDS_CLARIFICATION);
        assertThat(result.clarification().requiredField()).isEqualTo("referenceFurnitureId");
        assertThat(result.clarification().candidates()).hasSize(2);
    }

    private static WorkspaceFeedbackPreviewRequest selected(WorkspaceFeedbackPreviewRequest base, String id) {
        return new WorkspaceFeedbackPreviewRequest(base.roomId(), base.revision(), base.feedback(), base.roomDimensions(),
                base.furniture(), base.lockedFurnitureIds(), id, base.preferences());
    }

    private void plan(List<FeedbackOperation> operations, Map<String, Set<String>> preserve) {
        FeedbackPlan plan = new FeedbackPlan("2.0", operations.size() == 1 ? FeedbackRequestKind.DIRECT : FeedbackRequestKind.COMPOSITE,
                operations, List.of(), null, "test", FeedbackSource.LLM, false);
        when(interpreter.interpret(any(), any(), anyList())).thenReturn(new WorkspaceFeedbackPlan(plan, preserve, null, null));
    }
    private static FeedbackOperation move(String operationId, String id) {
        return new FeedbackOperation(operationId, FeedbackOperationType.MOVE, new FeedbackTargetSelector(id, "desk", ""),
                new FeedbackPlacement(FeedbackRelation.RIGHT, FeedbackMagnitude.SMALL, null), null, List.of());
    }
    static WorkspaceFeedbackPreviewRequest.FurnitureSnapshot desk(String id, double x, double z) {
        return new WorkspaceFeedbackPreviewRequest.FurnitureSnapshot(id, "desk", "책상 " + id, 1.2, 0.6, 0.72,
                new WorkspaceFeedbackPreviewRequest.PositionSnapshot(x, z), 0, FurnitureStatus.EXISTING, "desk-compact-01", "desk-compact", List.of("minimal"));
    }
    static WorkspaceFeedbackPreviewRequest request(List<WorkspaceFeedbackPreviewRequest.FurnitureSnapshot> furniture, List<String> locks) {
        return new WorkspaceFeedbackPreviewRequest(1L, "draft-7", "책상을 방 기준 오른쪽으로 조금 이동", new WorkspaceFeedbackPreviewRequest.RoomDimensions(6, 6, 2.7),
                furniture, locks, null, new WorkspaceFeedbackPreviewRequest.Preferences("업무 / 공부", "minimal", "auto"));
    }
}
