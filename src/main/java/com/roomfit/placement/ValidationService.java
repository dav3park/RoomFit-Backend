package com.roomfit.placement;

import com.roomfit.product.catalog.GeneratedFurnitureCatalog;
import com.roomfit.room.Furniture;
import com.roomfit.room.FurnitureBoundary;
import com.roomfit.room.FurnitureStatus;
import com.roomfit.room.Opening;
import com.roomfit.room.Position;
import com.roomfit.room.Room;
import com.roomfit.room.Wall;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 배치 검증 로직 (충돌 / 문 가림 / 창문 가림 / 동선 확보).
 * recommend와 validate/update 양쪽에서 재사용하는 별도 서비스로 분리.
 *
 * 5개 boolean(collisionFree 등)과 validationItems는 기존 프론트 체크리스트
 * 호환을 위해 그대로 유지한다. {@link ValidationIssue} 목록은 그 위에 추가된
 * 것으로, "어느 가구가 왜" 문제인지를 담아 3D 뷰의 빨강(ERROR)/주황(WARNING)
 * 표시에 쓰인다.
 */
@Service
public class ValidationService {

    private static final double MIN_PATH_WIDTH = 0.6;
    private static final double DOOR_CLEARANCE_DEPTH = 0.8;
    private static final double DOOR_SIDE_MARGIN = 0.1;
    private static final double WINDOW_CLEARANCE_DEPTH = 0.4;
    private static final double WINDOW_SIDE_MARGIN = 0.1;
    private static final double WINDOW_ATTACHMENT_EPSILON = 1.0e-6;
    private static final double SEPARATION_EPSILON = 1.0e-9;
    private static final double WALL_NORMAL_PROBE_MIN_METERS = 0.3;
    private static final String BASELINE_WARNING =
            "기존 가구의 선행 검증 문제는 신규 배치 평가에서 제외되었습니다.";

    public ValidationResult validate(Room room, List<Furniture> furniture) {
        List<String> warnings = new ArrayList<>();
        List<Furniture> activeFurniture = furniture.stream()
                .filter(item -> item.getStatus() != FurnitureStatus.DELETED)
                .toList();
        List<Furniture> physicalObstacles = activeFurniture.stream()
                .filter(item -> !FurnitureDomainPolicy.isRug(item))
                .toList();

        boolean collisionFree = checkCollisionFree(physicalObstacles, warnings);
        boolean boundaryValid = checkBoundaryValid(room, activeFurniture, warnings);
        boolean doorClearance = checkOpeningClearance(room, physicalObstacles, "door", warnings);
        boolean windowClearance = checkOpeningClearance(room, physicalObstacles, "window", warnings);
        boolean pathSecured = checkPathSecured(room, physicalObstacles, warnings);

        List<ValidationIssue> issues = new ArrayList<>();
        issues.addAll(collisionIssues(physicalObstacles, null));
        issues.addAll(boundaryIssues(room, activeFurniture));
        issues.addAll(openingIssues(room, physicalObstacles));
        issues.addAll(pathIssues(room, physicalObstacles));
        issues.addAll(zoneIssues(physicalObstacles, null));

        return result(collisionFree, boundaryValid, doorClearance, windowClearance, pathSecured, warnings, issues);
    }

