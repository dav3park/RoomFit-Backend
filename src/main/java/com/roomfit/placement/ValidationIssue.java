package com.roomfit.placement;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 가구별 검증 이슈. 기존 5개 boolean(collisionFree 등)·validationItems는 화면
 * 전체 체크리스트용으로 그대로 유지하고, 이 목록은 "어느 가구가 왜" 문제인지를
 * 프론트가 3D 뷰에 빨강/주황으로 표시할 수 있도록 추가한 것이다.
 */
@Schema(description = "가구별 검증 이슈. severity=ERROR는 배치 불가(빨강), WARNING은 작동 공간 침범 등 경고(주황)입니다.")
public record ValidationIssue(
        @Schema(description = "이슈가 발생한 가구 ID", example = "wardrobe-1")
        String furnitureId,
        @Schema(description = "충돌/침범 상대 가구 ID. 방 경계·문·창·동선 이슈는 null", example = "chair-1", nullable = true)
        String otherFurnitureId,
        @Schema(description = "이슈 종류")
        Type type,
        @Schema(description = "심각도. ERROR=빨강(배치 불가), WARNING=주황(경고, 배치는 허용)")
        Severity severity,
        @Schema(description = "사용자에게 표시 가능한 메시지", example = "가구가 서로 겹칩니다.")
        String message
) {

    @Schema(description = "이슈 종류", allowableValues = {
            "BODY_COLLISION", "OUT_OF_BOUNDS", "DOOR_CLEARANCE", "WINDOW_CLEARANCE", "PATH_BLOCKED", "ZONE_INTRUSION"
    })
    public enum Type {
        /** 가구 본체(footprint)끼리 겹침. */
        BODY_COLLISION,
        /** 가구 본체가 방 폴리곤/경계 밖으로 나감. */
        OUT_OF_BOUNDS,
        /** 문 앞 여유 공간을 가구 본체가 침범. */
        DOOR_CLEARANCE,
        /** 창문 앞 여유 공간을 가구 본체가 침범. */
        WINDOW_CLEARANCE,
        /** 방 동선(통로) 폭을 가구 본체가 막음. */
        PATH_BLOCKED,
        /** 다른 가구의 "작동 공간"(문 열림/서랍 인출/의자 빼기 등 zone)을 본체가 침범. */
        ZONE_INTRUSION
    }

    @Schema(description = "심각도")
    public enum Severity {
        /** 배치 불가 수준 — 3D 뷰에서 빨강으로 표시하고 확정을 막는다. */
        ERROR,
        /** 경고 수준 — 3D 뷰에서 주황으로 표시하되 배치/확정은 허용한다. */
        WARNING
    }
}
