package com.roomfit.placement;

import com.roomfit.common.CommonResponse;
import com.roomfit.placement.dto.WorkspaceFeedbackPreviewRequest;
import com.roomfit.placement.dto.WorkspaceFeedbackPreviewResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/layouts/feedback")
public class WorkspaceFeedbackController {
    private final WorkspaceFeedbackPreviewService service;
    public WorkspaceFeedbackController(WorkspaceFeedbackPreviewService service) { this.service = service; }

    @PostMapping("/preview")
    @Operation(summary = "작업실 AI 피드백 미리보기", description = "브라우저의 최신 가구 snapshot으로 후보를 만듭니다. Room, Context, Layout을 저장하지 않습니다.")
    public CommonResponse<WorkspaceFeedbackPreviewResponse> preview(@Valid @RequestBody WorkspaceFeedbackPreviewRequest request) {
        return CommonResponse.ok(service.preview(request));
    }
}