    /**
     * Validates only safety debt introduced by the candidate snapshot. Uploaded
     * rooms can contain pre-existing overlaps, so unchanged baseline violations
     * must not prevent a safe new item from being added or make its score fail.
     */
    public ValidationResult validateChange(Room room, List<Furniture> baseline, List<Furniture> candidate) {
        List<Furniture> baselineItems = baseline == null ? List.of() : baseline;
        List<Furniture> candidateItems = candidate == null ? List.of() : candidate;
        List<Furniture> activeBaseline = active(baselineItems);
        List<Furniture> activeCandidate = active(candidateItems);
        Set<String> evaluatedIds = changedActiveIds(baselineItems, activeCandidate);
        List<Furniture> evaluated = activeCandidate.stream()
                .filter(item -> evaluatedIds.contains(item.getId()))
                .toList();
        List<Furniture> physicalCandidate = physical(activeCandidate);
        List<Furniture> physicalEvaluated = physical(evaluated);
        List<String> warnings = new ArrayList<>();

        boolean collisionFree = checkCollisionFreeForChanges(physicalCandidate, evaluatedIds, warnings);
        boolean boundaryValid = checkBoundaryValid(room, evaluated, warnings);
        boolean doorClearance = checkOpeningClearance(room, physicalEvaluated, "door", warnings);
        boolean windowClearance = checkOpeningClearance(room, physicalEvaluated, "window", warnings);

        List<String> ignoredWarnings = new ArrayList<>();
        boolean baselinePathSecured = checkPathSecured(room, physical(activeBaseline), ignoredWarnings);
        List<Furniture> pathScope = baselinePathSecured ? physicalCandidate : physicalEvaluated;
        boolean pathSecured = checkPathSecured(room, pathScope, warnings);

        ValidationResult baselineResult = validate(room, baselineItems);
        if (!hardValid(baselineResult)) {
            warnings.add(BASELINE_WARNING);
        }

        List<ValidationIssue> issues = new ArrayList<>();
        issues.addAll(collisionIssues(physicalCandidate, evaluatedIds));
        issues.addAll(boundaryIssues(room, evaluated));
        issues.addAll(openingIssues(room, physicalEvaluated));
        issues.addAll(pathIssues(room, pathScope));
        issues.addAll(zoneIssues(physicalCandidate, evaluatedIds));

        return result(collisionFree, boundaryValid, doorClearance, windowClearance, pathSecured, warnings, issues);
    }

    boolean isSafeAddition(Room room, List<Furniture> baseline, Furniture added) {
        List<Furniture> candidate = new ArrayList<>(baseline);
        candidate.add(added);
        return hardValid(validateChange(room, baseline, candidate));
    }

    private ValidationResult result(boolean collisionFree, boolean boundaryValid,
                                    boolean doorClearance, boolean windowClearance,
                                    boolean pathSecured, List<String> warnings, List<ValidationIssue> issues) {
        List<ValidationItem> validationItems = List.of(
                new ValidationItem("collision", collisionFree, collisionFree ? "가구 충돌 없음" : "가구 충돌 발생"),
                new ValidationItem("boundary", boundaryValid, boundaryValid ? "방 범위 내 배치" : "방 범위 밖 가구 존재"),
                new ValidationItem("door_clearance", doorClearance, doorClearance ? "문 앞 공간 확보" : "문 앞 공간 부족"),
                new ValidationItem("window_clearance", windowClearance, windowClearance ? "창문 앞 공간 확보" : "창문 앞 공간 부족"),
                new ValidationItem("path", pathSecured, pathSecured ? "이동 동선 확보" : "이동 동선 부족")
        );

        return new ValidationResult(collisionFree, boundaryValid, doorClearance,
                windowClearance, pathSecured, validationItems, warnings, issues);
    }

    private List<Furniture> active(List<Furniture> furniture) {
        return furniture.stream()
                .filter(Objects::nonNull)
                .filter(item -> item.getStatus() != FurnitureStatus.DELETED)
                .toList();
    }

    private List<Furniture> physical(List<Furniture> furniture) {
        return furniture.stream()
                .filter(item -> !FurnitureDomainPolicy.isRug(item))
                .toList();
    }

    private Set<String> changedActiveIds(List<Furniture> baseline, List<Furniture> activeCandidate) {
        Map<String, Furniture> baselineById = new HashMap<>();
        for (Furniture item : baseline) {
            if (item != null && item.getId() != null) {
                baselineById.put(item.getId(), item);
            }
        }
        Set<String> changed = new HashSet<>();
        for (Furniture item : activeCandidate) {
            Furniture previous = baselineById.get(item.getId());
            if (previous == null || previous.getStatus() == FurnitureStatus.DELETED || !samePlacement(previous, item)) {
                changed.add(item.getId());
            }
        }
        return changed;
    }

    private boolean samePlacement(Furniture first, Furniture second) {
        if (first.getPosition() == null || second.getPosition() == null) return first.getPosition() == second.getPosition();
        return Objects.equals(first.getType(), second.getType())
                && Double.compare(first.getWidth(), second.getWidth()) == 0
                && Double.compare(first.getDepth(), second.getDepth()) == 0
                && Double.compare(first.getHeight(), second.getHeight()) == 0
                && Double.compare(first.getPosition().getX(), second.getPosition().getX()) == 0
                && Double.compare(first.getPosition().getZ(), second.getPosition().getZ()) == 0
                && Double.compare(first.getRotation(), second.getRotation()) == 0
                && first.getStatus() == second.getStatus();
    }

