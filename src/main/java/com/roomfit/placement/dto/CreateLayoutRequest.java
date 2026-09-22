package com.roomfit.placement.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "빈 배치 생성 요청. AI 추천(AgentContext) 없이 방의 기존 가구만 담은 미확정 Layout을 만듭니다 — "
        + "카탈로그에서 가구를 직접 드래그해 배치하는 흐름의 시작점입니다.")
public class CreateLayoutRequest {

    @Schema(description = "대상 방 ID", example = "1")
    private Long roomId;

    protected CreateLayoutRequest() {
        // JSON 역직렬화용
    }

    public Long getRoomId() {
        return roomId;
    }
}
