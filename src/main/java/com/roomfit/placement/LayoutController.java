package com.roomfit.placement;

import com.roomfit.common.CommonResponse;
import com.roomfit.placement.dto.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/layouts")
@Tag(name = "Layouts", description = "추천 배치 생성, 드래그 검증, 수정 저장, 피드백, 확정 API")
public class LayoutController {

    private final LayoutService layoutService;

    public LayoutController(LayoutService layoutService) {
        this.layoutService = layoutService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "빈 배치 생성", description = "AgentContext 없이 방의 기존 가구만 담은 미확정 Layout을 생성합니다. "
            + "AI 추천을 거치지 않고 카탈로그에서 가구를 직접 드래그해 배치하는 흐름의 시작점입니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "빈 배치 생성 성공"),
            @ApiResponse(responseCode = "404", description = "존재하지 않는 roomId")
    })
    public CommonResponse<LayoutResponse> createLayout(@RequestBody CreateLayoutRequest request) {
        return CommonResponse.ok(layoutService.createBlankLayout(request));
    }

    @PostMapping("/{layoutId}/furniture")
    @Operation(summary = "가구 직접 추가", description = "카탈로그 productId를 클라이언트가 고른 좌표/회전 그대로 Layout에 추가합니다. "
            + "AI 배치와 달리 서버는 좌표를 옮기거나 clamp하지 않습니다 — 방 밖이거나 다른 가구와 겹쳐도 추가는 허용되며, "
            + "결과 validationResult.issues로 어떤 가구가 왜 문제인지 알려줍니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "가구 추가 성공(충돌/경계 이슈가 있어도 성공)"),
            @ApiResponse(responseCode = "400", description = "PRODUCT_NOT_FOUND, INVALID_REQUEST_BODY"),
            @ApiResponse(responseCode = "404", description = "존재하지 않는 layoutId"),
            @ApiResponse(responseCode = "409", description = "이미 확정된 배치(ALREADY_CONFIRMED)")
    })
    public CommonResponse<LayoutResponse> addFurnitureDirect(@PathVariable Long layoutId,
                                                              @RequestBody AddFurnitureRequest request) {
        return CommonResponse.ok(layoutService.addFurnitureDirect(layoutId, request));
    }

    @PostMapping("/recommend")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "배치 추천 생성", description = "Agent Context를 기반으로 기존 가구와 선택 제품을 고려한 추천 배치를 생성합니다. recommendedFurniture, validationResult, scoreSummary를 반환합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "추천 생성 성공"),
            @ApiResponse(responseCode = "400", description = "요청 roomId와 Context roomId 불일치"),
            @ApiResponse(responseCode = "404", description = "존재하지 않는 roomId 또는 contextId")
    })
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "추천 대상 roomId와 Agent Context ID. roomId는 Context의 roomId와 같아야 합니다.",
            required = true,
            content = @Content(examples = @ExampleObject(name = "Recommend from context", value = """
                    {
                      "roomId": 1,
                      "contextId": 1
                    }
                    """)))
    public CommonResponse<LayoutResponse> recommend(@RequestBody RecommendRequest request) {
        return CommonResponse.ok(layoutService.recommend(request));
    }

    @GetMapping("/{layoutId}")
    @Operation(summary = "배치 조회", description = "저장된 Layout의 전체 가구, 확정 상태, 원본 Layout ID, 최신 검증 및 점수를 반환합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "배치 조회 성공"),
            @ApiResponse(responseCode = "404", description = "존재하지 않는 layoutId")
    })
    public CommonResponse<LayoutResponse> getLayout(@PathVariable Long layoutId) {
        return CommonResponse.ok(layoutService.getLayout(layoutId));
    }

    @GetMapping("/rooms/{roomId}/confirmed/latest")
    @Operation(summary = "방의 최신 확정 배치 조회", description = "저장된 인테리어 재편집에 사용할 방의 최신 확정 Layout을 반환합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "최신 확정 배치 조회 성공"),
            @ApiResponse(responseCode = "404", description = "방 또는 확정 Layout 없음")
    })
    public CommonResponse<LayoutResponse> getLatestConfirmedLayout(@PathVariable Long roomId) {
        return CommonResponse.ok(layoutService.getLatestConfirmedLayout(roomId));
    }

    @PostMapping("/{layoutId}/draft")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "확정 배치 재편집 Draft 생성", description = "확정 Layout을 변경하지 않고 전체 가구 metadata와 배치를 deep copy한 새 미확정 Draft를 생성합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Draft 생성 성공"),
            @ApiResponse(responseCode = "400", description = "원본 Layout의 boundary가 유효하지 않음"),
            @ApiResponse(responseCode = "404", description = "존재하지 않는 layoutId"),
            @ApiResponse(responseCode = "409", description = "원본 Layout이 확정 상태가 아님")
    })
    public CommonResponse<LayoutResponse> createDraft(@PathVariable Long layoutId) {
        return CommonResponse.ok(layoutService.createDraft(layoutId));
    }

    @PostMapping("/validate")
    @Operation(summary = "현재 배치 검증", description = "사용자가 가구를 드래그하는 중 현재 화면의 가구 배치가 충돌/경계/문/창문/동선 조건을 만족하는지 검증합니다. 저장은 수행하지 않습니다. furniture 배열에는 현재 layoutId에 포함된 전체 furniture id 목록을 전달해야 합니다. 각 item은 full furniture object가 아니라 id, position, rotation, status 중심의 compact update item입니다. width/depth/height/productId/variantId/styleTags 등은 요청 필드가 아니라 백엔드 추천 결과 메타데이터입니다. 일부 가구 id만 전달하면 FURNITURE_ARRAY_MISMATCH가 발생할 수 있습니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "배치 검증 성공"),
            @ApiResponse(responseCode = "400", description = "FURNITURE_ARRAY_MISMATCH, FURNITURE_NOT_FOUND, INVALID_ROTATION, INVALID_FURNITURE_POSITION, INVALID_FURNITURE_STATUS"),
            @ApiResponse(responseCode = "404", description = "존재하지 않는 layoutId")
    })
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "검증할 현재 가구 배치 전체 배열. 전체 furniture id를 포함하되 full furniture object는 보내지 않습니다.",
            required = true,
            content = @Content(examples = @ExampleObject(name = "Validate current layout", value = """
                    {
                      "layoutId": 1,
                      "furniture": [
                        { "id": "bed-1", "position": { "x": 0.8, "z": 1.4 }, "rotation": 0, "status": "EXISTING" },
                        { "id": "desk-rec-1", "position": { "x": 2.3, "z": 1.0 }, "rotation": 0, "status": "USER_MODIFIED" },
                        { "id": "chair-rec-1", "position": { "x": 2.3, "z": 1.8 }, "rotation": 0, "status": "USER_MODIFIED" }
                      ]
                    }
                    """)))
    public CommonResponse<ValidationResult> validate(@RequestBody ValidateRequest request) {
        return CommonResponse.ok(layoutService.validateOnly(request));
    }

    @PutMapping("/{layoutId}")
    @Operation(summary = "수정 배치 저장", description = "사용자가 수정한 최종 가구 배치를 저장하고 validationResult와 scoreSummary를 다시 계산합니다. furniture 배열에는 현재 layout의 전체 furniture id 목록을 compact update item 형태로 전달합니다. width/depth/height/productId/variantId/styleTags 등은 요청 필드가 아니라 백엔드 추천 결과 메타데이터입니다. 이미 확정된 layout은 수정할 수 없으며 409 ALREADY_CONFIRMED가 반환됩니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "수정 배치 저장 성공"),
            @ApiResponse(responseCode = "400", description = "FURNITURE_ARRAY_MISMATCH, FURNITURE_NOT_FOUND, INVALID_ROTATION, INVALID_FURNITURE_POSITION, INVALID_FURNITURE_STATUS"),
            @ApiResponse(responseCode = "404", description = "존재하지 않는 layoutId"),
            @ApiResponse(responseCode = "409", description = "이미 확정된 배치(ALREADY_CONFIRMED)")
    })
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "저장할 최종 가구 배치 전체 배열. 전체 furniture id를 포함하되 full furniture object는 보내지 않습니다.",
            required = true,
            content = @Content(examples = @ExampleObject(name = "Save modified layout", value = """
                    {
                      "furniture": [
                        { "id": "bed-1", "position": { "x": 0.8, "z": 1.4 }, "rotation": 0, "status": "EXISTING" },
                        { "id": "desk-rec-1", "position": { "x": 2.3, "z": 1.0 }, "rotation": 0, "status": "USER_MODIFIED" },
                        { "id": "chair-rec-1", "position": { "x": 2.3, "z": 1.8 }, "rotation": 0, "status": "USER_MODIFIED" }
                      ]
                    }
                    """)))
    public CommonResponse<LayoutResponse> updateLayout(@PathVariable Long layoutId,
                                                         @RequestBody LayoutUpdateRequest request) {
        return CommonResponse.ok(layoutService.updateLayout(layoutId, request));
    }

    @PostMapping("/{layoutId}/furniture-additions")
    @Operation(summary = "Draft 추가 희망 가구 배치", description = "현재 Draft의 기존 가구 위치와 metadata를 유지하고 Agent Context의 requiredItems를 결정론적으로 안전한 위치에 추가합니다. 한 요청의 신규 가구는 최대 8개, DELETED를 제외한 최종 활성 가구는 최대 12개입니다. 같은 type의 반복 항목도 각각 한 가구로 계산합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "추가 가구 배치 또는 변경 없음"),
            @ApiResponse(responseCode = "400", description = "Room과 Context 불일치 또는 잘못된 요청"),
            @ApiResponse(responseCode = "404", description = "존재하지 않는 Layout 또는 Context"),
            @ApiResponse(responseCode = "409", description = "이미 확정된 Layout"),
            @ApiResponse(responseCode = "422", description = "선택한 가구를 안전하게 배치하지 못함")
    })
    public CommonResponse<LayoutResponse> addFurniture(@PathVariable Long layoutId,
                                                        @RequestBody DraftFurnitureAdditionRequest request) {
        return CommonResponse.ok(layoutService.addFurniture(layoutId, request));
    }

    @PostMapping("/{layoutId}/confirm")
    @Operation(summary = "최종 배치 확정", description = "사용자가 추천 또는 수정한 배치를 최종 확정합니다. 이미 확정된 layout은 다시 확정할 수 없으며 409 ALREADY_CONFIRMED가 반환됩니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "최종 배치 확정 성공"),
            @ApiResponse(responseCode = "404", description = "존재하지 않는 layoutId"),
            @ApiResponse(responseCode = "409", description = "이미 확정된 배치(ALREADY_CONFIRMED)")
    })
    public CommonResponse<ConfirmResponse> confirmLayout(@PathVariable Long layoutId) {
        return CommonResponse.ok(layoutService.confirmLayout(layoutId));
    }

    @PostMapping("/feedback")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "사용자 피드백 기반 재추천", description = "자연어 피드백을 의미 기반 Plan으로 해석하고 MOVE, ROTATE, REPLACE_PRODUCT, ADD_FURNITURE, REMOVE_FURNITURE, SWAP_FURNITURE를 결정론적으로 검증·실행합니다. LLM은 좌표나 제품 ID를 생성하지 않습니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "피드백 기반 재추천 성공"),
            @ApiResponse(responseCode = "400", description = "UNSUPPORTED_FEEDBACK_INTENT"),
            @ApiResponse(responseCode = "404", description = "LAYOUT_NOT_FOUND, CONTEXT_NOT_FOUND, ROOM_NOT_FOUND")
    })
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "배치 이동/회전/제품 교체와 가구 추가/제거/타입 교체 피드백. 모호하거나 지원하지 않는 요청은 실행하지 않습니다.",
            required = true,
            content = @Content(examples = @ExampleObject(name = "Larger desk feedback", value = """
                    {
                      "layoutId": 1,
                      "feedback": "책상 더 크게"
                    }
                    """)))
    public CommonResponse<FeedbackResponse> feedback(@RequestBody FeedbackRequest request) {
        return CommonResponse.ok(layoutService.feedback(request));
    }
}
