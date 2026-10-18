package com.roomfit.placement;

import com.roomfit.product.catalog.GeneratedFurnitureCatalog;
import com.roomfit.room.Furniture;
import com.roomfit.room.FurnitureBoundary;
import com.roomfit.room.FurnitureStatus;

final class FurnitureSupportPolicy {

    static final double POSITION_EPSILON = 1.0e-6;

    private FurnitureSupportPolicy() {
    }

    static boolean isStrictStack(Furniture first, Furniture second) {
        SupportPair pair = SupportPair.resolve(first, second);
        if (pair == null || !active(pair.supporter()) || !active(pair.dependent())) return false;
        FurnitureBoundary.Footprint footprint = FurnitureBoundary.footprint(pair.supporter());
        double localX = pair.dependent().getPosition().getX() - pair.supporter().getPosition().getX();
        double localZ = pair.dependent().getPosition().getZ() - pair.supporter().getPosition().getZ();
        return localX >= footprint.minX() - POSITION_EPSILON
                && localX <= footprint.maxX() + POSITION_EPSILON
                && localZ >= footprint.minZ() - POSITION_EPSILON
                && localZ <= footprint.maxZ() + POSITION_EPSILON;
    }

    /**
     * Full exemption for furniture pairs that are expected to sit tight
     * against/inside each other during normal use (a chair pushed under its
     * desk) — unlike {@link #isStrictStack}, this doesn't require the
     * dependent's center to fall inside the other's footprint, since a chair
     * pushed under a desk usually only partially overlaps it (seat under the
     * desktop, back sticking out). Any overlap between the pair — body
     * collision or clearance-zone intrusion — is ignored entirely.
     */
    static boolean isCollisionExempt(Furniture first, Furniture second) {
        if (!active(first) || !active(second)) return false;
        // 하부장과 거기 박힌 싱크대·가스레인지처럼 붙박이 설비끼리는 원래 한 덩어리다. 둘 다 옮길 수
        // 없으니 서로 겹쳐도 사용자가 고칠 문제가 아니다.
        if (first.isImmovableFixture() && second.isImmovableFixture()) return true;
        String firstType = GeneratedFurnitureCatalog.get().normalizeType(first.getType());
        String secondType = GeneratedFurnitureCatalog.get().normalizeType(second.getType());
        return isDeskChairPair(firstType, secondType);
    }

    // 의자는 책상·테이블 아래로 밀어 넣어 두는 게 보통이라 일부 겹쳐도 충돌이 아니다.
    private static final java.util.Set<String> CHAIR_TUCK_SURFACES = java.util.Set.of("desk", "multi_table");

    private static boolean isDeskChairPair(String firstType, String secondType) {
        return ("desk_chair".equals(secondType) && CHAIR_TUCK_SURFACES.contains(firstType))
                || ("desk_chair".equals(firstType) && CHAIR_TUCK_SURFACES.contains(secondType));
    }

    private static boolean active(Furniture furniture) {
        return furniture != null
                && furniture.getPosition() != null
                && furniture.getStatus() != FurnitureStatus.DELETED;
    }

    private record SupportPair(Furniture supporter, Furniture dependent) {

        private static SupportPair resolve(Furniture first, Furniture second) {
            if (first == null || second == null) return null;
            String firstType = GeneratedFurnitureCatalog.get().normalizeType(first.getType());
            String secondType = GeneratedFurnitureCatalog.get().normalizeType(second.getType());
            if (supported(firstType, secondType)) return new SupportPair(first, second);
            if (supported(secondType, firstType)) return new SupportPair(second, first);
            return null;
        }

        private static boolean supported(String supporterType, String dependentType) {
            return ("desk".equals(supporterType) && "monitor".equals(dependentType))
                    || ("media_console".equals(supporterType) && "tv".equals(dependentType));
        }
    }
}