    private boolean hardValid(ValidationResult result) {
        return result.isCollisionFree() && result.isBoundaryValid() && result.isDoorClearance()
                && result.isWindowClearance() && result.isPathSecured();
    }

    boolean isSafeStandalonePlacement(Room room, Furniture furniture) {
        List<Furniture> standalone = List.of(furniture);
        List<String> warnings = new ArrayList<>();
        boolean boundaryValid = checkBoundaryValid(room, standalone, warnings);
        if (FurnitureDomainPolicy.isRug(furniture)) {
            return boundaryValid;
        }
        return boundaryValid
                && checkOpeningClearance(room, standalone, "door", warnings)
                && checkOpeningClearance(room, standalone, "window", warnings)
                && checkPathSecured(room, standalone, warnings);
    }

    private boolean checkCollisionFree(List<Furniture> furniture, List<String> warnings) {
        for (int i = 0; i < furniture.size(); i++) {
            for (int j = i + 1; j < furniture.size(); j++) {
                if (FurnitureSupportPolicy.isStrictStack(furniture.get(i), furniture.get(j))) {
                    continue;
                }
                if (obbOverlap(worldCorners(furniture.get(i)), worldCorners(furniture.get(j)))) {
                    warnings.add("가구 충돌이 감지되었습니다.");
                    return false;
                }
            }
        }
        return true;
    }

    private boolean checkCollisionFreeForChanges(List<Furniture> furniture, Set<String> evaluatedIds,
                                                 List<String> warnings) {
        for (int i = 0; i < furniture.size(); i++) {
            Furniture currentItem = furniture.get(i);
            for (int j = i + 1; j < furniture.size(); j++) {
                Furniture otherItem = furniture.get(j);
                if (!evaluatedIds.contains(currentItem.getId()) && !evaluatedIds.contains(otherItem.getId())) {
                    continue;
                }
                if (FurnitureSupportPolicy.isStrictStack(currentItem, otherItem)) {
                    continue;
                }
                if (obbOverlap(worldCorners(currentItem), worldCorners(otherItem))) {
                    warnings.add("신규 또는 변경 가구 충돌이 감지되었습니다.");
                    return false;
                }
            }
        }
        return true;
    }

    private boolean checkBoundaryValid(Room room, List<Furniture> furniture, List<String> warnings) {
        for (Furniture item : furniture) {
            if (!FurnitureBoundary.isInside(room, item)) {
                warnings.add("방 범위를 벗어난 가구가 있습니다.");
                return false;
            }
        }
        return true;
    }

    private boolean checkOpeningClearance(Room room, List<Furniture> furniture,
                                           String openingType, List<String> warnings) {
        for (Opening opening : room.getOpenings()) {
            if (!openingType.equals(opening.getType())) {
                continue;
            }

            List<double[]> clearanceCorners = clearanceZoneCorners(room, opening, openingType);
            if (clearanceCorners.isEmpty()) {
                continue;
            }
            for (Furniture item : furniture) {
                if ("window".equals(openingType) && isAttachedWindowTreatment(room, opening, item)) {
                    continue;
                }
                if ("window".equals(openingType) && item.getHeight() < windowSillHeight(opening)) {
                    continue;
                }
                if (obbOverlap(clearanceCorners, worldCorners(item))) {
                    warnings.add("door".equals(openingType) ? "문 앞 공간이 부족합니다." : "창문 앞 공간이 부족합니다.");
                    return false;
                }
            }
        }
        return true;
    }

