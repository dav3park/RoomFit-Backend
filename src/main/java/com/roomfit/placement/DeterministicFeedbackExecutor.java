package com.roomfit.placement;

import com.roomfit.agent.domain.AgentContext;
import com.roomfit.product.catalog.GeneratedFurnitureCatalog;
import com.roomfit.product.domain.MockProduct;
import com.roomfit.product.repository.MockProductRepository;
import com.roomfit.room.Furniture;
import com.roomfit.room.FurnitureBoundary;
import com.roomfit.room.FurnitureStatus;
import com.roomfit.room.Opening;
import com.roomfit.room.Position;
import com.roomfit.room.Room;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Service
public class DeterministicFeedbackExecutor {

    private static final int MAX_ADD_VALIDATIONS = 24;
    private static final int MAX_SWAP_VALIDATIONS = 24;
    private static final double METRIC_TOLERANCE = 1.0e-9;

    private final ValidationService validationService;
    private final MockProductRepository productRepository;
    private final RenderableProductCatalog renderableCatalog;
    private final ScoreService scoreService;
    private final FeedbackPlanValidator planValidator;
    private final FeedbackPlacementCandidateGenerator candidateGenerator;

    @Autowired
    public DeterministicFeedbackExecutor(ValidationService validationService,
                                         MockProductRepository productRepository,
                                         RenderableProductCatalog renderableCatalog,
                                         ScoreService scoreService) {
        this.validationService = validationService;
        this.productRepository = productRepository;
        this.renderableCatalog = renderableCatalog;
        this.scoreService = scoreService;
        this.planValidator = new FeedbackPlanValidator();
        this.candidateGenerator = new FeedbackPlacementCandidateGenerator();
    }

    DeterministicFeedbackExecutor(ValidationService validationService, MockProductRepository productRepository) {
        this(validationService, productRepository, new RenderableProductCatalog(productRepository.findAll()), new ScoreService());
    }

    DeterministicFeedbackExecutor(ValidationService validationService,
                                  MockProductRepository productRepository,
                                  RenderableProductCatalog renderableCatalog,
                                  ScoreService scoreService,
                                  FeedbackPlacementCandidateGenerator candidateGenerator) {
        this.validationService = validationService;
        this.productRepository = productRepository;
        this.renderableCatalog = renderableCatalog;
        this.scoreService = scoreService;
        this.planValidator = new FeedbackPlanValidator();
        this.candidateGenerator = candidateGenerator;
    }

    public FeedbackExecution execute(FeedbackPlan plan, Room room, List<Furniture> original) {
        return execute(plan, room, original, null);
    }

    public FeedbackExecution execute(FeedbackPlan plan, Room room, List<Furniture> original, AgentContext context) {
        return execute(plan, room, original, context, FeedbackPlanValidator.MAX_FEEDBACK_OPERATIONS);
    }

    public FeedbackExecution execute(FeedbackPlan plan, Room room, List<Furniture> original,
                                     AgentContext context, int maxOperations) {
        planValidator.validate(plan, maxOperations);
        List<String> requested = plan.operations().stream().map(operation -> operation.type().name()).toList();
        if (plan.needsClarification()) {
            return noChange(original, plan, requested, "NEEDS_CLARIFICATION",
                    summaryFor("NEEDS_CLARIFICATION"), List.of());
        }
        if (plan.operations().isEmpty()) {
            return noChange(original, plan, requested, "UNSUPPORTED_OPERATION",
                    "이번 피드백에서는 요청한 작업을 지원하지 않습니다.", List.of());
        }
        FeedbackOperationExecution unsafeCompositeSwap = unsafeCompositeMetadataSwap(plan, room, original);
        if (unsafeCompositeSwap != null) {
            return noChange(original, plan, requested, unsafeCompositeSwap.reasonCode(),
                    summaryFor(unsafeCompositeSwap.reasonCode()), List.of(unsafeCompositeSwap));
        }

        List<Furniture> working = new ArrayList<>(original);
        List<FeedbackOperationExecution> operationResults = new ArrayList<>();
        Set<String> appliedOperationIds = new HashSet<>();

        for (FeedbackOperation operation : plan.operations()) {
            if (!appliedOperationIds.containsAll(operation.dependsOn())) {
                operationResults.add(FeedbackOperationExecution.skipped(operation, "DEPENDENCY_NOT_APPLIED"));
                return atomicFailure(original, plan, requested, "DEPENDENCY_NOT_APPLIED", operationResults);
            }

            OperationAttempt attempt = applyOperation(operation, room, working, context);
            if (!attempt.applied()) {
                operationResults.add(FeedbackOperationExecution.failed(operation, attempt.reasonCode()));
                return atomicFailure(original, plan, requested, attempt.reasonCode(), operationResults);
            }

            working = new ArrayList<>(attempt.snapshot());
            appliedOperationIds.add(operation.operationId());
            operationResults.add(FeedbackOperationExecution.applied(operation, attempt.affectedFurnitureId()));
        }

        return new FeedbackExecution(working,
                new FeedbackResult(true, plan.source(), plan.fallbackUsed(),
                        "적용 가능한 배치 변경을 반영했습니다.", requested,
                        plan.operations().stream().map(operation -> operation.type().name()).toList(), null),
                operationResults);
    }

