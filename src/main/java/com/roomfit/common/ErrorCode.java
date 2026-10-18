package com.roomfit.common;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    ROOM_NOT_FOUND(HttpStatus.NOT_FOUND, "해당 방을 찾을 수 없습니다."),
    CONTEXT_NOT_FOUND(HttpStatus.NOT_FOUND, "해당 Agent Context를 찾을 수 없습니다."),
    LAYOUT_NOT_FOUND(HttpStatus.NOT_FOUND, "해당 배치를 찾을 수 없습니다."),
    FURNITURE_NOT_FOUND(HttpStatus.NOT_FOUND, "해당 가구를 찾을 수 없습니다."),
    STYLE_IMAGE_NOT_FOUND(HttpStatus.NOT_FOUND, "해당 취향 이미지를 찾을 수 없습니다."),
    PRODUCT_NOT_FOUND(HttpStatus.BAD_REQUEST, "해당 Mock Product를 찾을 수 없습니다."),
    INVALID_FURNITURE_STATUS(HttpStatus.BAD_REQUEST, "가구 status 값이 올바르지 않습니다."),
    INVALID_LIFESTYLE_GOAL(HttpStatus.BAD_REQUEST, "생활 목적 값이 올바르지 않습니다."),
    INVALID_DESIGN_STYLE(HttpStatus.BAD_REQUEST, "디자인 취향 값이 올바르지 않습니다."),
    INVALID_PREFERRED_COLOR_TONE(HttpStatus.BAD_REQUEST, "선호 색감 톤 값이 올바르지 않습니다."),
    INVALID_FURNITURE_TYPE(HttpStatus.BAD_REQUEST, "가구 타입 값이 올바르지 않습니다."),
    ROOM_CONTEXT_MISMATCH(HttpStatus.BAD_REQUEST, "요청한 방과 Agent Context의 방이 일치하지 않습니다."),
    INVALID_ROOM_DIMENSION(HttpStatus.BAD_REQUEST, "방 크기 값이 올바르지 않습니다."),
    INVALID_OPENING_DATA(HttpStatus.BAD_REQUEST, "문/창문 데이터가 올바르지 않습니다."),
    REQUIRED_ITEM_EMPTY(HttpStatus.BAD_REQUEST, "requiredItems는 최소 1개 이상이어야 합니다."),
    STYLE_IMAGE_EMPTY(HttpStatus.BAD_REQUEST, "selectedImageIds는 최소 1개 이상이어야 합니다."),
    INVALID_FURNITURE_POSITION(HttpStatus.BAD_REQUEST, "가구 좌표가 방 범위를 벗어납니다."),
    INVALID_ROTATION(HttpStatus.BAD_REQUEST, "rotation 값이 올바르지 않습니다."),
    FURNITURE_ARRAY_MISMATCH(HttpStatus.BAD_REQUEST, "요청 furniture 배열이 기존 배치와 일치하지 않습니다."),
    UNSUPPORTED_FEEDBACK_INTENT(HttpStatus.BAD_REQUEST, "지원하지 않는 피드백입니다."),
    FEEDBACK_LLM_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, "AI 피드백 연결이 설정되지 않았습니다."),
    FEEDBACK_LLM_FAILED(HttpStatus.BAD_GATEWAY, "AI 피드백 요청을 처리하지 못했습니다. 현재 배치는 유지됩니다."),
    FEEDBACK_ROOM_CHANGED(HttpStatus.CONFLICT, "방 치수가 변경되었습니다. 공간을 다시 불러온 뒤 요청해 주세요."),
    FEEDBACK_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "AI 피드백 요청이 많습니다. 잠시 후 다시 시도해 주세요."),
    CONSULTATION_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, "AI 디자인 상담 연결이 설정되지 않았습니다. 입력 내용은 유지됩니다."),
    CONSULTATION_FAILED(HttpStatus.BAD_GATEWAY, "취향 분석을 완료하지 못했습니다. 입력 내용을 유지했으니 잠시 후 다시 시도해 주세요."),
    CONSULTATION_RESPONSE_INVALID(HttpStatus.BAD_GATEWAY, "AI 분석 결과를 확인하지 못했습니다. 입력은 유지됩니다. 다시 분석해 주세요."),
    CONSULTATION_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "취향 분석 요청이 많습니다. 잠시 후 다시 시도해 주세요."),
    INVALID_CONSULTATION_IMAGE(HttpStatus.BAD_REQUEST, "첨부 사진을 읽을 수 없습니다. JPG·PNG 사진과 용량을 확인해 주세요."),
    CONSULTATION_UPLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "첨부 용량이 너무 큽니다. 사진 한 장은 5 MB, 전체는 3장 이내로 줄여 주세요."),
    INVALID_REQUEST_BODY(HttpStatus.BAD_REQUEST, "요청 본문이 올바르지 않습니다."),
    ROOM_DELETE_NOT_ALLOWED(HttpStatus.FORBIDDEN, "ROOMPLAN으로 업로드한 방만 삭제할 수 있습니다."),
    ROOM_COPY_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "샘플 방만 복제할 수 있습니다."),
    CLIENT_ID_REQUIRED(HttpStatus.BAD_REQUEST, "X-RoomFit-Client-Id 헤더가 필요합니다."),
    INVALID_CLIENT_ID(HttpStatus.BAD_REQUEST, "X-RoomFit-Client-Id는 UUID 형식이어야 합니다."),
    PAIRING_CODE_NOT_FOUND(HttpStatus.NOT_FOUND, "유효하지 않거나 만료된 코드입니다."),
    ALREADY_CONFIRMED(HttpStatus.CONFLICT, "이미 확정된 배치입니다."),
    LAYOUT_NOT_CONFIRMED(HttpStatus.CONFLICT, "확정된 배치만 편집 Draft로 복제할 수 있습니다."),
    FURNITURE_ADDITION_FAILED(HttpStatus.UNPROCESSABLE_ENTITY, "선택한 추가 가구를 안전하게 배치할 수 없습니다."),
    FURNITURE_DOMAIN_CONFLICT(HttpStatus.UNPROCESSABLE_ENTITY, "로프트 침대와 일반 책상은 동시에 배치할 수 없습니다."),
    RECOMMENDATION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "배치 추천에 실패했습니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 처리 중 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }
}
