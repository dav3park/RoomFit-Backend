package com.roomfit.placement;

import com.roomfit.agent.domain.AgentContext;
import com.roomfit.agent.domain.LifestyleGoal;
import com.roomfit.product.catalog.GeneratedFurnitureCatalog;
import com.roomfit.product.domain.MockProduct;
import com.roomfit.product.service.MockProductService;
import com.roomfit.product.service.ProductRecommendationService;
import com.roomfit.room.Furniture;
import com.roomfit.room.FurnitureBoundary;
import com.roomfit.room.FurnitureStatus;
import com.roomfit.room.Opening;
import com.roomfit.room.Position;
import com.roomfit.room.Room;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 규칙 기반 배치 추천 구현체 — LlmPlacementService의 폴백으로 쓰인다
 * (PlacementServiceConfig 참고). Spring 빈으로 자동 등록하지 않고
 * PlacementServiceConfig에서 직접 생성한다 — PlacementService 인터페이스의
 * 유일한 빈은 FallbackPlacementService여야 컨트롤러/서비스 쪽 주입이 모호해지지
 * 않는다 (FeedbackIntentParserConfig의 RuleBasedFeedbackIntentParser와 동일 패턴).
 *
 * TODO: 지금은 requiredItems를 단순 나열 배치하는 스켈레톤 수준.
 * 실제로는 lifestyleGoal/designStyle 별 배치 템플릿을 두고,
 * 방 크기 및 기존(existing) 가구와 겹치지 않는 템플릿을 선택하는 방식으로 구현 권장.
 */
public class RuleBasedPlacementService implements PlacementService {

    private static final String COLLECTOR_ROOM_NAME = "미드센추리 컬렉터 룸";
    private static final String COLLECTOR_STUDIO_ROOM_NAME = "샘플룸2";

    private final MockProductService mockProductService;
    private final ProductRecommendationService productRecommendationService;
    private final ValidationService validationService;

    public RuleBasedPlacementService(MockProductService mockProductService,
                                      ProductRecommendationService productRecommendationService) {
        this(mockProductService, productRecommendationService, new ValidationService());
    }

    RuleBasedPlacementService(MockProductService mockProductService,
                              ProductRecommendationService productRecommendationService,
                              ValidationService validationService) {
        this.mockProductService = mockProductService;
        this.productRecommendationService = productRecommendationService;
        this.validationService = validationService;
    }

    @Override
    public PlacementResult recommend(AgentContext context, Room room) {
        if (COLLECTOR_ROOM_NAME.equals(room.getName())) {
            return scriptedRecommendation(room, midCenturyCollectorRecommendation());
        }
        if (COLLECTOR_STUDIO_ROOM_NAME.equals(room.getName())) {
            return scriptedRecommendation(room, midCenturyStudioRecommendation());
        }

        List<Furniture> recommended = new ArrayList<>();
        recommended.addAll(room.getFurniture().stream()
                .filter(this::isActivePlacedFurniture)
                .map(this::copyFurniture)
                .toList());

        // AgentContext는 selectedProductIds만 영속화한다 — 전체 MockProduct는
        // 여기서 다시 조회한다(AgentContext.java 참고).
        Map<String, MockProduct> selectedProductByType = mockProductService.findByProductIds(context.getSelectedProductIds()).stream()
                .collect(Collectors.toMap(
                        product -> GeneratedFurnitureCatalog.get().normalizeType(product.getType()),
                        Function.identity(),
                        (first, ignored) -> first));

        List<String> requestedTypes = new ArrayList<>(context.getRequiredItems());
        requestedTypes.addAll(context.getOptionalItems());
        int requiredItemCount = context.getRequiredItems().size();
        List<RequestedItem> requestedItems = new ArrayList<>();
        for (int index = 0; index < requestedTypes.size(); index++) {
            String requestedType = requestedTypes.get(index);
            requestedItems.add(new RequestedItem(index, requestedType,
                    GeneratedFurnitureCatalog.get().normalizeType(requestedType)));
        }
        requestedItems.sort(Comparator
                .comparingInt((RequestedItem item) -> item.originalIndex() < requiredItemCount ? 0 : 1)
                .thenComparingInt(item -> dependencyRank(item.canonicalType()))
                .thenComparingInt(item -> LifestyleFurniturePriority.rank(
                        context.getLifestyleGoal(), item.canonicalType()))
                .thenComparingInt(RequestedItem::originalIndex));
        List<UnplacedFurniture> unplaced = new ArrayList<>();
        int placedCount = 0;
        for (RequestedItem requestedItem : requestedItems) {
            String itemType = requestedItem.requestedType();
            String canonicalItemType = requestedItem.canonicalType();
            if (canonicalItemType == null || !supportedType(canonicalItemType)) {
                unplaced.add(unplaced(requestedItem.originalIndex(), itemType, null, "UNSUPPORTED_FURNITURE_TYPE"));
                continue;
            }
            PlacementAttempt attempt = tryAddFurniture(room, recommended, context, canonicalItemType,
                    selectedProductByType.get(canonicalItemType));
            if (attempt.furniture() != null) {
                placedCount++;
            } else {
                unplaced.add(unplaced(requestedItem.originalIndex(), canonicalItemType,
                        attempt.product(), attempt.reasonCode()));
            }
        }
        RecommendationExecutionStatus outcome = placedCount == requestedItems.size()
                ? RecommendationExecutionStatus.SUCCESS
                : placedCount == 0 ? RecommendationExecutionStatus.FAILED : RecommendationExecutionStatus.PARTIAL_SUCCESS;
        String warningCode = unplaced.isEmpty() ? null : "INSUFFICIENT_ROOM_SPACE";
        String message = outcome == RecommendationExecutionStatus.SUCCESS
                ? "선택한 " + requestedItems.size() + "개 가구를 모두 배치했습니다."
                : outcome == RecommendationExecutionStatus.PARTIAL_SUCCESS
                ? "선택한 " + requestedItems.size() + "개 가구 중 " + placedCount + "개를 배치했습니다."
                : "선택한 가구를 안전하게 배치할 공간을 찾지 못했습니다.";
        return new PlacementResult(RecommendationStatus.SUCCESS, recommended, ScoreSummary.defaultSummary(),
                requestedItems.size(), placedCount, unplaced, outcome, warningCode, message);
    }