    /**
     * Feedback composites are transactional at the layout level.  Attempts run
     * against an isolated snapshot, but a later failure rolls every earlier
     * operation back before the result reaches LayoutService.
     */
    private FeedbackExecution atomicFailure(List<Furniture> original, FeedbackPlan plan, List<String> requested,
                                            String failureReason,
                                            List<FeedbackOperationExecution> operationResults) {
        List<FeedbackOperationExecution> rolledBack = operationResults.stream()
                .map(result -> result.status() == FeedbackOperationExecution.Status.APPLIED
                        ? new FeedbackOperationExecution(result.operationId(), result.type(),
                                FeedbackOperationExecution.Status.FAILED, "ATOMIC_ROLLBACK", null)
                        : result)
                .toList();
        return noChange(original, plan, requested, failureReason, summaryFor(failureReason), rolledBack);
    }

    /** A metadata-constrained swap must be decidable before a preceding composite operation mutates the layout. */
    private FeedbackOperationExecution unsafeCompositeMetadataSwap(FeedbackPlan plan, Room room,
                                                                    List<Furniture> original) {
        if (plan.requestKind() != FeedbackRequestKind.COMPOSITE) return null;
        for (FeedbackOperation operation : plan.operations()) {
            if (operation.type() != FeedbackOperationType.SWAP_FURNITURE
                    || operation.replacementRequirements().styleKeywords().isEmpty()) {
                continue;
            }
            TargetResolution target = resolveTarget(operation.target(), original, room);
            if (target.status() != TargetResolutionStatus.RESOLVED) {
                return FeedbackOperationExecution.failed(operation, targetFailureReason(target.status(), false));
            }
            Furniture current = original.get(target.index());
            if (!renderableCatalog.sameFurnitureType(current.getType(), operation.replacementRequirements().furnitureType())) {
                return FeedbackOperationExecution.failed(operation, "INVALID_SWAP_CANONICAL_TYPE");
            }
            RenderableProductCatalog.FurnitureSize size = new RenderableProductCatalog.FurnitureSize(
                    current.getWidth(), current.getDepth(), current.getHeight());
            long candidateCount = renderableCatalog.findSwapCandidates(operation.replacementRequirements(), size).stream()
                    .filter(product -> renderableCatalog.sameFurnitureType(current.getType(), product.getType()))
                    .filter(product -> !product.getProductId().equals(current.getProductId())
                            || !java.util.Objects.equals(product.getVariantId(), current.getVariantId()))
                    .count();
            if (candidateCount != 1) {
                return FeedbackOperationExecution.failed(operation, "NO_SAFE_SWAP_CANDIDATE");
            }
        }
        return null;
    }

    private OperationAttempt applyOperation(FeedbackOperation operation, Room room,
                                            List<Furniture> working, AgentContext context) {
        if (operation.type() == FeedbackOperationType.ADD_FURNITURE) {
            return add(room, working, operation, context);
        }

        TargetResolution resolution = resolveTarget(operation.target(), working, room);
        if (resolution.status() != TargetResolutionStatus.RESOLVED) {
            return OperationAttempt.failed(targetFailureReason(resolution.status(), false));
        }
        int targetIndex = resolution.index();

        Furniture reference = null;
        if (operation.type() == FeedbackOperationType.MOVE && operation.referenceTarget() != null) {
            TargetResolution referenceResolution = resolveTarget(operation.referenceTarget(), working, room);
            if (referenceResolution.status() != TargetResolutionStatus.RESOLVED) {
                return OperationAttempt.failed(targetFailureReason(referenceResolution.status(), true));
            }
            if (referenceResolution.index() == targetIndex) {
                return OperationAttempt.failed("INVALID_MOVE_REFERENCE");
            }
            reference = working.get(referenceResolution.index());
        }

        return switch (operation.type()) {
            case MOVE -> move(room, working, targetIndex, operation, reference);
            case ROTATE -> rotate(room, working, targetIndex, operation);
            case REPLACE_PRODUCT -> replace(room, working, targetIndex, operation.constraints());
            case REMOVE_FURNITURE -> remove(working, targetIndex);
            case SWAP_FURNITURE -> swap(room, working, targetIndex,
                    operation.replacementRequirements(), context);
            case ADD_FURNITURE -> throw new IllegalStateException("ADD is handled before target resolution");
            case CHANGE_MATERIAL, CHANGE_COLOR_TONE -> OperationAttempt.failed("UNSUPPORTED_OPERATION");
        };
    }

