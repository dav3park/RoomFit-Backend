package com.roomfit.placement.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "배치 추천 생성 요청")
public class RecommendRequest {

    @Schema(description = "추천 대상 방 ID. Agent Context의 roomId와 같아야 합니다.", example = "1")
    private Long roomId;

    @Schema(description = "추천에 사용할 Agent Context ID", example = "1")
    private Long contextId;

    @Schema(description = "true면 방의 기존(스캔/사용자 배치) 가구를 모두 무시하고 빈 방에서부터 새로 추천합니다. "
            + "생략 시 false — 기존 가구 위에 새 가구를 얹는 기존 동작을 유지합니다.", example = "true", defaultValue = "false")
    private boolean discardExisting;

    protected RecommendRequest() {
        // JSON 역직렬화용
    }

    public Long getRoomId() {
        return roomId;
    }

    public Long getContextId() {
        return contextId;
    }

    public boolean isDiscardExisting() {
        return discardExisting;
    }
}