    private List<Furniture> midCenturyCollectorRecommendation() {
        return List.of(
                new Furniture("collector-bed", "bed", "미드센추리 싱글 침대", 1.35, 2.0, 0.5,
                        new Position(1.35, 1.8), 0, FurnitureStatus.RECOMMENDED),
                new Furniture("collector-bedside", "nightstand", "코랄 협탁", 0.48, 0.42, 0.52,
                        new Position(0.55, 1.7), 0, FurnitureStatus.RECOMMENDED),
                new Furniture("collector-floor-plant", "plant", "플로어 식물", 0.48, 0.48, 0.92,
                        new Position(0.58, 4.5), 0, FurnitureStatus.RECOMMENDED),
                new Furniture("collector-desk", "desk", "미드센추리 컬렉터 데스크", 1.35, 0.62, 0.74,
                        new Position(2.9, 1.0), 0, FurnitureStatus.RECOMMENDED),
                new Furniture("collector-desk-chair", "desk_chair", "월넛 데스크 체어", 0.58, 0.58, 0.82,
                        new Position(2.9, 1.95), 180, FurnitureStatus.RECOMMENDED),
                new Furniture("collector-blue-cabinet", "drawer_chest", "코발트 모듈 수납장", 0.78, 0.42, 1.08,
                        new Position(1.35, 3.15), 0, FurnitureStatus.RECOMMENDED),
                new Furniture("collector-glass-shelf", "bookshelf", "크롬 글라스 전시 선반", 1.28, 0.36, 1.48,
                        new Position(3.95, 0.98), 0, FurnitureStatus.RECOMMENDED),
                new Furniture("collector-console", "media_console", "크림 LP 콘솔", 1.76, 0.44, 0.78,
                        new Position(5.08, 2.7), 0, FurnitureStatus.RECOMMENDED),
                new Furniture("collector-red-shelf", "partition_shelf", "레트로 레드 벽 선반", 0.92, 0.18, 0.22,
                        new Position(5.9, 2.3), 90, FurnitureStatus.RECOMMENDED),
                new Furniture("collector-lounge-chair", "sofa", "코랄 라운지 체어", 0.92, 0.86, 0.84,
                        new Position(5.1, 4.55), 135, FurnitureStatus.RECOMMENDED),
                new Furniture("collector-cane-chair", "desk_chair", "케인 크롬 체어", 0.68, 0.7, 0.82,
                        new Position(3.75, 4.6), 320, FurnitureStatus.RECOMMENDED),
                new Furniture("collector-rug", "rug", "크림 라운드 러그", 2.2, 2.2, 0.035,
                        new Position(3.9, 4.08), 0, FurnitureStatus.RECOMMENDED),
                new Furniture("collector-coffee-table", "multi_table", "글라스 커피 테이블", 0.9, 0.9, 0.42,
                        new Position(3.9, 4.08), 0, FurnitureStatus.RECOMMENDED)
        );
    }

    private List<Furniture> midCenturyStudioRecommendation() {
        return List.of(
                new Furniture("studio-bed", "bed", "코랄 프레임 침대", 1.35, 2.0, 0.5,
                        new Position(1.35, 1.8), 0, FurnitureStatus.RECOMMENDED),
                new Furniture("studio-bedside", "nightstand", "레드 미드센추리 협탁", 0.48, 0.42, 0.52,
                        new Position(0.55, 1.7), 0, FurnitureStatus.RECOMMENDED),
                new Furniture("studio-floor-plant", "plant", "윈도우 플로어 식물", 0.48, 0.48, 0.92,
                        new Position(0.58, 4.5), 0, FurnitureStatus.RECOMMENDED),
                new Furniture("studio-desk", "desk", "컬렉터 데스크", 1.35, 0.62, 0.74,
                        new Position(2.8, 1.05), 0, FurnitureStatus.RECOMMENDED),
                new Furniture("studio-desk-chair", "desk_chair", "월넛 데스크 체어", 0.58, 0.58, 0.82,
                        new Position(2.8, 1.9), 180, FurnitureStatus.RECOMMENDED),
                new Furniture("studio-blue-cabinet", "drawer_chest", "코발트 모듈 수납장", 0.78, 0.42, 1.08,
                        new Position(5.55, 1.0), 0, FurnitureStatus.RECOMMENDED),
                new Furniture("studio-glass-shelf", "bookshelf", "크롬 글라스 전시 선반", 1.28, 0.36, 1.48,
                        new Position(4.45, 0.95), 0, FurnitureStatus.RECOMMENDED),
                new Furniture("studio-console", "media_console", "크림 LP 콘솔", 1.76, 0.44, 0.78,
                        new Position(5.08, 2.7), 0, FurnitureStatus.RECOMMENDED),
                new Furniture("studio-red-shelf", "partition_shelf", "레트로 레드 벽 선반", 0.92, 0.18, 0.22,
                        new Position(5.9, 2.3), 90, FurnitureStatus.RECOMMENDED),
                new Furniture("studio-lounge-chair", "sofa", "코랄 라운지 체어", 0.92, 0.86, 0.84,
                        new Position(5.45, 4.75), 135, FurnitureStatus.RECOMMENDED),
                new Furniture("studio-cane-chair", "desk_chair", "케인 크롬 체어", 0.68, 0.7, 0.82,
                        new Position(3.4, 5.25), 225, FurnitureStatus.RECOMMENDED),
                new Furniture("studio-rug", "rug", "크림 라운드 러그", 2.2, 2.2, 0.035,
                        new Position(4.35, 4.3), 0, FurnitureStatus.RECOMMENDED),
                new Furniture("studio-coffee-table", "multi_table", "글라스 커피 테이블", 0.9, 0.9, 0.42,
                        new Position(4.35, 4.3), 0, FurnitureStatus.RECOMMENDED)
        );
    }

