package com.roomfit.placement.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "클라이언트가 카탈로그에서 고른 가구를 원하는 좌표에 직접 추가하는 요청. "
        + "AI 추천을 거치지 않고 productId 기준 치수/타입을 그대로 사용하며, 서버는 좌표를 바꾸지 않고 "
        + "검증 결과(issues 포함)만 계산해 돌려줍니다 — 방 밖/충돌이어도 추가 자체는 막지 않습니다.")
public class AddFurnitureRequest {

    @Schema(description = "MockProduct/카탈로그 productId", example = "wardrobe-tall-01")
    private String productId;
    @Schema(description = "배치할 x-z 중심 좌표")
    private FurniturePositionDto.PositionDto position;
    @Schema(description = "degree 단위 회전 각도", example = "0")
    private double rotation;

    protected AddFurnitureRequest() {
        // JSON 역직렬화용
    }

    public String getProductId() {
        return productId;
    }

    public FurniturePositionDto.PositionDto getPosition() {
        return position;
    }

    public double getRotation() {
        return rotation;
    }
}