    private OperationAttempt add(Room room, List<Furniture> base, FeedbackOperation operation, AgentContext context) {
        if (operation.referenceTarget() != null && !isReferenceRelation(operation.placement().relation())) {
            return OperationAttempt.failed("INVALID_ADD_REFERENCE");
        }
        Furniture reference = null;
        if (operation.referenceTarget() != null) {
            TargetResolution referenceResolution = resolveTarget(operation.referenceTarget(), base, room);
            if (referenceResolution.status() != TargetResolutionStatus.RESOLVED) {
                return OperationAttempt.failed(targetFailureReason(referenceResolution.status(), true));
            }
            reference = base.get(referenceResolution.index());
        }

        List<MockProduct> products = renderableCatalog.findCandidates(operation.productRequirements(), null);
        if (products.isEmpty()) {
            return OperationAttempt.failed("NO_RENDERABLE_PRODUCT");
        }

        String furnitureId = generateFurnitureId(operation.productRequirements().furnitureType(), base);
        List<CandidateEvaluation> validCandidates = new ArrayList<>();
        boolean boundaryFitAvailable = products.stream().anyMatch(product -> fitsRoomAtSupportedRotation(room, product));
        int validations = 0;
        int globalOrder = 0;
        for (MockProduct product : products) {
            List<FeedbackPlacementCandidateGenerator.PlacementCandidate> placements =
                    additionPlacements(room, base, product, operation, reference);
            for (FeedbackPlacementCandidateGenerator.PlacementCandidate placement : placements) {
                if (validations++ == MAX_ADD_VALIDATIONS) break;
                Furniture added = furnitureFromProduct(furnitureId, product, placement.position(),
                        placement.rotation(), FurnitureStatus.RECOMMENDED);
                List<Furniture> snapshot = appended(base, added);
                ValidationResult validation = validationService.validateChange(room, base, snapshot);
                if (hardValid(validation)) {
                    validCandidates.add(new CandidateEvaluation(snapshot, furnitureId, product.getProductId(),
                            score(context, snapshot, validation),
                            distanceFromReference(placement.position(), reference, room), globalOrder));
                }
                globalOrder++;
            }
            if (validations >= MAX_ADD_VALIDATIONS) break;
        }

        return bestCandidate(validCandidates)
                .map(candidate -> OperationAttempt.applied(candidate.snapshot(), candidate.affectedFurnitureId()))
                .orElseGet(() -> OperationAttempt.failed(boundaryFitAvailable
                        ? "NO_VALID_ADD_PLACEMENT" : "NO_VALID_BOUNDARY_PLACEMENT"));
    }

    private List<FeedbackPlacementCandidateGenerator.PlacementCandidate> additionPlacements(
            Room room, List<Furniture> base, MockProduct product, FeedbackOperation operation, Furniture reference) {
        String type = GeneratedFurnitureCatalog.get().normalizeType(product.getType());
        var policy = GeneratedFurnitureCatalog.get().placementPolicy(product.getVariantId())
                .orElseGet(() -> com.roomfit.product.catalog.FurniturePlacementPolicy.legacy(type));
        if (!"SUPPORT".equals(policy.installation())) {
            return candidateGenerator.forAdd(room, product, operation.placement(), reference);
        }
        return base.stream()
                .filter(this::active)
                .filter(item -> policy.supportTypes().contains(GeneratedFurnitureCatalog.get().normalizeType(item.getType())))
                .map(support -> new FeedbackPlacementCandidateGenerator.PlacementCandidate(
                        FurnitureInstallationPolicy.supportPosition(support),
                        support.getRotation(), 0))
                .toList();
    }

    private boolean isReferenceRelation(FeedbackRelation relation) {
        return relation == FeedbackRelation.NEXT_TO
                || relation == FeedbackRelation.LEFT_OF
                || relation == FeedbackRelation.RIGHT_OF;
    }

    private OperationAttempt remove(List<Furniture> base, int targetIndex) {
        String removedId = base.get(targetIndex).getId();
        List<Furniture> snapshot = new ArrayList<>(base);
        snapshot.remove(targetIndex);
        return OperationAttempt.applied(snapshot, removedId);
    }

    private OperationAttempt swap(Room room, List<Furniture> base, int targetIndex,
                                  FeedbackProductRequirements requirements, AgentContext context) {
        Furniture current = base.get(targetIndex);
        if (!renderableCatalog.sameFurnitureType(current.getType(), requirements.furnitureType())) {
            return OperationAttempt.failed("INVALID_SWAP_CANONICAL_TYPE");
        }
        RenderableProductCatalog.FurnitureSize referenceSize = new RenderableProductCatalog.FurnitureSize(
                current.getWidth(), current.getDepth(), current.getHeight());
        List<MockProduct> products = renderableCatalog.findSwapCandidates(requirements, referenceSize).stream()
                .filter(product -> renderableCatalog.sameFurnitureType(current.getType(), product.getType()))
                .filter(product -> !product.getProductId().equals(current.getProductId())
                        || !java.util.Objects.equals(product.getVariantId(), current.getVariantId()))
                .toList();
        if (!requirements.styleKeywords().isEmpty() && products.size() != 1) {
            return OperationAttempt.failed("NO_SAFE_SWAP_CANDIDATE");
        }
        if (products.isEmpty()) {
            return OperationAttempt.failed("NO_RENDERABLE_PRODUCT");
        }

        List<CandidateEvaluation> validCandidates = new ArrayList<>();
        boolean boundaryFitAvailable = products.stream().anyMatch(product -> fitsRoomAtSupportedRotation(room, product));
        int validations = 0;
        int globalOrder = 0;
        for (MockProduct product : products) {
            for (FeedbackPlacementCandidateGenerator.PlacementCandidate placement
                    : candidateGenerator.forSwap(room, current, product)) {
                if (validations++ == MAX_SWAP_VALIDATIONS) break;
                Furniture swapped = furnitureFromProduct(current.getId(), product, placement.position(),
                        placement.rotation(), current.getStatus());
                List<Furniture> snapshot = replace(base, targetIndex, swapped);
                ValidationResult validation = validationService.validateChange(room, base, snapshot);
                if (hardValid(validation)) {
                    validCandidates.add(new CandidateEvaluation(snapshot, current.getId(), product.getProductId(),
                            score(context, snapshot, validation),
                            distance(current.getPosition(), placement.position()), globalOrder));
                }
                globalOrder++;
            }
            if (validations >= MAX_SWAP_VALIDATIONS) break;
        }

        return bestCandidate(validCandidates)
                .map(candidate -> OperationAttempt.applied(candidate.snapshot(), candidate.affectedFurnitureId()))
                .orElseGet(() -> OperationAttempt.failed(boundaryFitAvailable
                        ? "NO_SAFE_SWAP_CANDIDATE" : "NO_VALID_BOUNDARY_PLACEMENT"));
    }