    private PlacementAttempt tryAddFurniture(Room room, List<Furniture> placed, AgentContext context,
                                             String itemType, MockProduct exactSelectedProduct) {
        if (exactSelectedProduct != null) return tryProduct(room, placed, itemType, exactSelectedProduct);
        List<MockProduct> ranked = productRecommendationService.rankedCandidates(itemType, context, room).stream()
                .filter(product -> !"bed-loft-desk".equals(product.getVariantId())
                        || java.util.stream.Stream.concat(context.getRequiredItems().stream(), context.getOptionalItems().stream())
                            .noneMatch(type -> GeneratedFurnitureCatalog.get().sameType("desk", type))
                            && placed.stream().noneMatch(item -> GeneratedFurnitureCatalog.get().sameType("desk", item.getType())))
                .toList();
        List<MockProduct> generated = ranked.stream().filter(product -> product.getVariantId() != null).toList();
        List<MockProduct> pool = generated.isEmpty() ? ranked : generated;
        LinkedHashSet<MockProduct> candidates = new LinkedHashSet<>(pool.stream().limit(4).toList());
        pool.stream().sorted(Comparator.comparingDouble(product -> product.getWidth() * product.getDepth()))
                .limit(2).forEach(candidates::add);
        Set<String> modes = new LinkedHashSet<>();
        for (MockProduct product : pool) {
            String mode = GeneratedFurnitureCatalog.get().placementPolicy(product.getVariantId())
                    .map(policy -> policy.installation()).orElse("FLOOR");
            if (modes.add(mode)) candidates.add(product);
        }
        PlacementAttempt failure = new PlacementAttempt(null, null, "NO_RENDERABLE_PRODUCT");
        for (MockProduct product : candidates.stream().limit(8).toList()) {
            PlacementAttempt attempt = tryProduct(room, placed, itemType, product);
            if (attempt.furniture() != null) return attempt;
            failure = attempt;
        }
        return failure;
    }

    private PlacementAttempt tryProduct(Room room, List<Furniture> placed, String itemType, MockProduct product) {
        FurnitureSpec spec = FurnitureSpec.from(itemType, product);
        // 시도한 모든 후보 위치의 실패 사유를 모아, 가장 흔했던(대표적인) 사유를
        // 반환한다 — 마지막으로 시도한 위치의 사유만 보면, 후보 20개 중 19개가
        // 충돌이었어도 우연히 마지막 하나가 경계 실패면 "경계 문제"로 잘못
        // 보고된다. 빈도가 같으면 validationReason()의 검사 순서(경계 -> 충돌 ->
        // 문 -> 창문 -> 동선)로 deterministic하게 tie-break한다.
        Map<String, Integer> reasonCounts = new LinkedHashMap<>();
        Furniture origin=createRecommendedFurniture(generateFurnitureId(itemType,placed),spec,new Position(0,0),0);
        var installation=FurnitureInstallationPolicy.policy(origin);
        List<Furniture> prototypes;
        if("WINDOW".equals(installation.installation()))prototypes=room.getOpenings().stream().filter(o->"window".equals(o.getType())).limit(4)
                .map(opening->FurnitureInstallationPolicy.windowCandidate(room,origin,opening)).filter(java.util.Objects::nonNull).toList();
        else prototypes=placementCandidates(itemType,spec,room,placed).stream()
                .map(pose->createRecommendedFurniture(origin.getId(),spec,pose.position(),pose.rotation())).toList();
        if("SUPPORT".equals(installation.installation()))prototypes=placed.stream()
                .filter(other->installation.supportTypes().contains(GeneratedFurnitureCatalog.get().normalizeType(other.getType())))
                .limit(4).map(other->createRecommendedFurniture(origin.getId(),spec,FurnitureInstallationPolicy.supportPosition(other),other.getRotation())).toList();
        if("WALL".equals(installation.installation())||"FLOOR_CEILING".equals(installation.installation()))prototypes=wallCandidates(spec,room).stream()
                .map(pose->createRecommendedFurniture(origin.getId(),spec,pose.position(),pose.rotation())).toList();
        for (Furniture prototype : prototypes) {
            Position safePosition = FurnitureBoundary.clamp(room, prototype.getPosition(), prototype).orElse(null);
            if (safePosition == null) {
                reasonCounts.merge("NO_VALID_BOUNDARY_PLACEMENT", 1, Integer::sum);
                continue;
            }
            Furniture candidate = new Furniture(prototype.getId(),prototype.getType(),prototype.getLabel(),prototype.getWidth(),prototype.getDepth(),prototype.getHeight(),
                    safePosition,prototype.getRotation(),prototype.getStatus(),prototype.getProductId(),prototype.getStyleTags(),prototype.getVariantId());
            ValidationResult validation = validationService.validateChange(room, placed, appended(placed, candidate));
            if (isHardValid(validation)) {
                placed.add(candidate);
                return new PlacementAttempt(candidate, product, null);
            }
            reasonCounts.merge(validationReason(validation), 1, Integer::sum);
        }
        return new PlacementAttempt(null, product, mostLikelyFailureReason(reasonCounts));
    }

