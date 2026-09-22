package com.roomfit.placement;

import com.roomfit.product.catalog.GeneratedFurnitureCatalog;
import com.roomfit.product.domain.MockProduct;
import com.roomfit.room.Furniture;
import com.roomfit.room.FurnitureBoundary;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 가구 본체(footprint) 앞/옆에 필요한 "작동 공간"(문 열림, 서랍 인출, 의자 빼기 등)을
 * 사각형 zone으로 계산한다.
 *
 * 문짝 폭·서랍 인출 방향처럼 가구별로 정확한 zone을 저작하려면 카탈로그 스키마
 * 확장(clearanceZones)이 필요하다 — 이번 1차 구현에서는 그 스키마 변경 없이,
 * 이미 모든 Catalog product가 갖고 있는 requiredClearance(front/side)를 재사용해
 * 전면 1개 + 좌우 2개의 zone 사각형을 만드는 것으로 근사한다. 타입별 정밀 zone
 * 저작은 후속 작업으로 남는다.
 */
final class FurnitureClearance {

    private static final double[] DEFAULT_CLEARANCE = {0.3, 0.1};
    // GeneratedFurnitureCatalog의 21개 canonical type에 없는 레거시 전용 타입.
    private static final Map<String, double[]> LEGACY_TYPE_OVERRIDES = Map.of(
            "storage", new double[]{0.5, 0.2}
    );
    private static final Map<String, double[]> BY_CANONICAL_TYPE = buildFromCatalog();

    private FurnitureClearance() {
    }

    private static Map<String, double[]> buildFromCatalog() {
        Map<String, double[]> byType = new LinkedHashMap<>();
        for (MockProduct product : GeneratedFurnitureCatalog.get().products()) {
            byType.putIfAbsent(product.getType(), new double[]{
                    product.getRequiredClearance().getFront(),
                    product.getRequiredClearance().getSide()
            });
        }
        return Map.copyOf(byType);
    }

    private static double[] frontSide(String rawType) {
        String canonical = GeneratedFurnitureCatalog.get().normalizeType(rawType);
        if (canonical != null && BY_CANONICAL_TYPE.containsKey(canonical)) {
            return BY_CANONICAL_TYPE.get(canonical);
        }
        String key = rawType == null ? "" : rawType.trim().toLowerCase(Locale.ROOT);
        return LEGACY_TYPE_OVERRIDES.getOrDefault(key, DEFAULT_CLEARANCE);
    }

    /**
     * 이 가구의 전면/좌측/우측 작동 공간을 월드 좌표 사각형(4개 코너, 회전 반영)으로 반환한다.
     * 러그처럼 clearance가 0인 타입은 빈 목록을 반환한다.
     */
    static List<Zone> zonesWorld(Furniture furniture) {
        double[] clearance = frontSide(furniture.getType());
        double front = clearance[0];
        double side = clearance[1];
        if (front <= 1.0e-6 && side <= 1.0e-6) {
            return List.of();
        }

        FurnitureBoundary.LocalFootprint local = FurnitureBoundary.resolveLocalFootprint(
                furniture.getWidth(), furniture.getDepth(), furniture.getVariantId());

        List<Zone> zones = new ArrayList<>(3);
        if (front > 1.0e-6) {
            zones.add(worldZone(furniture, local.minX(), local.maxZ(), local.maxX(), local.maxZ() + front, "front"));
        }
        if (side > 1.0e-6) {
            zones.add(worldZone(furniture, local.minX() - side, local.minZ(), local.minX(), local.maxZ(), "left"));
            zones.add(worldZone(furniture, local.maxX(), local.minZ(), local.maxX() + side, local.maxZ(), "right"));
        }
        return zones;
    }

    private static Zone worldZone(Furniture furniture, double minX, double minZ, double maxX, double maxZ,
                                   String side) {
        double[] localXs = {minX, maxX, maxX, minX};
        double[] localZs = {minZ, minZ, maxZ, maxZ};
        List<double[]> corners = new ArrayList<>(4);
        for (int i = 0; i < 4; i++) {
            FurnitureBoundary.Offset rotated = FurnitureBoundary.rotateLocalOffset(
                    localXs[i], localZs[i], furniture.getRotation());
            corners.add(new double[]{
                    furniture.getPosition().getX() + rotated.x(),
                    furniture.getPosition().getZ() + rotated.z()
            });
        }
        return new Zone(side, corners);
    }

    record Zone(String side, List<double[]> corners) {
    }
}