    private OperationAttempt move(Room room, List<Furniture> base, int index, FeedbackOperation operation,
                                  Furniture reference) {
        Furniture current = base.get(index);
        if (FurnitureBoundary.clamp(room, current.getPosition(), current).isEmpty()) {
            return OperationAttempt.failed("NO_VALID_BOUNDARY_PLACEMENT");
        }
        if (reference!=null && operation.placement().relation()==FeedbackRelation.NEXT_TO
                && FurnitureInstallationPolicy.policy(current).supportTypes().contains(GeneratedFurnitureCatalog.get().normalizeType(reference.getType()))) {
            Furniture updated=copy(current,FurnitureInstallationPolicy.supportPosition(reference),reference.getRotation());
            List<Furniture> snapshot=replace(base,index,updated);
            if(!samePosition(current,updated)&&valid(room,base,snapshot))return OperationAttempt.applied(snapshot,current.getId());
            return OperationAttempt.failed("NO_VALID_MOVE_PLACEMENT");
        }
        if (semanticMove(operation.placement().relation())) {
            for (FeedbackPlacementCandidateGenerator.PlacementCandidate candidate
                    : candidateGenerator.forMove(room, current, operation.placement(), reference)) {
                Furniture updated = copy(current, candidate.position(), candidate.rotation());
                List<Furniture> snapshot = replace(base, index, updated);
                if (!samePosition(current, updated) && valid(room, base, snapshot)) {
                    return OperationAttempt.applied(snapshot, current.getId());
                }
            }
            return OperationAttempt.failed("NO_VALID_MOVE_PLACEMENT");
        }
        for (Position position : movePositions(room, current, operation)) {
            Furniture updated = copy(current, position, current.getRotation());
            List<Furniture> snapshot = replace(base, index, updated);
            if (!samePosition(current, updated) && valid(room, base, snapshot)) {
                return OperationAttempt.applied(snapshot, current.getId());
            }
        }
        return OperationAttempt.failed("NO_VALID_MOVE_PLACEMENT");
    }

    private boolean semanticMove(FeedbackRelation relation) {
        return relation == FeedbackRelation.IN_CORNER || relation == FeedbackRelation.NEXT_TO
                || relation == FeedbackRelation.LEFT_OF || relation == FeedbackRelation.RIGHT_OF;
    }

    private List<Position> movePositions(Room room, Furniture item, FeedbackOperation operation) {
        FeedbackPlacement placement = operation.placement();
        double distance = FeedbackPlacementPolicy.movementDistance(placement.magnitude());
        FurnitureBoundary.Footprint footprint = FurnitureBoundary.footprint(item);
        double x = item.getPosition().getX();
        double z = item.getPosition().getZ();
        Position delta = switch (placement.relation()) {
            case LEFT -> new Position(x - distance, z);
            case RIGHT -> new Position(x + distance, z);
            case FORWARD -> new Position(x, z - distance);
            case BACKWARD -> new Position(x, z + distance);
            case CENTER -> new Position(room.getWidth() / 2.0, room.getDepth() / 2.0);
            case NEAR_WALL -> new Position(room.getWidth() - footprint.effectiveWidth() / 2.0
                    - FurnitureBoundary.WALL_CLEARANCE_METERS, z);
            case NEAR_WINDOW -> new Position(x, room.getDepth() - footprint.effectiveDepth() / 2.0
                    - FurnitureBoundary.WALL_CLEARANCE_METERS);
            case AWAY_FROM_DOOR -> new Position(x,
                    Math.min(room.getDepth() - footprint.effectiveDepth() / 2.0
                            - FurnitureBoundary.WALL_CLEARANCE_METERS, z + distance));
            case NEXT_TO, LEFT_OF, RIGHT_OF, IN_CORNER ->
                    throw new IllegalArgumentException("Unsupported MOVE relation: " + placement.relation());
        };
        Position clamped = FurnitureBoundary.clamp(room, delta, footprint).orElse(null);
        return clamped == null || samePosition(delta, clamped) ? List.of(delta) : List.of(delta, clamped);
    }