    private Furniture createRecommendedFurniture(String id, FurnitureSpec spec, Position position, double rotation) {
        return new Furniture(
                id,
                spec.type(),
                spec.label(),
                spec.width(),
                spec.depth(),
                spec.height(),
                position,
                rotation,
                FurnitureStatus.RECOMMENDED,
                spec.productId(),
                spec.styleTags(),
                spec.variantId()
        );
    }

    private List<PlacementCandidate> placementCandidates(String itemType, FurnitureSpec spec,
                                                          Room room, List<Furniture> placed) {
        if ("curtain_blind".equals(itemType)) {
            return deduplicate(environmentCandidates(itemType, spec, room));
        }
        if ("monitor".equals(itemType) || "tv".equals(itemType)) {
            return deduplicate(relationshipCandidates(itemType, spec, placed));
        }
        List<PlacementCandidate> candidates = new ArrayList<>();
        var policy=GeneratedFurnitureCatalog.get().placementPolicy(spec.variantId()).orElse(null);
        if(policy!=null&&policy.roles().contains("divider")) {
            var sleepers=placed.stream().filter(item->Set.of("bed","sofa_bed").contains(GeneratedFurnitureCatalog.get().normalizeType(item.getType()))).limit(2).toList();
            var others=placed.stream().filter(item->Set.of("desk","sofa").contains(GeneratedFurnitureCatalog.get().normalizeType(item.getType()))).limit(2).toList();
            for(var a:sleepers)for(var b:others){double dx=b.getPosition().getX()-a.getPosition().getX(),dz=b.getPosition().getZ()-a.getPosition().getZ();
                candidates.add(new PlacementCandidate(new Position((a.getPosition().getX()+b.getPosition().getX())/2,(a.getPosition().getZ()+b.getPosition().getZ())/2),
                        (Math.toDegrees(Math.atan2(dx,-dz))+360)%360,PlacementRelation.SAFE,candidates.size()));}
        }
        candidates.addAll(relationshipCandidates(itemType, spec, placed));
        candidates.addAll(environmentCandidates(itemType, spec, room));
        candidates.addAll(safeCandidates(itemType, spec, room, placed));
        candidates.addAll(gridCandidates(spec, room));
        return deduplicate(candidates);
    }

    private List<PlacementCandidate> relationshipCandidates(String itemType, FurnitureSpec spec,
                                                             List<Furniture> placed) {
        return switch (itemType) {
            case "monitor" -> findPlacedByType(placed, "desk")
                    .map(anchor -> List.of(stackCandidate(anchor, PlacementRelation.SUPPORT)))
                    .orElse(List.of());
            case "tv" -> findPlacedByType(placed, "media_console")
                    .map(anchor -> List.of(stackCandidate(anchor, PlacementRelation.SUPPORT)))
                    .orElse(List.of());
            case "nightstand" -> findPlacedByType(placed, "bed")
                    .map(anchor -> sideCandidates(anchor, spec, 0.15))
                    .orElse(List.of());
            case "side_table" -> findPlacedByTypes(placed, "sofa", "sofa_bed")
                    .map(anchor -> sideCandidates(anchor, spec, 0.15))
                    .orElse(List.of());
            case "desk_chair" -> findPlacedByType(placed, "desk")
                    .map(anchor -> List.of(frontCandidate(anchor, spec, 0.25)))
                    .orElse(List.of());
            case "mood_lamp" -> findPlacedByTypes(placed, "desk", "bed", "sofa", "sofa_bed")
                    .map(anchor -> sideCandidates(anchor, spec, 0.15))
                    .orElse(List.of());
            default -> List.of();
        };
    }

    private PlacementCandidate stackCandidate(Furniture anchor, PlacementRelation relation) {
        return new PlacementCandidate(new Position(anchor.getPosition().getX(), anchor.getPosition().getZ()),
                normalize(anchor.getRotation()), relation, 0);
    }

    private List<PlacementCandidate> sideCandidates(Furniture anchor, FurnitureSpec spec, double gap) {
        double rotation = normalize(anchor.getRotation());
        Position right = right(rotation);
        Position left = new Position(-right.getX(), -right.getZ());
        return List.of(
                offsetCandidate(anchor, spec, right, gap, rotation, PlacementRelation.ANCHOR_SIDE, 0),
                offsetCandidate(anchor, spec, left, gap, rotation, PlacementRelation.ANCHOR_SIDE, 1)
        );
    }