    private boolean isAttachedWindowTreatment(Room room, Opening opening, Furniture item) {
        if (!GeneratedFurnitureCatalog.get().sameType("curtain_blind", item.getType())) {
            return false;
        }
        FurnitureBoundary.UsableBounds usable = FurnitureBoundary.usableBounds(room).orElse(null);
        if (usable == null) return false;
        FurnitureBoundary.Footprint footprint = FurnitureBoundary.footprint(item);
        double openingCenter = opening.getOffset() + opening.getWidth() / 2.0;
        return switch (opening.getWall()) {
            case "south" -> cardinalRotation(item, 0)
                    && near(item.getPosition().getZ() + footprint.minZ(), usable.minZ())
                    && alignedSpan(item.getPosition().getX(), openingCenter,
                    item.getPosition().getX() + footprint.minX(), item.getPosition().getX() + footprint.maxX(), opening);
            case "east" -> cardinalRotation(item, 90)
                    && near(item.getPosition().getX() + footprint.maxX(), usable.maxX())
                    && alignedSpan(item.getPosition().getZ(), openingCenter,
                    item.getPosition().getZ() + footprint.minZ(), item.getPosition().getZ() + footprint.maxZ(), opening);
            case "north" -> cardinalRotation(item, 180)
                    && near(item.getPosition().getZ() + footprint.maxZ(), usable.maxZ())
                    && alignedSpan(item.getPosition().getX(), openingCenter,
                    item.getPosition().getX() + footprint.minX(), item.getPosition().getX() + footprint.maxX(), opening);
            case "west" -> cardinalRotation(item, 270)
                    && near(item.getPosition().getX() + footprint.minX(), usable.minX())
                    && alignedSpan(item.getPosition().getZ(), openingCenter,
                    item.getPosition().getZ() + footprint.minZ(), item.getPosition().getZ() + footprint.maxZ(), opening);
            // TODO: wall-id 기반 window(비정형 방의 표준 opening 표현)에 커튼/블라인드가
            // 붙어 있으면 그 벽의 로컬 좌표로 변환해 같은 판정을 해야 한다. 지금은 literal
            // north/south/east/west가 아니면 "부착 안 됨"으로 보수적으로 취급하므로, wall-id
            // 창문에 붙인 커튼은 window clearance 위반으로(과도하게 엄격하게) 보일 수 있다.
            default -> false;
        };
    }

    private boolean alignedSpan(double itemCenter, double openingCenter,
                                double itemMin, double itemMax, Opening opening) {
        return near(itemCenter, openingCenter)
                && itemMax >= opening.getOffset() - WINDOW_ATTACHMENT_EPSILON
                && itemMin <= opening.getOffset() + opening.getWidth() + WINDOW_ATTACHMENT_EPSILON;
    }

    private boolean cardinalRotation(Furniture item, double expected) {
        double normalized = item.getRotation() % 360.0;
        if (normalized < 0) normalized += 360.0;
        return near(normalized, expected);
    }

    private boolean near(double first, double second) {
        return Math.abs(first - second) <= WINDOW_ATTACHMENT_EPSILON;
    }

    private boolean checkPathSecured(Room room, List<Furniture> furniture, List<String> warnings) {
        double centerX = room.getWidth() / 2.0;
        Rect path = new Rect(centerX - MIN_PATH_WIDTH / 2.0, centerX + MIN_PATH_WIDTH / 2.0,
                0, room.getDepth());

        for (Furniture item : furniture) {
            Rect rect = Rect.from(item);
            boolean blocksFullPathWidth = rect.minX() <= path.minX() && rect.maxX() >= path.maxX();
            boolean meaningfulDepth = rect.depth() >= room.getDepth() * 0.4;
            if (blocksFullPathWidth && meaningfulDepth) {
                warnings.add("이동 동선 폭이 부족합니다.");
                return false;
            }
        }
        return true;
    }

    // ---- 가구별 이슈(ValidationIssue) 계산 ----------------------------------
    // 위 boolean/warnings 로직과 같은 판정 primitive(obbOverlap, clearanceZoneCorners)를
    // 재사용하되, 독립된 결과 목록으로 쌓는다 — 기존 boolean/warnings 동작을 바꾸지
    // 않기 위해 일부러 별도 루프로 분리했다.

    private List<ValidationIssue> collisionIssues(List<Furniture> furniture, Set<String> evaluatedIds) {
        List<ValidationIssue> issues = new ArrayList<>();
        for (int i = 0; i < furniture.size(); i++) {
            Furniture a = furniture.get(i);
            for (int j = i + 1; j < furniture.size(); j++) {
                Furniture b = furniture.get(j);
                if (evaluatedIds != null && !evaluatedIds.contains(a.getId()) && !evaluatedIds.contains(b.getId())) {
                    continue;
                }
                if (FurnitureSupportPolicy.isStrictStack(a, b)) {
                    continue;
                }
                if (obbOverlap(worldCorners(a), worldCorners(b))) {
                    issues.add(new ValidationIssue(a.getId(), b.getId(), ValidationIssue.Type.BODY_COLLISION,
                            ValidationIssue.Severity.ERROR, "가구가 서로 겹칩니다."));
                }
            }
        }
        return issues;
    }