    private OperationAttempt rotate(Room room, List<Furniture> base, int index, FeedbackOperation operation) {
        Furniture current = base.get(index);
        boolean boundaryBlocked = false;
        for (double rotation : FeedbackPlacementPolicy.rotationCandidates(
                current.getRotation(), operation.placement().orientation())) {
            if (rotation == FeedbackPlacementPolicy.normalize(current.getRotation())) continue;
            FurnitureBoundary.Footprint footprint = FurnitureBoundary.footprint(
                    current.getWidth(), current.getDepth(), rotation, current.getVariantId());
            Position position = FurnitureBoundary.clamp(room, current.getPosition(), footprint).orElse(null);
            if (position == null) {
                boundaryBlocked = true;
                continue;
            }
            if (!FurnitureBoundary.isInside(room, current.getPosition(), footprint)) {
                boundaryBlocked = true;
            }
            Furniture updated = copy(current, position, rotation);
            List<Furniture> snapshot = replace(base, index, updated);
            if (valid(room, base, snapshot)) {
                return OperationAttempt.applied(snapshot, current.getId());
            }
        }
        return OperationAttempt.failed(boundaryBlocked ? "ROTATION_OUT_OF_BOUNDS" : "NO_VALID_ROTATION");
    }

    private OperationAttempt replace(Room room, List<Furniture> base, int index,
                                     FeedbackReplaceConstraints constraints) {
        Furniture current = base.get(index);
        if (!validReplaceConstraints(current, constraints)) {
            return OperationAttempt.failed("INVALID_REPLACE_CONSTRAINTS");
        }
        if (currentProductMatches(current, constraints)) {
            return OperationAttempt.failed("CURRENT_PRODUCT_ALREADY_MATCHES");
        }
        List<MockProduct> products = productRepository.findAll().stream()
                .filter(renderableCatalog::isRenderable)
                .filter(product -> renderableCatalog.sameFurnitureType(current.getType(), product.getType()))
                .filter(product -> !product.getProductId().equals(current.getProductId()))
                .filter(product -> !constraints.largerThanCurrent() || product.getWidth() > current.getWidth())
                .filter(product -> !constraints.smallerThanCurrent() || product.getWidth() < current.getWidth())
                .filter(product -> constraints.minWidth() == null || product.getWidth() >= constraints.minWidth())
                .filter(product -> product.getStyleTags().containsAll(constraints.requiredStyleTags()))
                .filter(product -> product.getLifestyleTags().containsAll(constraints.requiredLifestyleTags()))
                .filter(product -> !constraints.storagePreferred() || hasStorage(product))
                .sorted(productComparator(constraints.storagePreferred()))
                .toList();
        if (products.isEmpty()) {
            return OperationAttempt.failed(constraints.largerThanCurrent() ? "NO_LARGER_PRODUCT_AVAILABLE"
                    : constraints.smallerThanCurrent() ? "NO_SMALLER_PRODUCT_AVAILABLE" : "NO_MATCHING_PRODUCT");
        }
        boolean boundaryFitAvailable = products.stream().anyMatch(product -> fitsRoomAtSupportedRotation(room, product));
        for (MockProduct product : products) {
            Optional<Furniture> placed = replacement(room, base, index, current, product);
            if (placed.isPresent()) {
                return OperationAttempt.applied(replace(base, index, placed.get()), current.getId());
            }
        }
        return OperationAttempt.failed(boundaryFitAvailable
                ? "NO_VALID_PRODUCT_PLACEMENT" : "NO_VALID_BOUNDARY_PLACEMENT");
    }

    private TargetResolution resolveTarget(FeedbackTargetSelector target, List<Furniture> furniture, Room room) {
        if (!target.furnitureId().isBlank()) {
            for (int i = 0; i < furniture.size(); i++) {
                if (target.furnitureId().equals(furniture.get(i).getId()) && active(furniture.get(i))) {
                    return TargetResolution.resolved(i);
                }
            }
            return TargetResolution.notFound();
        }

        List<Integer> matches = new ArrayList<>();
        String labelKeyword = target.labelKeyword().toLowerCase(Locale.ROOT);
        for (int i = 0; i < furniture.size(); i++) {
            Furniture item = furniture.get(i);
            if (!active(item)) continue;
            if (!target.furnitureType().isBlank()
                    && !renderableCatalog.sameFurnitureType(target.furnitureType(), item.getType())) continue;
            String label = item.getLabel() == null ? "" : item.getLabel().toLowerCase(Locale.ROOT);
            if (!labelKeyword.isBlank() && !label.contains(labelKeyword)) continue;
            matches.add(i);
        }
        if (matches.isEmpty()) return TargetResolution.notFound();

        if (target.locationHint() != null) {
            return resolveByLocationHint(target.locationHint(), matches, furniture, room);
        }
        if (target.ordinal() != null) {
            List<Integer> ordered = matches.stream()
                    .sorted(Comparator.comparingDouble((Integer index) -> furniture.get(index).getPosition().getX())
                            .thenComparingDouble(index -> furniture.get(index).getPosition().getZ())
                            .thenComparing(index -> furniture.get(index).getId()))
                    .toList();
            int ordinalIndex = target.ordinal() - 1;
            return ordinalIndex < ordered.size()
                    ? TargetResolution.resolved(ordered.get(ordinalIndex)) : TargetResolution.notFound();
        }
        if (matches.size() > 1) return TargetResolution.ambiguous();
        return TargetResolution.resolved(matches.getFirst());
    }