    private PlacementCandidate frontCandidate(Furniture anchor, FurnitureSpec spec, double gap) {
        double anchorRotation = normalize(anchor.getRotation());
        double candidateRotation = normalize(anchorRotation + 180);
        return offsetCandidate(anchor, spec, forward(anchorRotation), gap, candidateRotation,
                PlacementRelation.ANCHOR_FRONT, 0);
    }

    private PlacementCandidate offsetCandidate(Furniture anchor, FurnitureSpec spec, Position direction,
                                                double gap, double rotation, PlacementRelation relation, int order) {
        FurnitureBoundary.Footprint anchorFootprint = FurnitureBoundary.footprint(anchor);
        FurnitureBoundary.Footprint candidateFootprint = footprint(spec, rotation);
        double distance = maximumProjection(anchorFootprint, direction)
                - minimumProjection(candidateFootprint, direction) + gap;
        return new PlacementCandidate(new Position(
                anchor.getPosition().getX() + direction.getX() * distance,
                anchor.getPosition().getZ() + direction.getZ() * distance),
                rotation, relation, order);
    }

    private double maximumProjection(FurnitureBoundary.Footprint footprint, Position direction) {
        return footprint.corners().stream()
                .mapToDouble(corner -> corner.x() * direction.getX() + corner.z() * direction.getZ())
                .max().orElseThrow();
    }

    private double minimumProjection(FurnitureBoundary.Footprint footprint, Position direction) {
        return footprint.corners().stream()
                .mapToDouble(corner -> corner.x() * direction.getX() + corner.z() * direction.getZ())
                .min().orElseThrow();
    }

    private List<PlacementCandidate> environmentCandidates(String itemType, FurnitureSpec spec, Room room) {
        if ("curtain_blind".equals(itemType)) {
            return windowCandidates(spec, room);
        }
        if ("plant".equals(itemType)) {
            return cornerCandidates(spec, room);
        }
        if (usesWallCandidates(itemType)) {
            return wallCandidates(spec, room);
        }
        return List.of();
    }

    private boolean usesWallCandidates(String type) {
        return Set.of("bed", "desk", "sofa", "sofa_bed", "media_console", "wardrobe", "bookshelf",
                "drawer_chest", "hanger", "partition_shelf", "full_length_mirror", "storage").contains(type);
    }

    private List<PlacementCandidate> wallCandidates(FurnitureSpec spec, Room room) {
        FurnitureBoundary.UsableBounds usable = FurnitureBoundary.usableBounds(room).orElse(null);
        if (usable == null) return List.of();
        List<PlacementCandidate> candidates = new ArrayList<>();
        if (!room.getWalls().isEmpty()) {
            for (var wall : room.getWalls()) {
                double dx=wall.getEnd().getX()-wall.getStart().getX(), dz=wall.getEnd().getZ()-wall.getStart().getZ(), len=Math.hypot(dx,dz);
                if (len < spec.width()) continue;
                double nx=-dz/len, nz=dx/len, mx=(wall.getStart().getX()+wall.getEnd().getX())/2, mz=(wall.getStart().getZ()+wall.getEnd().getZ())/2;
                if (!FurnitureBoundary.containsPoint(room,mx+nx*.3,mz+nz*.3)) {nx=-nx;nz=-nz;}
                double rotation=(Math.toDegrees(Math.atan2(-nx,nz))+360)%360;
                for(double t:new double[]{.5,.25,.75}) candidates.add(new PlacementCandidate(
                    new Position(wall.getStart().getX()+dx*t+nx*(spec.depth()/2+.08),wall.getStart().getZ()+dz*t+nz*(spec.depth()/2+.08)),
                    rotation,PlacementRelation.WALL,candidates.size()));
            }
            return candidates;
        }
        addWallCandidates(candidates, spec, usable, "south", 0, 0);
        addWallCandidates(candidates, spec, usable, "east", 90, 3);
        addWallCandidates(candidates, spec, usable, "north", 180, 6);
        addWallCandidates(candidates, spec, usable, "west", 270, 9);
        return candidates;
    }

    private void addWallCandidates(List<PlacementCandidate> candidates, FurnitureSpec spec,
                                   FurnitureBoundary.UsableBounds usable, String wall,
                                   double rotation, int firstOrder) {
        FurnitureBoundary.Footprint footprint = footprint(spec, rotation);
        double minCenterX = usable.minX() - footprint.minX();
        double maxCenterX = usable.maxX() - footprint.maxX();
        double minCenterZ = usable.minZ() - footprint.minZ();
        double maxCenterZ = usable.maxZ() - footprint.maxZ();
        if (maxCenterX < minCenterX || maxCenterZ < minCenterZ) return;

        if ("south".equals(wall) || "north".equals(wall)) {
            double z = "south".equals(wall) ? minCenterZ : maxCenterZ;
            double[] positions = centerAndQuarters(minCenterX, maxCenterX);
            for (int index = 0; index < positions.length; index++) {
                candidates.add(new PlacementCandidate(new Position(positions[index], z), rotation,
                        PlacementRelation.WALL, firstOrder + index));
            }
        } else {
            double x = "east".equals(wall) ? maxCenterX : minCenterX;
            double[] positions = centerAndQuarters(minCenterZ, maxCenterZ);
            for (int index = 0; index < positions.length; index++) {
                candidates.add(new PlacementCandidate(new Position(x, positions[index]), rotation,
                        PlacementRelation.WALL, firstOrder + index));
            }
        }
    }