    private List<ValidationIssue> boundaryIssues(Room room, List<Furniture> furniture) {
        List<ValidationIssue> issues = new ArrayList<>();
        for (Furniture item : furniture) {
            if (!FurnitureBoundary.isInside(room, item)) {
                issues.add(new ValidationIssue(item.getId(), null, ValidationIssue.Type.OUT_OF_BOUNDS,
                        ValidationIssue.Severity.ERROR, "가구가 방 범위를 벗어났습니다."));
            }
        }
        return issues;
    }

    private List<ValidationIssue> openingIssues(Room room, List<Furniture> furniture) {
        List<ValidationIssue> issues = new ArrayList<>();
        for (String openingType : List.of("door", "window")) {
            ValidationIssue.Type type = "door".equals(openingType)
                    ? ValidationIssue.Type.DOOR_CLEARANCE : ValidationIssue.Type.WINDOW_CLEARANCE;
            for (Opening opening : room.getOpenings()) {
                if (!openingType.equals(opening.getType())) continue;
                List<double[]> clearanceCorners = clearanceZoneCorners(room, opening, openingType);
                if (clearanceCorners.isEmpty()) continue;
                for (Furniture item : furniture) {
                    if ("window".equals(openingType) && isAttachedWindowTreatment(room, opening, item)) continue;
                    if ("window".equals(openingType) && item.getHeight() < windowSillHeight(opening)) continue;
                    if (obbOverlap(clearanceCorners, worldCorners(item))) {
                        issues.add(new ValidationIssue(item.getId(), null, type, ValidationIssue.Severity.ERROR,
                                "door".equals(openingType) ? "문 앞 공간을 가리고 있습니다." : "창문 앞 공간을 가리고 있습니다."));
                    }
                }
            }
        }
        return issues;
    }

    private List<ValidationIssue> pathIssues(Room room, List<Furniture> furniture) {
        List<ValidationIssue> issues = new ArrayList<>();
        double centerX = room.getWidth() / 2.0;
        Rect path = new Rect(centerX - MIN_PATH_WIDTH / 2.0, centerX + MIN_PATH_WIDTH / 2.0,
                0, room.getDepth());
        for (Furniture item : furniture) {
            Rect rect = Rect.from(item);
            boolean blocksFullPathWidth = rect.minX() <= path.minX() && rect.maxX() >= path.maxX();
            boolean meaningfulDepth = rect.depth() >= room.getDepth() * 0.4;
            if (blocksFullPathWidth && meaningfulDepth) {
                issues.add(new ValidationIssue(item.getId(), null, ValidationIssue.Type.PATH_BLOCKED,
                        ValidationIssue.Severity.ERROR, "이동 동선을 막고 있습니다."));
            }
        }
        return issues;
    }

    private List<ValidationIssue> zoneIssues(List<Furniture> furniture, Set<String> evaluatedIds) {
        List<ValidationIssue> issues = new ArrayList<>();
        for (Furniture item : furniture) {
            List<FurnitureClearance.Zone> zones = FurnitureClearance.zonesWorld(item);
            if (zones.isEmpty()) continue;
            for (Furniture other : furniture) {
                if (Objects.equals(other.getId(), item.getId())) continue;
                if (evaluatedIds != null && !evaluatedIds.contains(item.getId()) && !evaluatedIds.contains(other.getId())) {
                    continue;
                }
                if (FurnitureSupportPolicy.isStrictStack(item, other)) continue;
                List<double[]> otherCorners = worldCorners(other);
                if (obbOverlap(worldCorners(item), otherCorners)) {
                    // Already reported as BODY_COLLISION (ERROR) — don't also warn.
                    continue;
                }
                for (FurnitureClearance.Zone zone : zones) {
                    if (obbOverlap(zone.corners(), otherCorners)) {
                        issues.add(new ValidationIssue(item.getId(), other.getId(), ValidationIssue.Type.ZONE_INTRUSION,
                                ValidationIssue.Severity.WARNING,
                                "가구 작동 공간(" + zoneLabel(zone.side()) + ")을 다른 가구가 막고 있습니다."));
                        break;
                    }
                }
            }
        }
        return issues;
    }

