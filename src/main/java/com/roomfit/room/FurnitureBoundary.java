package com.roomfit.room;

import com.roomfit.product.catalog.GeneratedFurnitureCatalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Visual-footprint boundary policy shared by uploads, validation and placement candidates.
 */
public final class FurnitureBoundary {

    public static final double WALL_CLEARANCE_METERS = 0.02;
    public static final double DEFAULT_WALL_THICKNESS_METERS = 0.12;
    public static final double EPSILON = 1.0e-9;
    private static final double RIGHT_ANGLE_TOLERANCE_DEGREES = 1.0e-4;
    private static final double WALL_CHAIN_TOLERANCE_METERS = 0.05;
    private static final double AXIS_ALIGNED_EDGE_TOLERANCE_METERS = 0.01;
    private static final double POLYGON_CLAMP_STEP_METERS = 0.05;
    private static final int POLYGON_CLAMP_MAX_STEPS_PER_AXIS = 400;

    private FurnitureBoundary() {
    }

    public static Footprint footprint(Furniture furniture) {
        return footprint(furniture.getWidth(), furniture.getDepth(), furniture.getRotation(), furniture.getVariantId());
    }

    public static Footprint footprint(double width, double depth, double rotationDegrees) {
        return footprint(width, depth, rotationDegrees, null);
    }

    public static Footprint footprint(double width, double depth, double rotationDegrees, String variantId) {
        LocalFootprint local = resolveLocalFootprint(width, depth, variantId);
        double normalizedRotation = normalizeDegrees(rotationDegrees);
        double nearestRightAngle = Math.rint(normalizedRotation / 90.0) * 90.0;
        double radians = Math.toRadians(
                Math.abs(normalizedRotation - nearestRightAngle) <= RIGHT_ANGLE_TOLERANCE_DEGREES
                        ? nearestRightAngle
                        : normalizedRotation);
        double cosine = Math.cos(radians);
        double sine = Math.sin(radians);

        List<Offset> corners = List.of(
                rotate(local.minX(), local.minZ(), cosine, sine),
                rotate(local.maxX(), local.minZ(), cosine, sine),
                rotate(local.maxX(), local.maxZ(), cosine, sine),
                rotate(local.minX(), local.maxZ(), cosine, sine)
        );
        double minX = corners.stream().mapToDouble(Offset::x).min().orElseThrow();
        double maxX = corners.stream().mapToDouble(Offset::x).max().orElseThrow();
        double minZ = corners.stream().mapToDouble(Offset::z).min().orElseThrow();
        double maxZ = corners.stream().mapToDouble(Offset::z).max().orElseThrow();
        return new Footprint(maxX - minX, maxZ - minZ, minX, maxX, minZ, maxZ, corners);
    }

    public static LocalFootprint resolveLocalFootprint(double width, double depth, String variantId) {
        if (variantId != null) {
            Optional<GeneratedFurnitureCatalog.VisualFootprint> generated =
                    GeneratedFurnitureCatalog.get().visualFootprint(variantId);
            if (generated.isPresent()) {
                GeneratedFurnitureCatalog.VisualFootprint footprint = generated.get();
                return new LocalFootprint(
                        footprint.minX(), footprint.maxX(), footprint.minZ(), footprint.maxZ(),
                        FootprintSource.VARIANT_VISUAL);
            }
        }

        // Null variants use the legacy renderer. Unknown non-null variants also
        // route to that renderer in Web, so both explicitly fall back to the
        // centered nominal dimensions rather than an invented safety margin.
        return new LocalFootprint(-width / 2.0, width / 2.0, -depth / 2.0, depth / 2.0,
                variantId == null ? FootprintSource.LEGACY_NOMINAL : FootprintSource.UNKNOWN_VARIANT_NOMINAL);
    }

    public static boolean isInside(Room room, Furniture furniture) {
        return isInside(room, furniture.getPosition(), footprint(furniture));
    }

    // Non-rectangular rooms (L-shaped studios, etc.) are handled by walking the
    // room's own wall polygon instead of a single axis-aligned bounding box —
    // see `orderedPolygon`. Simple rectangular rooms (today's common case, and
    // every existing test fixture) deliberately keep using the original
    // per-side-inset arithmetic below unchanged: `orderedPolygon` returns
    // empty for them on purpose, so this method's behavior for a rectangle is
    // byte-for-byte the same as before this class supported concave rooms.
    public static boolean isInside(Room room, Position center, Footprint footprint) {
        if (!finitePosition(center)) {
            return false;
        }
        Optional<List<Position>> polygon = orderedPolygon(room);
        if (polygon.isPresent()) {
            return isInsidePolygon(room, polygon.get(), center, footprint);
        }
        return isInsideRectangle(room, center, footprint);
    }