    private double[] centerAndQuarters(double min, double max) {
        double span = max - min;
        return new double[]{min + span / 2.0, min + span / 4.0, min + span * 3.0 / 4.0};
    }

    private List<PlacementCandidate> cornerCandidates(FurnitureSpec spec, Room room) {
        FurnitureBoundary.UsableBounds usable = FurnitureBoundary.usableBounds(room).orElse(null);
        if (usable == null) return List.of();
        FurnitureBoundary.Footprint footprint = footprint(spec, 0);
        double minCenterX = usable.minX() - footprint.minX();
        double maxCenterX = usable.maxX() - footprint.maxX();
        double minCenterZ = usable.minZ() - footprint.minZ();
        double maxCenterZ = usable.maxZ() - footprint.maxZ();
        if (maxCenterX < minCenterX || maxCenterZ < minCenterZ) return List.of();
        return List.of(
                new PlacementCandidate(new Position(minCenterX, minCenterZ), 0, PlacementRelation.CORNER, 0),
                new PlacementCandidate(new Position(maxCenterX, minCenterZ), 0, PlacementRelation.CORNER, 1),
                new PlacementCandidate(new Position(maxCenterX, maxCenterZ), 0, PlacementRelation.CORNER, 2),
                new PlacementCandidate(new Position(minCenterX, maxCenterZ), 0, PlacementRelation.CORNER, 3)
        );
    }

    private List<PlacementCandidate> windowCandidates(FurnitureSpec spec, Room room) {
        FurnitureBoundary.UsableBounds usable = FurnitureBoundary.usableBounds(room).orElse(null);
        if (usable == null) return List.of();
        List<PlacementCandidate> candidates = new ArrayList<>();
        int order = 0;
        for (Opening opening : room.getOpenings()) {
            if (!"window".equals(opening.getType())) continue;
            PlacementCandidate candidate = windowCandidate(spec, usable, opening, order++);
            if (candidate != null) candidates.add(candidate);
        }
        return candidates;
    }

    private PlacementCandidate windowCandidate(FurnitureSpec spec, FurnitureBoundary.UsableBounds usable,
                                               Opening opening, int order) {
        double rotation = switch (opening.getWall()) {
            case "south" -> 0;
            case "east" -> 90;
            case "north" -> 180;
            case "west" -> 270;
            default -> -1;
        };
        if (rotation < 0) return null;
        FurnitureBoundary.Footprint footprint = footprint(spec, rotation);
        double minCenterX = usable.minX() - footprint.minX();
        double maxCenterX = usable.maxX() - footprint.maxX();
        double minCenterZ = usable.minZ() - footprint.minZ();
        double maxCenterZ = usable.maxZ() - footprint.maxZ();
        if (maxCenterX < minCenterX || maxCenterZ < minCenterZ) return null;
        double openingCenter = opening.getOffset() + opening.getWidth() / 2.0;
        Position position = switch (opening.getWall()) {
            case "south" -> new Position(clamp(openingCenter, minCenterX, maxCenterX), minCenterZ);
            case "east" -> new Position(maxCenterX, clamp(openingCenter, minCenterZ, maxCenterZ));
            case "north" -> new Position(clamp(openingCenter, minCenterX, maxCenterX), maxCenterZ);
            case "west" -> new Position(minCenterX, clamp(openingCenter, minCenterZ, maxCenterZ));
            default -> throw new IllegalStateException("Unsupported window wall " + opening.getWall());
        };
        return new PlacementCandidate(position, rotation, PlacementRelation.WINDOW, order);
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private List<PlacementCandidate> safeCandidates(String itemType, FurnitureSpec spec,
                                                    Room room, List<Furniture> placed) {
        List<Position> positions = switch (itemType) {
            case "desk" -> List.of(
                    new Position(2.3, 1.0),
                    new Position(2.4, 1.4),
                    new Position(2.3, 2.0),
                    new Position(room.getWidth() - spec.width() / 2.0, 1.0)
            );
            case "desk_chair" -> List.of(
                    new Position(2.3, 1.8),
                    new Position(1.8, 2.7),
                    new Position(2.5, 2.2)
            );
            case "mood_lamp" -> List.of(
                    new Position(2.0, 0.4),
                    new Position(2.9, 1.0),
                    new Position(1.7, 2.7)
            );
            case "storage" -> List.of(
                    new Position(2.7, 3.9),
                    new Position(0.6, 3.7),
                    new Position(2.6, 2.8)
            );
            case "rug" -> List.of(
                    new Position(1.1, 3.3),
                    new Position(1.8, 2.9),
                    new Position(1.6, 3.4)
            );
            case "bed" -> List.of(
                    new Position(0.8, 1.4),
                    new Position(0.8, 3.1),
                    new Position(1.1, 2.8)
            );
            default -> List.of(
                    new Position(2.2, 2.0),
                    new Position(1.6, 3.1),
                    new Position(0.8, 3.3)
            );
        };
        List<PlacementCandidate> candidates = new ArrayList<>();
        for (int index = 0; index < positions.size(); index++) {
            candidates.add(new PlacementCandidate(positions.get(index), 0,
                    PlacementRelation.SAFE, index));
        }
        return candidates;
    }

    private List<PlacementCandidate> gridCandidates(FurnitureSpec spec, Room room) {
        List<PlacementCandidate> candidates = new ArrayList<>();
        double halfWidth = spec.width() / 2.0 + FurnitureBoundary.WALL_CLEARANCE_METERS;
        double halfDepth = spec.depth() / 2.0 + FurnitureBoundary.WALL_CLEARANCE_METERS;
        int order = 0;
        for (int xIndex = 0; xIndex < 4; xIndex++) {
            for (int zIndex = 0; zIndex < 4; zIndex++) {
                double x = halfWidth + (room.getWidth() - 2 * halfWidth) * xIndex / 3.0;
                double z = halfDepth + (room.getDepth() - 2 * halfDepth) * zIndex / 3.0;
                candidates.add(new PlacementCandidate(new Position(x, z), 0,
                        PlacementRelation.GRID, order++));
            }
        }
        return candidates;
    }

    private List<PlacementCandidate> deduplicate(List<PlacementCandidate> candidates) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        return candidates.stream()
                .filter(candidate -> seen.add(candidate.position().getX() + ":"
                        + candidate.position().getZ() + ":" + candidate.rotation()))
                .toList();
    }

