package com.roomfit.room;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * RoomPlan이 감지하는 "옮길 수 없는 장애물". 카탈로그 상품이 아니라서 가구 타입과 분리한다.
 * 업로드 시 type 필드에 {@link #code()} 값(소문자)이 그대로 들어온다.
 * 고정설비는 배치 엔진·피드백이 이동/삭제하지 않고, 새 가구의 충돌 기준으로만 쓰인다.
 */
public enum FixtureType {
    SINK("sink", "싱크대"),
    STOVE("stove", "가스레인지"),
    OVEN("oven", "오븐"),
    REFRIGERATOR("refrigerator", "냉장고"),
    DISHWASHER("dishwasher", "식기세척기"),
    WASHER_DRYER("washer_dryer", "세탁기"),
    TOILET("toilet", "변기"),
    BATHTUB("bathtub", "욕조"),
    FIREPLACE("fireplace", "벽난로"),
    STAIRS("stairs", "계단"),
    // 벽에 붙은 붙박이 상부장(찬장). RoomPlan .storage 중 바닥에서 떠 있는 것.
    UPPER_CABINET("upper_cabinet", "상부장"),
    // 싱크대·가스레인지가 얹힌 붙박이 하부장(조리대). RoomPlan .storage 중 위에 조리기구가 있는 것.
    BASE_CABINET("base_cabinet", "하부장");

    private final String code;
    private final String label;

    FixtureType(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    public static Optional<FixtureType> fromCode(String type) {
        if (type == null || type.isBlank()) {
            return Optional.empty();
        }
        String normalized = type.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
        return Arrays.stream(values()).filter(value -> value.code.equals(normalized)).findFirst();
    }

    public static boolean isFixture(String type) {
        return fromCode(type).isPresent();
    }
}