    private TargetResolution resolveByLocationHint(FeedbackLocationHint hint, List<Integer> matches,
                                                    List<Furniture> furniture, Room room) {
        if (hint == FeedbackLocationHint.NEAR_WINDOW
                && room.getOpenings().stream().noneMatch(opening -> "window".equals(opening.getType()))) {
            return TargetResolution.unsupportedSelector();
        }
        boolean maximum = hint == FeedbackLocationHint.LARGEST;
        double selectedMetric = maximum ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        List<Integer> selected = new ArrayList<>();
        for (Integer index : matches) {
            double metric = locationMetric(hint, furniture.get(index), room);
            boolean better = maximum ? metric > selectedMetric + METRIC_TOLERANCE
                    : metric < selectedMetric - METRIC_TOLERANCE;
            if (better) {
                selectedMetric = metric;
                selected.clear();
                selected.add(index);
            } else if (Math.abs(metric - selectedMetric) <= METRIC_TOLERANCE) {
                selected.add(index);
            }
        }
        return selected.size() == 1 ? TargetResolution.resolved(selected.getFirst()) : TargetResolution.ambiguous();
    }

    private double locationMetric(FeedbackLocationHint hint, Furniture item, Room room) {
        return switch (hint) {
            case CENTER -> distance(item.getPosition(), new Position(room.getWidth() / 2.0, room.getDepth() / 2.0));
            case LARGEST, SMALLEST -> item.getWidth() * item.getDepth() * item.getHeight();
            case NEAR_WINDOW -> room.getOpenings().stream()
                    .filter(opening -> "window".equals(opening.getType()))
                    .map(opening -> openingPosition(opening, room))
                    .mapToDouble(position -> distance(item.getPosition(), position))
                    .min()
                    .orElse(Double.POSITIVE_INFINITY);
        };
    }

    private Position openingPosition(Opening opening, Room room) {
        double center = opening.getOffset() + opening.getWidth() / 2.0;
        return switch (opening.getWall()) {
            case "north" -> new Position(center, room.getDepth());
            case "south" -> new Position(center, 0);
            case "east" -> new Position(room.getWidth(), center);
            case "west" -> new Position(0, center);
            default -> new Position(0, 0);
        };
    }

    private String targetFailureReason(TargetResolutionStatus status, boolean reference) {
        return switch (status) {
            case NOT_FOUND -> reference ? "REFERENCE_TARGET_NOT_FOUND" : "TARGET_NOT_FOUND";
            case AMBIGUOUS -> reference ? "AMBIGUOUS_REFERENCE_TARGET" : "AMBIGUOUS_TARGET";
            case UNSUPPORTED_SELECTOR -> reference
                    ? "UNSUPPORTED_REFERENCE_LOCATION_HINT" : "UNSUPPORTED_LOCATION_HINT";
            case RESOLVED -> throw new IllegalArgumentException("Resolved target has no failure reason");
        };
    }

    private Optional<CandidateEvaluation> bestCandidate(List<CandidateEvaluation> candidates) {
        return candidates.stream().min(
                Comparator.comparingInt(CandidateEvaluation::score).reversed()
                        .thenComparingDouble(CandidateEvaluation::movementDistance)
                        .thenComparing(CandidateEvaluation::productId)
                        .thenComparingInt(CandidateEvaluation::order)
        );
    }

    private boolean validReplaceConstraints(Furniture current, FeedbackReplaceConstraints constraints) {
        return constraints != null
                && constraints.furnitureType() != null
                && !constraints.furnitureType().isBlank()
                && renderableCatalog.sameFurnitureType(current.getType(), constraints.furnitureType())
                && (constraints.largerThanCurrent()
                || constraints.smallerThanCurrent()
                || constraints.minWidth() != null
                || !constraints.requiredStyleTags().isEmpty()
                || !constraints.requiredLifestyleTags().isEmpty()
                || constraints.storagePreferred());
    }

    private boolean currentProductMatches(Furniture current, FeedbackReplaceConstraints constraints) {
        if (current.getProductId() == null || constraints.largerThanCurrent() || constraints.smallerThanCurrent()) return false;
        return productRepository.findById(current.getProductId())
                .filter(product -> constraints.minWidth() == null || product.getWidth() >= constraints.minWidth())
                .filter(product -> product.getStyleTags().containsAll(constraints.requiredStyleTags()))
                .filter(product -> product.getLifestyleTags().containsAll(constraints.requiredLifestyleTags()))
                .filter(product -> !constraints.storagePreferred() || hasStorage(product))
                .isPresent();
    }

    private Comparator<MockProduct> productComparator(boolean storagePreferred) {
        Comparator<MockProduct> byWidth = Comparator.comparingDouble(MockProduct::getWidth);
        if (!storagePreferred) return byWidth;
        return Comparator.comparing((MockProduct product) -> !hasStorage(product)).thenComparing(byWidth);
    }

