package com.roomfit.room;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 붙박이 고정설비({@link Furniture#isImmovableFixture()})는 배치 엔진·피드백·LLM 어느 경로에서도
 * 옮기거나 지우면 안 된다. 냉장고처럼 옮길 수 있는 가전은 여기서 고정하지 않는다.
 * 엔진마다 막는 대신, 결과 스냅샷을 원본 기준으로 되돌리는 안전망을 한 곳에 둔다.
 */
public final class Fixtures {

    private Fixtures() {
    }

    public static List<Furniture> only(List<Furniture> furniture) {
        return furniture == null ? List.of() : furniture.stream().filter(Furniture::isImmovableFixture).map(Fixtures::copy).toList();
    }

    /**
     * "기존 가구 버리기"에서도 남길 항목: 붙박이 설비 + 냉장고 같은 가전. 둘 다 카탈로그 상품이 아니라
     * 추천이 대신 사줄 수 없는 방의 일부다.
     */
    public static List<Furniture> keptOnDiscard(List<Furniture> furniture) {
        return furniture == null ? List.of()
                : furniture.stream().filter(item -> FixtureType.isFixture(item.getType())).map(Fixtures::copy).toList();
    }

    public static List<Furniture> withoutFixtures(List<Furniture> furniture) {
        return furniture == null ? List.of() : furniture.stream().filter(item -> !item.isImmovableFixture()).toList();
    }

    /**
     * result 안의 고정설비를 baseline 상태(위치·회전·크기·상태)로 되돌리고, 빠진 고정설비는 다시 넣는다.
     * baseline에 없는 고정설비(엔진이 만들어낸 것)는 버린다.
     */
    public static List<Furniture> pin(List<Furniture> baseline, List<Furniture> result) {
        Map<String, Furniture> fixturesById = new LinkedHashMap<>();
        for (Furniture item : baseline == null ? List.<Furniture>of() : baseline) {
            if (item.isImmovableFixture()) fixturesById.put(item.getId(), item);
        }
        List<Furniture> pinned = new ArrayList<>();
        Map<String, Furniture> remaining = new LinkedHashMap<>(fixturesById);
        for (Furniture item : result == null ? List.<Furniture>of() : result) {
            Furniture original = fixturesById.get(item.getId());
            if (original != null) {
                if (remaining.remove(item.getId()) != null) pinned.add(copy(original));
            } else if (!item.isImmovableFixture()) {
                pinned.add(item);
            }
        }
        remaining.values().forEach(item -> pinned.add(copy(item)));
        return pinned;
    }

    private static Furniture copy(Furniture item) {
        return new Furniture(item.getId(), item.getType(), item.getLabel(), item.getWidth(), item.getDepth(),
                item.getHeight(), new Position(item.getPosition().getX(), item.getPosition().getZ()), item.getRotation(),
                item.getStatus(), item.getProductId(), item.getStyleTags(), item.getVariantId()).copyMountFrom(item);
    }
}
