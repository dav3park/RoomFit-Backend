package com.roomfit.room;

import com.roomfit.common.CustomException;
import com.roomfit.common.ErrorCode;
import com.roomfit.placement.ValidationResult;
import com.roomfit.placement.ValidationService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** RoomPlan 업로드 전용 정규화: 겹침·방 범위 오차만 바로잡고 스캔된 배치를 최대한 그대로 둔다. */
@Component
public class RoomPlanImportValidator {

    public static final double GRID_STEP_METERS = 0.10;
    public static final int MAX_CANDIDATE_SEARCHES = 4_000;

    private final ValidationService validationService;

    public RoomPlanImportValidator(ValidationService validationService) {
        this.validationService = validationService;
    }

    public void validateAndNormalize(Room room) {
        validateWallGeometry(room);
        List<RoomImportWarning> warnings = new ArrayList<>();
        addWallWarnings(room, warnings);
        // 업로드는 "지금 방에 가구가 어떻게 놓여 있는지"를 그대로 보여주는 게 목적이다. 그래서 문/창 앞
        // 여유 공간·동선 같은 배치 권장 규칙은 보지 않고, 다른 가구와 겹치는지와 방 안에 들어가는지만
        // 검사해 스캔 오차만 바로잡는다. 붙박이 설비는 스캔 위치 그대로 두고 그 위에 일반 가구를 검사한다.
        List<Furniture> fixtures = Fixtures.only(room.getFurniture());
        List<Furniture> placed = new ArrayList<>(fixtures);

        for (Furniture original : Fixtures.withoutFixtures(List.copyOf(room.getFurniture()))) {
            Optional<Furniture> result = placeKeepingScan(room, placed, fixtures, original);
            if (result.isEmpty()) {
                warnings.add(warning("FURNITURE_UNPLACED", original,
                        "어떤 위치·90도 회전에서도 다른 가구와 겹치지 않고 방 안에 들어가는 배치를 찾지 못해 제외했습니다.",
                        null, original.getPosition(), null, original.getRotation(), null));
                continue;
            }
            Furniture placedItem = result.get();
            placed.add(placedItem);
            addRepositionWarningIfNeeded(original, placedItem, warnings);
        }

        room.setFurniture(placed);
        if (!physicallySafe(room, fixtures, placed)) {
            throw new IllegalStateException("RoomPlan import must persist only non-overlapping, in-bounds furniture");
        }
        room.setImportMetadata(warnings.isEmpty() ? RoomImportStatus.ACCEPTED : RoomImportStatus.ACCEPTED_WITH_WARNINGS,
                warnings);
    }

    /**
     * 스캔된 가구는 실제 위치라서 원래 방향으로 겹치지 않고 방 안에 들어가는 가장 가까운 자리에 둔다.
     * 원래 방향으로 둘 곳이 없을 때만 다른 방향까지 찾는다.
     */
    private Optional<Furniture> placeKeepingScan(Room room, List<Furniture> placed, List<Furniture> fixtures,
                                                 Furniture original) {
        return findPlacement(room, placed, fixtures, original, List.of(original.getRotation()))
                .or(() -> findPlacement(room, placed, fixtures, original, rotationOrder(original.getRotation())));
    }

    private Optional<Furniture> findPlacement(Room room, List<Furniture> placed, List<Furniture> fixtures,
                                              Furniture original, List<Double> rotations) {
        Position origin = original.getPosition();
        for (double rotation : rotations) {
            Furniture rotated = copy(original, origin, rotation);
            List<Position> candidates = candidatePositions(room, rotated, origin).stream()
                    .sorted(java.util.Comparator.comparingDouble(candidate -> distance(candidate, origin)))
                    .toList();
            for (Position candidate : candidates) {
                Furniture proposal = copy(original, candidate, rotation);
                List<Furniture> next = append(placed, proposal);
                if (physicallySafe(room, fixtures, next)) {
                    return Optional.of(proposal);
                }
            }
        }
        return Optional.empty();
    }