    private boolean hasStorage(MockProduct product) {
        return product.getVariantId() != null && product.getVariantId().contains("storage");
    }

    private boolean fitsRoomAtSupportedRotation(Room room, MockProduct product) {
        Position center = new Position(room.getWidth() / 2.0, room.getDepth() / 2.0);
        return List.of(0.0, 90.0).stream().anyMatch(rotation -> FurnitureBoundary.clamp(
                room, center, FurnitureBoundary.footprint(
                        product.getWidth(), product.getDepth(), rotation, product.getVariantId())).isPresent());
    }

    private Optional<Furniture> replacement(Room room, List<Furniture> base, int index,
                                            Furniture current, MockProduct product) {
        for (double rotation : replacementRotations(current.getRotation())) {
            Furniture prototype = furnitureFromProduct(current.getId(), product, current.getPosition(),
                    rotation, current.getStatus());
            for (Position position : replacementPositions(room, current.getPosition(), prototype)) {
                Furniture moved = copy(prototype, position, rotation);
                if (valid(room, base, replace(base, index, moved))) return Optional.of(moved);
            }
        }
        return Optional.empty();
    }

    private List<Double> replacementRotations(double currentRotation) {
        double quarterTurn = FeedbackPlacementPolicy.normalize(currentRotation + 90);
        return quarterTurn == currentRotation ? List.of(currentRotation) : List.of(currentRotation, quarterTurn);
    }