    private FurnitureBoundary.Footprint footprint(FurnitureSpec spec, double rotation) {
        return FurnitureBoundary.footprint(spec.width(), spec.depth(), rotation, spec.variantId());
    }

    private Position forward(double rotation) {
        return switch ((int) normalize(rotation)) {
            case 90 -> new Position(-1, 0);
            case 180 -> new Position(0, -1);
            case 270 -> new Position(1, 0);
            default -> new Position(0, 1);
        };
    }

    private Position right(double rotation) {
        return switch ((int) normalize(rotation)) {
            case 90 -> new Position(0, -1);
            case 180 -> new Position(-1, 0);
            case 270 -> new Position(0, 1);
            default -> new Position(1, 0);
        };
    }

    private double normalize(double rotation) {
        double normalized = rotation % 360.0;
        return normalized < 0 ? normalized + 360.0 : normalized;
    }

    private int dependencyRank(String type) {
        if (Set.of("bed", "desk", "sofa", "sofa_bed", "media_console").contains(type)) return 0;
        if (Set.of("nightstand", "desk_chair", "monitor", "side_table", "tv", "mood_lamp").contains(type)) return 2;
        return 1;
    }

    private Optional<Furniture> findPlacedByType(List<Furniture> placed, String type) {
        return placed.stream()
                .filter(this::isActivePlacedFurniture)
                .filter(furniture -> GeneratedFurnitureCatalog.get().sameType(type, furniture.getType()))
                .findFirst();
    }

    private Optional<Furniture> findPlacedByTypes(List<Furniture> placed, String... types) {
        for (String type : types) {
            Optional<Furniture> match = findPlacedByType(placed, type);
            if (match.isPresent()) return match;
        }
        return Optional.empty();
    }

    private boolean isActivePlacedFurniture(Furniture furniture) {
        return furniture.getStatus() != FurnitureStatus.DELETED;
    }

    private boolean supportedType(String type) {
        return "storage".equals(type) || GeneratedFurnitureCatalog.get().products().stream()
                .anyMatch(product -> type.equals(product.getType()));
    }

    private List<Furniture> appended(List<Furniture> furniture, Furniture candidate) {
        List<Furniture> snapshot = new ArrayList<>(furniture);
        snapshot.add(candidate);
        return snapshot;
    }

    private boolean isHardValid(ValidationResult result) {
        return result.isCollisionFree() && result.isBoundaryValid() && result.isDoorClearance()
                && result.isWindowClearance() && result.isPathSecured();
    }

    private String validationReason(ValidationResult result) {
        if (!result.isBoundaryValid()) return "NO_VALID_BOUNDARY_PLACEMENT";
        if (!result.isCollisionFree()) return "COLLISION_DETECTED";
        if (!result.isDoorClearance()) return "DOOR_BLOCKED";
        if (!result.isWindowClearance()) return "WINDOW_BLOCKED";
        if (!result.isPathSecured()) return "MOVEMENT_PATH_BLOCKED";
        return "NO_VALID_PLACEMENT";
    }

    // validationReason()과 동일한 순서 — 빈도가 같을 때 이 순서로 deterministic하게
    // tie-break한다.
    private static final List<String> FAILURE_REASON_TIE_BREAK_ORDER = List.of(
            "NO_VALID_BOUNDARY_PLACEMENT", "COLLISION_DETECTED", "DOOR_BLOCKED",
            "WINDOW_BLOCKED", "MOVEMENT_PATH_BLOCKED", "NO_VALID_PLACEMENT");

    private String mostLikelyFailureReason(Map<String, Integer> reasonCounts) {
        if (reasonCounts.isEmpty()) {
            return "NO_VALID_PLACEMENT";
        }
        return reasonCounts.entrySet().stream()
                .max(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                        .thenComparing(entry -> -FAILURE_REASON_TIE_BREAK_ORDER.indexOf(entry.getKey())))
                .map(Map.Entry::getKey)
                .orElse("NO_VALID_PLACEMENT");
    }

    private UnplacedFurniture unplaced(int index, String type, MockProduct product, String reasonCode) {
        return new UnplacedFurniture(index, type, product == null ? null : product.getProductId(),
                product == null ? null : product.getVariantId(), reasonCode, failureMessage(type, reasonCode));
    }