    private static boolean isInsideRectangle(Room room, Position center, Footprint footprint) {
        Optional<UsableBounds> usable = usableBoundsRectangle(room);
        if (usable.isEmpty()) {
            return false;
        }
        UsableBounds bounds = usable.get();
        return footprint.corners().stream().allMatch(corner -> {
            double x = center.getX() + corner.x();
            double z = center.getZ() + corner.z();
            return x >= bounds.minX() - EPSILON && x <= bounds.maxX() + EPSILON
                    && z >= bounds.minZ() - EPSILON && z <= bounds.maxZ() + EPSILON;
        });
    }

    private static boolean isInsidePolygon(Room room, List<Position> polygon, Position center, Footprint footprint) {
        List<Wall> walls = room.getWalls();
        for (Offset corner : footprint.corners()) {
            double x = center.getX() + corner.x();
            double z = center.getZ() + corner.z();
            if (!pointInPolygon(x, z, polygon)) {
                return false;
            }
            for (Wall wall : walls) {
                double clearance = wall.getThickness() / 2.0 + WALL_CLEARANCE_METERS;
                if (distanceToWallSegment(x, z, wall) < clearance - EPSILON) {
                    return false;
                }
            }
        }
        return true;
    }

    public static Optional<Position> clamp(Room room, Position proposed, Furniture furniture) {
        return clamp(room, proposed, footprint(furniture));
    }

    public static Optional<Position> clamp(Room room, Position proposed, Footprint footprint) {
        if (!finitePosition(proposed)) {
            return Optional.empty();
        }
        Optional<List<Position>> polygon = orderedPolygon(room);
        if (polygon.isPresent()) {
            return clampToPolygon(room, polygon.get(), proposed, footprint);
        }
        return clampToRectangle(room, proposed, footprint);
    }

    private static Optional<Position> clampToRectangle(Room room, Position proposed, Footprint footprint) {
        Optional<UsableBounds> usable = usableBoundsRectangle(room);
        if (usable.isEmpty()) {
            return Optional.empty();
        }
        UsableBounds bounds = usable.get();
        double minX = bounds.minX() - footprint.minX();
        double maxX = bounds.maxX() - footprint.maxX();
        double minZ = bounds.minZ() - footprint.minZ();
        double maxZ = bounds.maxZ() - footprint.maxZ();
        if (maxX < minX - EPSILON || maxZ < minZ - EPSILON) {
            return Optional.empty();
        }
        return Optional.of(new Position(
                clamp(proposed.getX(), minX, maxX),
                clamp(proposed.getZ(), minZ, maxZ)
        ));
    }