    private String zoneLabel(String side) {
        return switch (side) {
            case "front" -> "전면";
            case "left" -> "좌측";
            case "right" -> "우측";
            default -> side;
        };
    }

    // ---- 문/창 앞 여유 공간 사각형 ------------------------------------------

    /**
     * 문/창 앞 여유 공간을 월드 좌표 사각형(4개 코너)으로 계산한다.
     * north/south/east/west 리터럴(직사각형 방, 레거시 업로드)은 예전과 동일한
     * 축 정렬 사각형을 반환한다. 그 외(Room.walls[].id를 참조하는 표준 형태 —
     * Opening.java 참고, 비정형 방의 L자/알코브 샘플이 여기 해당)는 예전에는
     * 무조건 (0,0,0,0) 사각형(=검증이 사실상 꺼짐)을 반환했다. 이제 해당 벽
     * 세그먼트를 찾아 그 벽을 기준으로 안쪽으로 확장한 사각형을 계산한다.
     */
    private List<double[]> clearanceZoneCorners(Room room, Opening opening, String openingType) {
        double depth = "door".equals(openingType) ? DOOR_CLEARANCE_DEPTH : WINDOW_CLEARANCE_DEPTH;
        double margin = "door".equals(openingType) ? DOOR_SIDE_MARGIN : WINDOW_SIDE_MARGIN;

        Rect literal = switch (opening.getWall()) {
            case "north" -> new Rect(opening.getOffset() - margin,
                    opening.getOffset() + opening.getWidth() + margin,
                    room.getDepth() - depth, room.getDepth());
            case "south" -> new Rect(opening.getOffset() - margin,
                    opening.getOffset() + opening.getWidth() + margin,
                    0, depth);
            case "east" -> new Rect(room.getWidth() - depth, room.getWidth(),
                    opening.getOffset() - margin, opening.getOffset() + opening.getWidth() + margin);
            case "west" -> new Rect(0, depth,
                    opening.getOffset() - margin, opening.getOffset() + opening.getWidth() + margin);
            default -> null;
        };
        if (literal != null) {
            return rectCorners(literal);
        }
        return wallClearanceCorners(room, opening, depth, margin).orElse(List.of());
    }

    private List<double[]> rectCorners(Rect rect) {
        return List.of(
                new double[]{rect.minX(), rect.minZ()},
                new double[]{rect.maxX(), rect.minZ()},
                new double[]{rect.maxX(), rect.maxZ()},
                new double[]{rect.minX(), rect.maxZ()}
        );
    }

    private Optional<List<double[]>> wallClearanceCorners(Room room, Opening opening, double depth, double margin) {
        Wall wall = findWall(room, opening.getWall());
        if (wall == null || wall.getStart() == null || wall.getEnd() == null) {
            return Optional.empty();
        }
        double dx = wall.getEnd().getX() - wall.getStart().getX();
        double dz = wall.getEnd().getZ() - wall.getStart().getZ();
        double length = Math.hypot(dx, dz);
        if (length < FurnitureBoundary.EPSILON) {
            return Optional.empty();
        }
        double dirX = dx / length;
        double dirZ = dz / length;
        double normalX = -dirZ;
        double normalZ = dirX;

        // Walls aren't guaranteed to wind consistently, so which side of the
        // wall is "inside the room" isn't known from the segment alone. Probe
        // both normal directions from the opening's midpoint and keep whichever
        // lands inside the room.
        double midOffset = opening.getOffset() + opening.getWidth() / 2.0;
        double midX = wall.getStart().getX() + dirX * midOffset;
        double midZ = wall.getStart().getZ() + dirZ * midOffset;
        double probeDistance = Math.max(WALL_NORMAL_PROBE_MIN_METERS, depth / 2.0);

        boolean forwardIsInward;
        if (FurnitureBoundary.containsPoint(room, midX + normalX * probeDistance, midZ + normalZ * probeDistance)) {
            forwardIsInward = true;
        } else if (FurnitureBoundary.containsPoint(room, midX - normalX * probeDistance, midZ - normalZ * probeDistance)) {
            forwardIsInward = false;
        } else {
            return Optional.empty();
        }
        double inwardX = forwardIsInward ? normalX : -normalX;
        double inwardZ = forwardIsInward ? normalZ : -normalZ;

        double alongStart = opening.getOffset() - margin;
        double alongEnd = opening.getOffset() + opening.getWidth() + margin;
        double startX = wall.getStart().getX() + dirX * alongStart;
        double startZ = wall.getStart().getZ() + dirZ * alongStart;
        double endX = wall.getStart().getX() + dirX * alongEnd;
        double endZ = wall.getStart().getZ() + dirZ * alongEnd;

        return Optional.of(List.of(
                new double[]{startX, startZ},
                new double[]{endX, endZ},
                new double[]{endX + inwardX * depth, endZ + inwardZ * depth},
                new double[]{startX + inwardX * depth, startZ + inwardZ * depth}
        ));
    }