    private String failureMessage(String type, String reasonCode) {
        return switch (reasonCode) {
            case "UNSUPPORTED_FURNITURE_TYPE" -> "지원하지 않는 가구 유형입니다.";
            case "NO_RENDERABLE_PRODUCT" -> "렌더링 가능한 " + type + " 제품을 찾을 수 없습니다.";
            case "NO_VALID_BOUNDARY_PLACEMENT" -> type + "를 방 경계 안에 배치할 수 없습니다.";
            case "COLLISION_DETECTED" -> type + "가 기존 가구와 충돌합니다.";
            case "DOOR_BLOCKED" -> type + "가 문 앞 공간을 가립니다.";
            case "WINDOW_BLOCKED" -> type + "가 창문 앞 공간을 가립니다.";
            case "MOVEMENT_PATH_BLOCKED" -> type + "가 주요 이동 동선을 막습니다.";
            default -> type + "를 안전하게 배치할 공간이 부족합니다.";
        };
    }

    private String generateFurnitureId(String type, List<Furniture> furniture) {
        String prefix = type + "-rec-";
        int sequence = 1;
        Set<String> ids = furniture.stream().map(Furniture::getId).collect(Collectors.toSet());
        while (ids.contains(prefix + sequence)) sequence++;
        return prefix + sequence;
    }

    private Furniture copyFurniture(Furniture furniture) {
        return copyFurniture(furniture, furniture.getPosition());
    }

    private PlacementResult scriptedRecommendation(Room room, List<Furniture> scriptedFurniture) {
        List<Furniture> furniture = new ArrayList<>(room.getFurniture().stream()
                .filter(this::isActivePlacedFurniture)
                .map(this::copyFurniture)
                .toList());
        Set<String> existingIds = furniture.stream().map(Furniture::getId).collect(Collectors.toSet());
        scriptedFurniture.stream()
                .filter(item -> !existingIds.contains(item.getId()))
                .map(item -> copyFurniture(item,
                        FurnitureBoundary.clamp(room, item.getPosition(), item).orElse(item.getPosition())))
                .forEach(furniture::add);

        ValidationResult validation = validationService.validate(room, furniture);
        RecommendationExecutionStatus outcome = isHardValid(validation)
                ? RecommendationExecutionStatus.SUCCESS
                : validation.isBoundaryValid() && !furniture.isEmpty()
                ? RecommendationExecutionStatus.PARTIAL_SUCCESS
                : RecommendationExecutionStatus.FAILED;
        String warningCode = outcome == RecommendationExecutionStatus.SUCCESS
                ? null : "LAYOUT_VALIDATION_FAILED";
        String message = switch (outcome) {
            case SUCCESS -> "고정 데모 배치가 모든 검증 조건을 통과했습니다.";
            case PARTIAL_SUCCESS -> "고정 데모 배치를 반환했지만 일부 검증 조건을 통과하지 못했습니다.";
            case FAILED -> "고정 데모 배치가 필수 검증 조건을 통과하지 못했습니다.";
        };
        return new PlacementResult(RecommendationStatus.SUCCESS, furniture, ScoreSummary.defaultSummary(),
                0, 0, List.of(), outcome, warningCode, message);
    }

    private Furniture copyFurniture(Furniture furniture, Position position) {
        return new Furniture(
                furniture.getId(),
                furniture.getType(),
                furniture.getLabel(),
                furniture.getWidth(),
                furniture.getDepth(),
                furniture.getHeight(),
                new Position(position.getX(), position.getZ()),
                furniture.getRotation(),
                furniture.getStatus(),
                furniture.getProductId(),
                furniture.getStyleTags(),
                furniture.getVariantId()
        );
    }

    private enum PlacementRelation {
        SUPPORT, ANCHOR_SIDE, ANCHOR_FRONT, WALL, CORNER, SAFE, GRID, WINDOW
    }

    private record PlacementCandidate(Position position, double rotation,
                                      PlacementRelation relation, int order) {
    }

    private record RequestedItem(int originalIndex, String requestedType, String canonicalType) {
    }

    private record FurnitureSpec(String type, String label, double width, double depth, double height,
                                 String productId, List<String> styleTags, String variantId) {

        private static FurnitureSpec from(String itemType, MockProduct product) {
            if (product != null) {
                return new FurnitureSpec(product.getType(), product.getName(), product.getWidth(), product.getDepth(),
                        product.getHeight(), product.getProductId(), product.getStyleTags(), product.getVariantId());
            }

            return switch (itemType) {
                case "bed" -> new FurnitureSpec("bed", "bed", 1.1, 2.0, 0.45, null, List.of(), null);
                case "desk" -> new FurnitureSpec("desk", "desk", 1.0, 0.6, 0.75, null, List.of(), null);
                case "chair" -> new FurnitureSpec("chair", "chair", 0.45, 0.45, 0.8, null, List.of(), null);
                case "storage" -> new FurnitureSpec("storage", "storage", 0.8, 0.4, 1.6, null, List.of(), null);
                case "rug" -> new FurnitureSpec("rug", "rug", 1.2, 1.6, 0.02, null, List.of(), null);
                case "lamp" -> new FurnitureSpec("lamp", "lamp", 0.25, 0.25, 1.4, null, List.of(), null);
                default -> new FurnitureSpec(itemType, itemType, 1.0, 0.6, 0.7, null, List.of(), null);
            };
        }
    }

    private record PlacementAttempt(Furniture furniture, MockProduct product, String reasonCode) {
    }
}