    // No closed-form inward offset of a (possibly concave) polygon is computed
    // here — for an L-shaped room that's fiddly to get right at the reflex
    // corner. Instead: if `proposed` already clears every wall by the required
    // margin, keep it as-is; otherwise fall back to a bounded nearest-point
    // grid search within the polygon's own bounding box, reusing `isInsidePolygon`
    // as the sole source of truth for validity. Bounded by
    // `POLYGON_CLAMP_MAX_STEPS_PER_AXIS` per axis so a pathologically large
    // scanned room can't turn one clamp call into an unbounded scan.
    private static Optional<Position> clampToPolygon(Room room, List<Position> polygon, Position proposed, Footprint footprint) {
        if (isInsidePolygon(room, polygon, proposed, footprint)) {
            return Optional.of(proposed);
        }

        UsableBounds bounds = polygonBounds(polygon);
        double minX = bounds.minX() - footprint.minX();
        double maxX = bounds.maxX() - footprint.maxX();
        double minZ = bounds.minZ() - footprint.minZ();
        double maxZ = bounds.maxZ() - footprint.maxZ();
        if (maxX < minX - EPSILON || maxZ < minZ - EPSILON) {
            return Optional.empty();
        }

        int stepsX = Math.min(POLYGON_CLAMP_MAX_STEPS_PER_AXIS,
                (int) Math.ceil((maxX - minX) / POLYGON_CLAMP_STEP_METERS) + 1);
        int stepsZ = Math.min(POLYGON_CLAMP_MAX_STEPS_PER_AXIS,
                (int) Math.ceil((maxZ - minZ) / POLYGON_CLAMP_STEP_METERS) + 1);

        Position best = null;
        double bestDistanceSquared = Double.MAX_VALUE;
        for (int ix = 0; ix < stepsX; ix++) {
            double x = Math.min(maxX, minX + ix * POLYGON_CLAMP_STEP_METERS);
            for (int iz = 0; iz < stepsZ; iz++) {
                double z = Math.min(maxZ, minZ + iz * POLYGON_CLAMP_STEP_METERS);
                Position candidate = new Position(x, z);
                if (!isInsidePolygon(room, polygon, candidate, footprint)) {
                    continue;
                }
                double dx = x - proposed.getX();
                double dz = z - proposed.getZ();
                double distanceSquared = dx * dx + dz * dz;
                if (distanceSquared < bestDistanceSquared) {
                    bestDistanceSquared = distanceSquared;
                    best = candidate;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    public static Optional<UsableBounds> usableBounds(Room room) {
        Optional<List<Position>> polygon = orderedPolygon(room);
        if (polygon.isPresent()) {
            // A loose superset (the polygon's own bounding box), not a tight
            // usable region — callers that only need a search envelope (e.g.
            // RoomPlanImportValidator's candidate grid) are fine with that,
            // since `isInside`/`clamp` above remain the authoritative,
            // polygon-aware correctness gate for any candidate this produces.
            return Optional.of(polygonBounds(polygon.get()));
        }
        return usableBoundsRectangle(room);
    }

    private static Optional<UsableBounds> usableBoundsRectangle(Room room) {
        if (!finiteRoom(room)) {
            return Optional.empty();
        }
        WallInsets insets = wallInteriorInsets(room);
        UsableBounds usable = new UsableBounds(
                insets.left() + WALL_CLEARANCE_METERS,
                room.getWidth() - insets.right() - WALL_CLEARANCE_METERS,
                insets.top() + WALL_CLEARANCE_METERS,
                room.getDepth() - insets.bottom() - WALL_CLEARANCE_METERS
        );
        return usable.maxX() >= usable.minX() && usable.maxZ() >= usable.minZ()
                ? Optional.of(usable)
                : Optional.empty();
    }

    private static WallInsets wallInteriorInsets(Room room) {
        List<Wall> walls = room.getWalls();
        if (walls == null || walls.isEmpty()) {
            double inset = DEFAULT_WALL_THICKNESS_METERS / 2.0;
            return new WallInsets(inset, inset, inset, inset);
        }

        double left = 0;
        double right = 0;
        double top = 0;
        double bottom = 0;
        for (Wall wall : walls) {
            if (wall == null || wall.getStart() == null || wall.getEnd() == null) {
                continue;
            }
            double dx = wall.getEnd().getX() - wall.getStart().getX();
            double dz = wall.getEnd().getZ() - wall.getStart().getZ();
            double centerX = (wall.getStart().getX() + wall.getEnd().getX()) / 2.0;
            double centerZ = (wall.getStart().getZ() + wall.getEnd().getZ()) / 2.0;
            // A scan's explicit zero thickness is geometry, not an omitted
            // value. The fallback thickness applies only when walls are absent.
            double thickness = wall.getThickness();
            double tolerance = Math.max(thickness, 0.25);
            if (Math.abs(dz) >= Math.abs(dx)) {
                if (Math.abs(centerX) <= tolerance) {
                    left = Math.max(left, Math.max(0, centerX + thickness / 2.0));
                } else if (Math.abs(centerX - room.getWidth()) <= tolerance) {
                    right = Math.max(right,
                            Math.max(0, room.getWidth() - centerX + thickness / 2.0));
                }
            } else if (Math.abs(centerZ) <= tolerance) {
                top = Math.max(top, Math.max(0, centerZ + thickness / 2.0));
            } else if (Math.abs(centerZ - room.getDepth()) <= tolerance) {
                bottom = Math.max(bottom,
                        Math.max(0, room.getDepth() - centerZ + thickness / 2.0));
            }
        }
        return new WallInsets(left, right, top, bottom);
    }

    /**
     * Chains `room.getWalls()` end-to-end (matching endpoints within
     * `WALL_CHAIN_TOLERANCE_METERS`) into one ordered, closed polygon loop.
     * Returns empty — meaning "use the rectangle model instead" — whenever:
     *   - there are fewer than 3 walls,
     *   - the walls don't chain into a single closed loop (e.g. legacy
     *     uploads with no wall segments, or a partial/gapped scan), or
     *   - the loop it finds is a plain 4-sided axis-aligned rectangle, since
     *     that's exactly the shape the original rectangle arithmetic already
     *     handles, and this method must not change behavior for that case.
     * Genuinely non-rectangular loops (L-shapes, pentagons, rotated rooms,
     * etc.) are the only ones returned.
     */
    private static Optional<List<Position>> orderedPolygon(Room room) {
        List<Wall> walls = room.getWalls();
        if (walls == null || walls.size() < 3) {
            return Optional.empty();
        }

        List<Wall> remaining = new ArrayList<>(walls);
        List<Position> loop = new ArrayList<>();
        Wall first = remaining.remove(0);
        if (first.getStart() == null || first.getEnd() == null) {
            return Optional.empty();
        }
        loop.add(first.getStart());
        Position cursor = first.getEnd();

        while (!remaining.isEmpty()) {
            int matchIndex = -1;
            boolean reversed = false;
            for (int i = 0; i < remaining.size(); i++) {
                Wall candidate = remaining.get(i);
                if (candidate.getStart() == null || candidate.getEnd() == null) {
                    continue;
                }
                if (near(candidate.getStart(), cursor)) {
                    matchIndex = i;
                    reversed = false;
                    break;
                }
                if (near(candidate.getEnd(), cursor)) {
                    matchIndex = i;
                    reversed = true;
                    break;
                }
            }
            if (matchIndex < 0) {
                return Optional.empty();
            }
            Wall next = remaining.remove(matchIndex);
            loop.add(cursor);
            cursor = reversed ? next.getStart() : next.getEnd();
        }

        if (loop.size() < 3 || !near(cursor, loop.get(0))) {
            return Optional.empty();
        }
        return isAxisAlignedRectangle(loop) ? Optional.empty() : Optional.of(loop);
    }

    private static boolean near(Position a, Position b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ()) <= WALL_CHAIN_TOLERANCE_METERS;
    }

    private static boolean isAxisAlignedRectangle(List<Position> polygon) {
        if (polygon.size() != 4) {
            return false;
        }
        for (int i = 0; i < polygon.size(); i++) {
            Position a = polygon.get(i);
            Position b = polygon.get((i + 1) % polygon.size());
            double dx = Math.abs(b.getX() - a.getX());
            double dz = Math.abs(b.getZ() - a.getZ());
            boolean axisAligned = dx <= AXIS_ALIGNED_EDGE_TOLERANCE_METERS
                    || dz <= AXIS_ALIGNED_EDGE_TOLERANCE_METERS;
            if (!axisAligned) {
                return false;
            }
        }
        return true;
    }

    private static boolean pointInPolygon(double px, double pz, List<Position> polygon) {
        boolean inside = false;
        int n = polygon.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = polygon.get(i).getX();
            double zi = polygon.get(i).getZ();
            double xj = polygon.get(j).getX();
            double zj = polygon.get(j).getZ();
            boolean crosses = (zi > pz) != (zj > pz)
                    && px < (xj - xi) * (pz - zi) / (zj - zi) + xi;
            if (crosses) {
                inside = !inside;
            }
        }
        return inside;
    }

    private static double distanceToWallSegment(double px, double pz, Wall wall) {
        Position a = wall.getStart();
        Position b = wall.getEnd();
        double dx = b.getX() - a.getX();
        double dz = b.getZ() - a.getZ();
        double lengthSquared = dx * dx + dz * dz;
        double t = lengthSquared < EPSILON ? 0
                : ((px - a.getX()) * dx + (pz - a.getZ()) * dz) / lengthSquared;
        t = Math.max(0, Math.min(1, t));
        double nearestX = a.getX() + t * dx;
        double nearestZ = a.getZ() + t * dz;
        return Math.hypot(px - nearestX, pz - nearestZ);
    }

    private static UsableBounds polygonBounds(List<Position> polygon) {
        double minX = polygon.stream().mapToDouble(Position::getX).min().orElseThrow();
        double maxX = polygon.stream().mapToDouble(Position::getX).max().orElseThrow();
        double minZ = polygon.stream().mapToDouble(Position::getZ).min().orElseThrow();
        double maxZ = polygon.stream().mapToDouble(Position::getZ).max().orElseThrow();
        return new UsableBounds(minX, maxX, minZ, maxZ);
    }

    private static Offset rotate(double x, double z, double cosine, double sine) {
        return new Offset(x * cosine - z * sine, x * sine + z * cosine);
    }

    private static double normalizeDegrees(double rotation) {
        double normalized = rotation % 360.0;
        return normalized < 0 ? normalized + 360.0 : normalized;
    }

    private static double clamp(double value, double min, double max) {
        if (Math.abs(max - min) <= EPSILON) {
            return (min + max) / 2.0;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static boolean finiteRoom(Room room) {
        return room != null && Double.isFinite(room.getWidth()) && Double.isFinite(room.getDepth())
                && room.getWidth() > 0 && room.getDepth() > 0;
    }

    private static boolean finitePosition(Position position) {
        return position != null && Double.isFinite(position.getX()) && Double.isFinite(position.getZ());
    }

    public enum FootprintSource {
        VARIANT_VISUAL,
        LEGACY_NOMINAL,
        UNKNOWN_VARIANT_NOMINAL
    }

    public record LocalFootprint(
            double minX, double maxX, double minZ, double maxZ, FootprintSource source
    ) {
    }

    public record Offset(double x, double z) {
    }

    public record Footprint(
            double effectiveWidth,
            double effectiveDepth,
            double minX,
            double maxX,
            double minZ,
            double maxZ,
            List<Offset> corners
    ) {
        public Footprint {
            corners = List.copyOf(corners);
        }
    }

    public record UsableBounds(double minX, double maxX, double minZ, double maxZ) {
    }

    private record WallInsets(double left, double right, double top, double bottom) {
    }
}
