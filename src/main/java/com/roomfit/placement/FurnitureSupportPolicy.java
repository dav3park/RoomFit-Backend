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
        String firstType = GeneratedFurnitureCatalog.get().normalizeType(first.getType());
        String secondType = GeneratedFurnitureCatalog.get().normalizeType(second.getType());
        return isDeskChairPair(firstType, secondType);
    }

    private static boolean isDeskChairPair(String firstType, String secondType) {
        return ("desk".equals(firstType) && "desk_chair".equals(secondType))
                || ("desk_chair".equals(firstType) && "desk".equals(secondType));
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