    private List<Position> replacementPositions(Room room, Position currentPosition, Furniture prototype) {
        FurnitureBoundary.Footprint footprint = FurnitureBoundary.footprint(prototype);
        double halfWidth = footprint.effectiveWidth() / 2.0;
        double halfDepth = footprint.effectiveDepth() / 2.0;
        double currentX = currentPosition.getX();
        double currentZ = currentPosition.getZ();
        List<Position> positions = List.of(
                currentPosition,
                new Position(currentX - 0.25, currentZ), new Position(currentX + 0.25, currentZ),
                new Position(currentX, currentZ - 0.25), new Position(currentX, currentZ + 0.25),
                new Position(halfWidth, currentZ), new Position(room.getWidth() - halfWidth, currentZ),
                new Position(currentX, halfDepth), new Position(currentX, room.getDepth() - halfDepth)
        );
        return distinctPositions(positions.stream()
                .map(position -> FurnitureBoundary.clamp(room, position, footprint).orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList());
    }

    private Furniture furnitureFromProduct(String id, MockProduct product, Position position,
                                           double rotation, FurnitureStatus status) {
        return new Furniture(id, product.getType(), product.getName(), product.getWidth(), product.getDepth(),
                product.getHeight(), position, rotation, status, product.getProductId(),
                product.getStyleTags(), product.getVariantId());
    }

    private String generateFurnitureId(String type, List<Furniture> furniture) {
        String prefix = type.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        if (prefix.isBlank()) prefix = "furniture";
        Set<String> existingIds = furniture.stream().map(Furniture::getId).collect(java.util.stream.Collectors.toSet());
        int sequence = 1;
        while (existingIds.contains(prefix + "-feedback-" + sequence)) sequence++;
        return prefix + "-feedback-" + sequence;
    }

    private int score(AgentContext context, List<Furniture> furniture, ValidationResult validation) {
        return context == null ? 0 : scoreService.calculate(context, furniture, validation).getTotalScore();
    }

    private double distanceFromReference(Position position, Furniture reference, Room room) {
        Position origin = reference == null
                ? new Position(room.getWidth() / 2.0, room.getDepth() / 2.0)
                : reference.getPosition();
        return distance(origin, position);
    }

    private double distance(Position first, Position second) {
        double x = first.getX() - second.getX();
        double z = first.getZ() - second.getZ();
        return Math.sqrt(x * x + z * z);
    }

    private String summaryFor(String reason) {
        return switch (reason) {
            case "NO_LARGER_PRODUCT_AVAILABLE" -> "현재 가구가 사용 가능한 제품 중 가장 넓어 기존 배치를 유지했습니다.";
            case "NO_SMALLER_PRODUCT_AVAILABLE" -> "현재 가구가 사용 가능한 제품 중 가장 작아 기존 배치를 유지했습니다.";
            case "NO_VALID_PRODUCT_PLACEMENT" -> "수납형 책상을 배치할 수 있는 유효한 위치를 찾지 못했습니다.";
            case "NO_MATCHING_PRODUCT", "NO_RENDERABLE_PRODUCT" -> "안전하게 렌더링할 수 있는 조건 일치 제품을 찾지 못했습니다.";
            case "CURRENT_PRODUCT_ALREADY_MATCHES" -> "현재 제품이 이미 요청 조건을 만족해 기존 배치를 유지했습니다.";
            case "INVALID_REPLACE_CONSTRAINTS" -> "제품 교체 조건을 안전하게 해석하지 못해 기존 배치를 유지했습니다.";
            case "NO_VALID_ADD_PLACEMENT" -> "추가 가구를 놓을 유효한 위치를 찾지 못했습니다.";
            case "NO_VALID_SWAP_PLACEMENT" -> "교체 가구를 놓을 유효한 위치를 찾지 못했습니다.";
            case "NO_VALID_BOUNDARY_PLACEMENT" -> "가구 전체가 방 안에 들어오는 위치를 찾지 못했습니다.";
            case "ROTATION_OUT_OF_BOUNDS" -> "회전 후 가구가 방 경계를 벗어나 기존 배치를 유지했습니다.";
            case "AMBIGUOUS_TARGET", "AMBIGUOUS_REFERENCE_TARGET", "NEEDS_CLARIFICATION" ->
                    "변경할 가구를 하나로 특정할 수 없어 추가 설명이 필요합니다.";
            case "TARGET_NOT_FOUND", "REFERENCE_TARGET_NOT_FOUND" -> "요청한 가구를 현재 배치에서 찾을 수 없습니다.";
            case "UNSUPPORTED_LOCATION_HINT", "UNSUPPORTED_REFERENCE_LOCATION_HINT" ->
                    "현재 방 정보로 위치 표현을 안전하게 판별할 수 없습니다.";
            case "DEPENDENCY_NOT_APPLIED" -> "앞선 변경이 적용되지 않아 이어지는 변경을 실행하지 않았습니다.";
            case "UNSUPPORTED_OPERATION" -> "이번 피드백에서는 요청한 작업을 지원하지 않습니다.";
            default -> "요청을 안전하게 적용할 수 없어 기존 배치를 유지했습니다.";
        };
    }

    private FeedbackExecution noChange(List<Furniture> original, FeedbackPlan plan, List<String> requested,
                                       String reason, String summary,
                                       List<FeedbackOperationExecution> operationResults) {
        return new FeedbackExecution(original,
                new FeedbackResult(false, plan.source(), plan.fallbackUsed(), summary, requested, List.of(), reason),
                operationResults);
    }

    private boolean active(Furniture furniture) {
        return furniture.getStatus() != FurnitureStatus.DELETED;
    }

    private boolean valid(Room room, List<Furniture> baseline, List<Furniture> candidate) {
        return hardValid(validationService.validateChange(room, baseline, candidate));
    }

    private boolean hardValid(ValidationResult result) {
        return result.isCollisionFree() && result.isBoundaryValid() && result.isDoorClearance()
                && result.isWindowClearance() && result.isPathSecured();
    }

    private List<Furniture> replace(List<Furniture> furniture, int index, Furniture updated) {
        List<Furniture> copy = new ArrayList<>(furniture);
        copy.set(index, updated);
        return copy;
    }

    private List<Furniture> appended(List<Furniture> furniture, Furniture added) {
        List<Furniture> copy = new ArrayList<>(furniture);
        copy.add(added);
        return copy;
    }

    private Furniture copy(Furniture item, Position position, double rotation) {
        return new Furniture(item.getId(), item.getType(), item.getLabel(), item.getWidth(), item.getDepth(), item.getHeight(),
                position, rotation, item.getStatus(), item.getProductId(), item.getStyleTags(), item.getVariantId());
    }

    private boolean samePosition(Furniture first, Furniture second) {
        return samePosition(first.getPosition(), second.getPosition());
    }

    private boolean samePosition(Position first, Position second) {
        return Math.abs(first.getX() - second.getX()) <= METRIC_TOLERANCE
                && Math.abs(first.getZ() - second.getZ()) <= METRIC_TOLERANCE;
    }

    private List<Position> distinctPositions(List<Position> positions) {
        List<Position> distinct = new ArrayList<>();
        for (Position position : positions) {
            boolean duplicate = distinct.stream().anyMatch(existing -> samePosition(existing, position));
            if (!duplicate) distinct.add(position);
        }
        return List.copyOf(distinct);
    }

    private record CandidateEvaluation(
            List<Furniture> snapshot,
            String affectedFurnitureId,
            String productId,
            int score,
            double movementDistance,
            int order
    ) {
    }

    private record OperationAttempt(
            boolean applied,
            List<Furniture> snapshot,
            String affectedFurnitureId,
            String reasonCode
    ) {
        private static OperationAttempt applied(List<Furniture> snapshot, String affectedFurnitureId) {
            return new OperationAttempt(true, List.copyOf(snapshot), affectedFurnitureId, null);
        }

        private static OperationAttempt failed(String reasonCode) {
            return new OperationAttempt(false, List.of(), null, reasonCode);
        }
    }

    private enum TargetResolutionStatus {
        RESOLVED,
        NOT_FOUND,
        AMBIGUOUS,
        UNSUPPORTED_SELECTOR
    }

    private record TargetResolution(TargetResolutionStatus status, int index) {
        private static TargetResolution resolved(int index) {
            return new TargetResolution(TargetResolutionStatus.RESOLVED, index);
        }

        private static TargetResolution notFound() {
            return new TargetResolution(TargetResolutionStatus.NOT_FOUND, -1);
        }

        private static TargetResolution ambiguous() {
            return new TargetResolution(TargetResolutionStatus.AMBIGUOUS, -1);
        }

        private static TargetResolution unsupportedSelector() {
            return new TargetResolution(TargetResolutionStatus.UNSUPPORTED_SELECTOR, -1);
        }
    }
}