    private Wall findWall(Room room, String wallId) {
        if (room.getWalls() == null || wallId == null) {
            return null;
        }
        for (Wall wall : room.getWalls()) {
            if (wall != null && wallId.equals(wall.getId())) {
                return wall;
            }
        }
        return null;
    }

    private double windowSillHeight(Opening opening) {
        return opening.getSillHeight() == null ? 0 : opening.getSillHeight();
    }

    // ---- OBB(SAT) 겹침 판정 --------------------------------------------------
    // 가구는 항상 사각형(footprint/clearance zone 모두 4개 코너의 볼록 사각형)이므로,
    // Separating Axis Theorem으로 임의 회전에서도 정확한 겹침 판정을 한다. 회전이
    // 0/90/180/270인 기존 케이스에서는 이전의 AABB 판정과 동일한 결과를 낸다.

    private static List<double[]> worldCorners(Furniture furniture) {
        FurnitureBoundary.Footprint footprint = FurnitureBoundary.footprint(furniture);
        Position position = furniture.getPosition();
        List<double[]> corners = new ArrayList<>(4);
        for (FurnitureBoundary.Offset corner : footprint.corners()) {
            corners.add(new double[]{position.getX() + corner.x(), position.getZ() + corner.z()});
        }
        return corners;
    }

    private static boolean obbOverlap(List<double[]> a, List<double[]> b) {
        return !separatedByEdgeNormals(a, b) && !separatedByEdgeNormals(b, a);
    }

    private static boolean separatedByEdgeNormals(List<double[]> edgesOf, List<double[]> other) {
        int n = edgesOf.size();
        for (int i = 0; i < n; i++) {
            double[] p1 = edgesOf.get(i);
            double[] p2 = edgesOf.get((i + 1) % n);
            double edgeX = p2[0] - p1[0];
            double edgeZ = p2[1] - p1[1];
            double axisX = -edgeZ;
            double axisZ = edgeX;
            if (Math.abs(axisX) < FurnitureBoundary.EPSILON && Math.abs(axisZ) < FurnitureBoundary.EPSILON) {
                continue;
            }
            double[] rangeSelf = project(edgesOf, axisX, axisZ);
            double[] rangeOther = project(other, axisX, axisZ);
            if (rangeSelf[1] <= rangeOther[0] + SEPARATION_EPSILON
                    || rangeOther[1] <= rangeSelf[0] + SEPARATION_EPSILON) {
                return true;
            }
        }
        return false;
    }

    private static double[] project(List<double[]> corners, double axisX, double axisZ) {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (double[] corner : corners) {
            double dot = corner[0] * axisX + corner[1] * axisZ;
            min = Math.min(min, dot);
            max = Math.max(max, dot);
        }
        return new double[]{min, max};
    }

    private record Rect(double minX, double maxX, double minZ, double maxZ) {

        private static Rect from(Furniture furniture) {
            FurnitureBoundary.Footprint footprint = FurnitureBoundary.footprint(furniture);
            return new Rect(
                    furniture.getPosition().getX() + footprint.minX(),
                    furniture.getPosition().getX() + footprint.maxX(),
                    furniture.getPosition().getZ() + footprint.minZ(),
                    furniture.getPosition().getZ() + footprint.maxZ()
            );
        }

        private double depth() {
            return maxZ - minZ;
        }
    }
}