    private List<Position> candidatePositions(Room room, Furniture furniture, Position original) {
        Optional<FurnitureBoundary.UsableBounds> usable = FurnitureBoundary.usableBounds(room);
        if (usable.isEmpty()) return List.of();
        FurnitureBoundary.UsableBounds bounds = usable.get();
        FurnitureBoundary.Footprint footprint = FurnitureBoundary.footprint(furniture);
        double minX = bounds.minX() - footprint.minX();
        double maxX = bounds.maxX() - footprint.maxX();
        double minZ = bounds.minZ() - footprint.minZ();
        double maxZ = bounds.maxZ() - footprint.maxZ();
        if (minX > maxX || minZ > maxZ) return List.of();

        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<Position> candidates = new ArrayList<>();
        add(candidates, seen, original);
        FurnitureBoundary.clamp(room, original, furniture).ifPresent(position -> add(candidates, seen, position));

        Position anchor = FurnitureBoundary.clamp(room, original, furniture).orElse(original);
        for (int radius = 1; radius <= 8 && candidates.size() < MAX_CANDIDATE_SEARCHES; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                add(candidates, seen, bounded(anchor.getX() + dx * GRID_STEP_METERS, minX, maxX),
                        bounded(anchor.getZ() - radius * GRID_STEP_METERS, minZ, maxZ));
                add(candidates, seen, bounded(anchor.getX() + dx * GRID_STEP_METERS, minX, maxX),
                        bounded(anchor.getZ() + radius * GRID_STEP_METERS, minZ, maxZ));
            }
            for (int dz = -radius + 1; dz < radius; dz++) {
                add(candidates, seen, bounded(anchor.getX() - radius * GRID_STEP_METERS, minX, maxX),
                        bounded(anchor.getZ() + dz * GRID_STEP_METERS, minZ, maxZ));
                add(candidates, seen, bounded(anchor.getX() + radius * GRID_STEP_METERS, minX, maxX),
                        bounded(anchor.getZ() + dz * GRID_STEP_METERS, minZ, maxZ));
            }
        }
        for (double z = minZ; z <= maxZ + FurnitureBoundary.EPSILON && candidates.size() < MAX_CANDIDATE_SEARCHES; z += GRID_STEP_METERS) {
            for (double x = minX; x <= maxX + FurnitureBoundary.EPSILON && candidates.size() < MAX_CANDIDATE_SEARCHES; x += GRID_STEP_METERS) {
                add(candidates, seen, Math.min(x, maxX), Math.min(z, maxZ));
            }
        }
        return List.copyOf(candidates);
    }

    private static double distance(Position a, Position b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }

    private List<Double> rotationOrder(double original) {
        List<Double> result = new ArrayList<>(List.of(original));
        for (double candidate : List.of(0.0, 90.0, 180.0, 270.0)) {
            if (Math.abs(candidate - original) > FurnitureBoundary.EPSILON) result.add(candidate);
        }
        return result;
    }

    private ValidationResult validateAgainst(Room room, List<Furniture> baseline, List<Furniture> furniture) {
        return baseline.isEmpty()
                ? validationService.validate(room, furniture)
                : validationService.validateChange(room, baseline, furniture);
    }

    private boolean physicallySafe(Room room, List<Furniture> baseline, List<Furniture> furniture) {
        ValidationResult result = validateAgainst(room, baseline, furniture);
        return result.isCollisionFree() && result.isBoundaryValid();
    }

    private List<Furniture> append(List<Furniture> placed, Furniture proposal) {
        List<Furniture> all = new ArrayList<>(placed);
        all.add(proposal);
        return all;
    }

    private Furniture copy(Furniture item, Position position, double rotation) {
        return new Furniture(item.getId(), item.getType(), item.getLabel(), item.getWidth(), item.getDepth(),
                item.getHeight(), position, rotation, item.getStatus(), item.getProductId(), item.getStyleTags(), item.getVariantId())
                .copyMountFrom(item);
    }

    private void addRepositionWarningIfNeeded(Furniture original, Furniture normalized, List<RoomImportWarning> warnings) {
        double movement = Math.hypot(normalized.getPosition().getX() - original.getPosition().getX(),
                normalized.getPosition().getZ() - original.getPosition().getZ());
        boolean rotated = Math.abs(normalized.getRotation() - original.getRotation()) > FurnitureBoundary.EPSILON;
        if (movement > FurnitureBoundary.EPSILON || rotated) {
            warnings.add(warning(rotated ? "FURNITURE_ROTATED_AND_REPOSITIONED" : "FURNITURE_REPOSITIONED", original,
                    "RoomPlan 가구가 다른 가구와 겹치거나 방 밖으로 나와 가장 가까운 위치로 옮겼습니다.", movement,
                    original.getPosition(), normalized.getPosition(), original.getRotation(), normalized.getRotation()));
        }
    }

    private void addWallWarnings(Room room, List<RoomImportWarning> warnings) {
        // room.width/depth is only a meaningful "canonical boundary" to compare
        // wall endpoints against when the room's own walls actually form a
        // simple 4-sided axis-aligned rectangle — for a non-rectangular
        // (e.g. L-shaped) room, wall endpoints legitimately fall well inside
        // that bounding box, and flagging that as a normalization warning
        // would be noise, not signal.
        boolean isAxisAlignedRectangleRoom = isAxisAlignedRectangleRoom(room);
        for (Wall wall : room.getWalls()) {
            if (wall.getThickness() == 0) {
                warnings.add(warning("ZERO_WALL_THICKNESS_ACCEPTED", wall.getId(), null,
                        "RoomPlan이 보고한 wall thickness=0 값을 원본 그대로 유지했습니다.", null, null, null, null, null));
            }
            if (!isAxisAlignedRectangleRoom) {
                continue;
            }
            boolean dimensionDifference = nearButDifferent(wall.getStart().getX(), room.getWidth())
                    || nearButDifferent(wall.getEnd().getX(), room.getWidth())
                    || nearButDifferent(wall.getStart().getZ(), room.getDepth())
                    || nearButDifferent(wall.getEnd().getZ(), room.getDepth());
            if (dimensionDifference) {
                warnings.add(warning("ROOM_WALL_DIMENSION_NORMALIZED", wall.getId(), null,
                        "Room width/depth를 canonical boundary로 사용하고 scan wall endpoint는 원본으로 보존했습니다.",
                        null, null, null, null, null));
            }
        }
    }

    private boolean isAxisAlignedRectangleRoom(Room room) {
        List<Wall> walls = room.getWalls();
        if (walls == null || walls.isEmpty()) {
            // Nothing to compare wall endpoints against anyway — the warning
            // loop above never fires in this case regardless of this result.
            return true;
        }
        if (walls.size() != 4) {
            return false;
        }
        double tolerance = 0.01;
        for (Wall wall : walls) {
            if (wall.getStart() == null || wall.getEnd() == null) {
                return false;
            }
            double dx = Math.abs(wall.getEnd().getX() - wall.getStart().getX());
            double dz = Math.abs(wall.getEnd().getZ() - wall.getStart().getZ());
            if (dx > tolerance && dz > tolerance) {
                return false;
            }
        }
        return true;
    }

    private void validateWallGeometry(Room room) {
        Set<String> ids = new HashSet<>();
        for (Wall wall : room.getWalls()) {
            if (!finite(wall.getStart().getX()) || !finite(wall.getStart().getZ()) || !finite(wall.getEnd().getX())
                    || !finite(wall.getEnd().getZ()) || !finite(wall.getHeight()) || !finite(wall.getThickness())
                    || wall.getHeight() < 0 || wall.getThickness() < 0 || !ids.add(wall.getId())) {
                throw new CustomException(ErrorCode.INVALID_REQUEST_BODY);
            }
        }
    }

    private boolean nearButDifferent(double value, double boundary) {
        return Math.abs(value - boundary) > FurnitureBoundary.EPSILON
                && Math.abs(value - boundary) <= 0.05;
    }

    private boolean finite(double value) { return Double.isFinite(value); }
    private double bounded(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
    private void add(List<Position> values, Set<String> seen, Position value) { add(values, seen, value.getX(), value.getZ()); }
    private void add(List<Position> values, Set<String> seen, double x, double z) {
        String key = Math.round(x * 1_000_000) + ":" + Math.round(z * 1_000_000);
        if (seen.add(key)) values.add(new Position(x, z));
    }
    private RoomImportWarning warning(String code, String entityId, String furnitureType, String message,
                                      Double movement, Position original, Position normalized,
                                      Double originalRotation, Double normalizedRotation) {
        return new RoomImportWarning(code, entityId, furnitureType, message, movement, original, normalized,
                originalRotation, normalizedRotation);
    }
    private RoomImportWarning warning(String code, Furniture item, String message, Double movement,
                                      Position original, Position normalized, Double originalRotation, Double normalizedRotation) {
        return warning(code, item.getId(), item.getType(), message, movement, original, normalized,
                originalRotation, normalizedRotation);
    }
}
