package com.roomfit.placement;

import com.roomfit.placement.dto.WorkspaceFeedbackPreviewRequest;
import com.roomfit.product.repository.MockProductRepository;
import com.roomfit.room.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkspaceFeedbackIntentGuardTest {
    private final OpenAiWorkspaceFeedbackInterpreter interpreter = mock(OpenAiWorkspaceFeedbackInterpreter.class);
    private final RoomAccessService access = mock(RoomAccessService.class);
    private final ValidationService validation = new ValidationService();
    private final Room room = new Room(1L, 6, 6, 2.7, "m", List.of(), List.of());
    private final WorkspaceFeedbackPreviewService service = new WorkspaceFeedbackPreviewService(access, interpreter,
            new DeterministicFeedbackExecutor(validation, new MockProductRepository()), validation, new FurnitureDomainPolicy());
    private final List<WorkspaceFeedbackPreviewRequest.FurnitureSnapshot> snapshot = List.of(
            WorkspaceFeedbackPreviewServiceTest.desk("desk-1", 2, 2),
            new WorkspaceFeedbackPreviewRequest.FurnitureSnapshot("bed-1", "bed", "침대", 1.4, 2, .5,
                    new WorkspaceFeedbackPreviewRequest.PositionSnapshot(1, 4), 0, FurnitureStatus.EXISTING, null, null, List.of()));

    @ParameterizedTest
    @ValueSource(strings = {"책상을 오른쪽으로 30cm 옮겨줘", "책상을 방 기준 오른쪽으로 30cm 옮겨줘",
            "책상을 방 기준 오른쪽으로 0.3m 옮겨줘", "책상을 방 기준 오른쪽으로 300 mm 옮겨줘",
            "책상을 오른쪽으로 삼십 센티미터 옮겨줘", "책상을 방 기준 오른쪽으로 ３０ＣＭ 옮겨줘",
            "책상을 방 기준 오른쪽으로 30센치 옮겨줘"})
    void explicitLengthsAreNeverApproximatedOrSentToProvider(String text) {
        var result = service.preview(request(text));
        assertThat(result.status()).isEqualTo(FeedbackStatus.FAILED);
        assertThat(result.reasonCode()).isEqualTo("UNSUPPORTED_DISTANCE");
        assertThat(result.source()).isEqualTo("INPUT_GUARD");
        assertUnchanged(result); verifyNoInteractions(interpreter);
    }

    @ParameterizedTest
    @ValueSource(strings = {"책상을 45도 회전해줘", "책상을 시계 방향으로 45도 회전해줘", "책상을 45° 돌려줘",
            "책상을 시계 방향으로 270도 회전해줘", "책상을 시계 방향으로 -90도 회전해줘", "책상을 시계 방향으로 −90도 회전해줘"})
    void unsupportedAnglesCannotBecomeQuarterTurns(String text) {
        var result = service.preview(request(text));
        assertThat(result.reasonCode()).isEqualTo("UNSUPPORTED_ANGLE");
        assertUnchanged(result); verifyNoInteractions(interpreter);
    }

    @ParameterizedTest
    @ValueSource(strings = {"침대를 삭제하지 마", "책상은 이동하지 마", "책상을 90도 회전해줘", "책상을 오른쪽으로 30 옮겨줘"})
    void preservationOnlyOrUnresolvedNumbersAskWithoutMutation(String text) {
        var result = service.preview(request(text));
        assertThat(result.status()).isEqualTo(FeedbackStatus.NEEDS_CLARIFICATION);
        assertUnchanged(result); verifyNoInteractions(interpreter);
    }

    @ParameterizedTest
    @ValueSource(strings = {"책상은 옮기지 말고 방 기준 오른쪽으로 옮겨줘", "책상은 이동하지 말고 책상을 방 기준 오른쪽으로 옮겨줘"})
    void contradictoryMoveRequiresAQuestionEvenWhenProviderOmitsPreserve(String text) {
        plan(move("desk-1")); var result = service.preview(request(text));
        assertThat(result.reasonCode()).isEqualTo("CONFLICTING_REQUIREMENTS");
        assertThat(result.status()).isEqualTo(FeedbackStatus.NEEDS_CLARIFICATION);
        assertUnchanged(result);
    }

    @ParameterizedTest
    @ValueSource(strings = {"침대는 삭제하지 말고 책상을 방 기준 오른쪽으로 조금 옮겨줘", "침대를 삭제하지 말고 책상만 시계 방향으로 90도 회전해줘"})
    void negatedDeletionCannotEnterExecution(String text) {
        var remove = new FeedbackOperation("remove", FeedbackOperationType.REMOVE_FURNITURE,
                new FeedbackTargetSelector("bed-1", "bed", ""), null, null, List.of());
        plan(remove, text.contains("회전") ? rotate(FeedbackOrientation.QUARTER_TURN_CW) : move("desk-1"));
        var result = service.preview(request(text));
        assertThat(result.reasonCode()).isEqualTo("FORBIDDEN_OPERATION");
        assertThat(result.status()).isEqualTo(FeedbackStatus.FAILED); assertUnchanged(result);
    }

    @Test
    void correctlyInterpretedNegatedDeletionKeepsBedAndMovesOnlyDesk() {
        plan(move("desk-1"));
        var result = service.preview(request("침대를 삭제하지 말고 책상을 방 기준 오른쪽으로 조금 옮겨줘"));
        assertThat(result.status()).isEqualTo(FeedbackStatus.SUCCESS);
        assertThat(result.furniture().getFirst().getPosition().getX()).isEqualTo(2.2);
        assertThat(result.furniture().get(1).getId()).isEqualTo("bed-1");
        assertThat(result.furniture().get(1).getPosition().getZ()).isEqualTo(4);
        assertThat(result.operationResults()).extracting(FeedbackOperationExecution::type).containsExactly(FeedbackOperationType.MOVE);
    }

    @Test
    void deletingIsProhibitedButMovingTheSameItemIsAllowed() {
        plan(move("desk-1"));
        assertThat(service.preview(request("책상을 삭제하지 말고 책상을 방 기준 오른쪽으로 조금 옮겨줘")).status()).isEqualTo(FeedbackStatus.SUCCESS);
    }

    @Test
    void movementProhibitionAllowsInPlaceClockwiseRotation() {
        plan(rotate(FeedbackOrientation.QUARTER_TURN_CW));
        var result = service.preview(request("책상은 옮기지 말고 책상만 시계 방향으로 90도 회전해줘"));
        assertThat(result.status()).isEqualTo(FeedbackStatus.SUCCESS);
        assertThat(result.furniture().getFirst().getPosition().getX()).isEqualTo(2);
        assertThat(result.furniture().getFirst().getRotation()).isEqualTo(90);
    }

    @ParameterizedTest
    @ValueSource(strings = {"QUARTER_TURN_CCW", "HALF_TURN"})
    void supportedAngleAndDirectionStillMustMatchThePlan(String orientation) {
        plan(rotate(FeedbackOrientation.valueOf(orientation)));
        var result = service.preview(request("책상을 시계 방향으로 90도 회전해줘"));
        assertThat(result.reasonCode()).isEqualTo("ANGLE_MISMATCH"); assertUnchanged(result);
    }

    @Test
    void numericAngleDoesNotPermitOmittedOrDuplicatedRotation() {
        plan(move("desk-1"));
        assertThat(service.preview(request("책상을 시계 방향으로 90도 회전해줘")).reasonCode()).isEqualTo("ANGLE_MISMATCH");
    }

    @Test
    void decimalSupportedAngleIsNotSplitAsASentence() {
        plan(rotate(FeedbackOrientation.QUARTER_TURN_CW));
        assertThat(service.preview(request("책상을 시계 방향으로 90.0도 회전해줘")).status()).isEqualTo(FeedbackStatus.SUCCESS);
    }

    @Test
    void missingDirectionFrameCannotSilentlyUseRoomAxes() {
        plan(move("desk-1"));
        var result = service.preview(request("책상을 오른쪽으로 조금 옮겨줘"));
        assertThat(result.reasonCode()).isEqualTo("AMBIGUOUS_DIRECTION_FRAME"); assertUnchanged(result);
    }

    @Test
    void originalKeepClauseCannotBeLostWhenProviderOmitsPreserve() {
        plan(move("bed-1"), move("desk-1"));
        var result = service.preview(request("침대는 그대로 두고 책상을 방 기준 오른쪽으로 조금 옮겨줘"));
        assertThat(result.reasonCode()).isEqualTo("FORBIDDEN_OPERATION"); assertUnchanged(result);
    }

    @Test
    void additionProhibitionPreventsAProviderInventedAddition() {
        var add = new FeedbackOperation("add", FeedbackOperationType.ADD_FURNITURE,
                new FeedbackTargetSelector("", "mood_lamp", ""), null, new FeedbackPlacement(FeedbackRelation.CENTER, null, null), null,
                new FeedbackProductRequirements("mood_lamp", FeedbackSizePreference.ANY, false, List.of()), null, List.of());
        plan(add, move("desk-1"));
        var result = service.preview(request("가구를 추가하지 말고 책상을 방 기준 오른쪽으로 조금 옮겨줘"));
        assertThat(result.reasonCode()).isEqualTo("FORBIDDEN_OPERATION"); assertUnchanged(result);
    }

    @Test
    void negatedDeletionDoesNotAuthorizeDeletingAnUnmentionedDifferentTarget() {
        plan(new FeedbackOperation("remove", FeedbackOperationType.REMOVE_FURNITURE,
                new FeedbackTargetSelector("desk-1", "desk", ""), null, null, List.of()), move("desk-1"));
        var result = service.preview(request("침대는 삭제하지 말고 책상을 방 기준 오른쪽으로 조금 옮겨줘"));
        assertThat(result.reasonCode()).isEqualTo("UNREQUESTED_OPERATION"); assertUnchanged(result);
    }

    @Test
    void productThresholdCannotBeInventedFromABiggerProductRequest() {
        plan(new FeedbackOperation("replace", FeedbackOperationType.REPLACE_PRODUCT,
                new FeedbackTargetSelector("desk-1", "desk", ""), null,
                new FeedbackReplaceConstraints("desk", true, false, 1.5, List.of(), List.of(), false), List.of()));
        var result = service.preview(request("책상을 더 큰 상품으로 교체해줘"));
        assertThat(result.reasonCode()).isEqualTo("NUMERIC_REQUIREMENT_MISMATCH"); assertUnchanged(result);
    }

    @Test
    void explicitPositiveDeletionStillWorks() {
        plan(new FeedbackOperation("remove", FeedbackOperationType.REMOVE_FURNITURE,
                new FeedbackTargetSelector("bed-1", "bed", ""), null, null, List.of()));
        var result = service.preview(request("침대를 삭제해줘"));
        assertThat(result.status()).isEqualTo(FeedbackStatus.SUCCESS);
        assertThat(result.furniture()).extracting(Furniture::getId).containsExactly("desk-1");
    }

    private WorkspaceFeedbackPreviewRequest request(String text) {
        when(access.findReadableRoom(1L)).thenReturn(room);
        return new WorkspaceFeedbackPreviewRequest(1L, "audit-revision", text,
                new WorkspaceFeedbackPreviewRequest.RoomDimensions(6, 6, 2.7), snapshot, List.of(), null,
                new WorkspaceFeedbackPreviewRequest.Preferences("휴식", "natural", "auto"));
    }
    private void plan(FeedbackOperation... operations) {
        when(interpreter.interpret(any(), any(), anyList())).thenReturn(new WorkspaceFeedbackPlan(new FeedbackPlan("2.0",
                operations.length == 1 ? FeedbackRequestKind.DIRECT : FeedbackRequestKind.COMPOSITE, List.of(operations), List.of(), null,
                "test", FeedbackSource.LLM, false), Map.of(), null, null));
    }
    private static FeedbackOperation move(String id) {
        return new FeedbackOperation("move-" + id, FeedbackOperationType.MOVE, new FeedbackTargetSelector(id, id.startsWith("bed") ? "bed" : "desk", ""),
                new FeedbackPlacement(FeedbackRelation.RIGHT, FeedbackMagnitude.SMALL, null), null, List.of());
    }
    private static FeedbackOperation rotate(FeedbackOrientation direction) {
        return new FeedbackOperation("rotate", FeedbackOperationType.ROTATE, new FeedbackTargetSelector("desk-1", "desk", ""),
                new FeedbackPlacement(null, null, direction), null, List.of());
    }
    private void assertUnchanged(com.roomfit.placement.dto.WorkspaceFeedbackPreviewResponse result) {
        assertThat(result.operationResults()).isEmpty();
        assertThat(result.furniture()).extracting(Furniture::getId).containsExactly("desk-1", "bed-1");
        assertThat(result.furniture().getFirst().getPosition().getX()).isEqualTo(2);
        assertThat(snapshot.getFirst().position().x()).isEqualTo(2);
        assertThat(room.getFurniture()).isEmpty();
    }
}
