package com.roomfit.placement;

import com.roomfit.agent.domain.AgentContext;
import com.roomfit.agent.repository.AgentContextRepository;
import com.roomfit.common.CustomException;
import com.roomfit.common.ErrorCode;
import com.roomfit.placement.dto.*;
import com.roomfit.product.catalog.GeneratedFurnitureCatalog;
import com.roomfit.product.domain.MockProduct;
import com.roomfit.product.repository.MockProductRepository;
import com.roomfit.room.Furniture;
import com.roomfit.room.FurnitureBoundary;
import com.roomfit.room.FurnitureStatus;
import com.roomfit.room.Opening;
import com.roomfit.room.Position;
import com.roomfit.room.Room;
import com.roomfit.room.RoomAccessService;
import com.roomfit.room.RoomRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class LayoutService {

    private final LayoutRepository layoutRepository;
    private final AgentContextRepository agentContextRepository;
    private final RoomRepository roomRepository;
    private final RoomAccessService roomAccessService;
    private final PlacementService placementService; // 규칙기반/AI기반 구현체를 DI로 교체 가능
    private final ValidationService validationService;
    private final FeedbackPlanInterpreter feedbackPlanInterpreter;
    private final DeterministicFeedbackExecutor feedbackExecutor;
    private final ScoreService scoreService;
    private final FurnitureAdditionPolicy furnitureAdditionPolicy;
    private final FurnitureDomainPolicy furnitureDomainPolicy;
    private final MockProductRepository mockProductRepository;

    public LayoutService(LayoutRepository layoutRepository,
                          AgentContextRepository agentContextRepository,
                          RoomRepository roomRepository,
                          RoomAccessService roomAccessService,
                          PlacementService placementService,
                          ValidationService validationService,
                          FeedbackPlanInterpreter feedbackPlanInterpreter,
                          DeterministicFeedbackExecutor feedbackExecutor,
                          ScoreService scoreService,
                          FurnitureAdditionPolicy furnitureAdditionPolicy,
                          FurnitureDomainPolicy furnitureDomainPolicy,
                          MockProductRepository mockProductRepository) {
        this.layoutRepository = layoutRepository;
        this.agentContextRepository = agentContextRepository;
        this.roomRepository = roomRepository;
        this.roomAccessService = roomAccessService;
        this.placementService = placementService;
        this.validationService = validationService;
        this.feedbackPlanInterpreter = feedbackPlanInterpreter;
        this.feedbackExecutor = feedbackExecutor;
        this.scoreService = scoreService;
        this.furnitureAdditionPolicy = furnitureAdditionPolicy;
        this.furnitureDomainPolicy = furnitureDomainPolicy;
        this.mockProductRepository = mockProductRepository;
    }

    /**
     * AgentContext 없이 방의 기존 가구만 담은 빈 Layout을 만든다 — AI 추천을 거치지
     * 않고 카탈로그에서 가구를 직접 드래그해 배치하는 흐름의 시작점.
     */
    @Transactional
    public LayoutResponse createBlankLayout(CreateLayoutRequest request) {
        if (request == null || request.getRoomId() == null) {
            throw new CustomException(ErrorCode.INVALID_REQUEST_BODY);
        }
        Room room = roomAccessService.findWritableRoom(request.getRoomId());
        List<Furniture> furniture = deepCopyFurniture(room.getFurniture());
        ValidationResult validationResult = validationService.validate(room, furniture);
        ScoreSummary scoreSummary = scoreService.calculate(null, furniture, validationResult);

        Layout layout = new Layout(room.getId(), null, furniture);
        layoutRepository.save(layout);
        return LayoutResponse.ofSnapshot(layout, scoreSummary, validationResult);
    }

    /**
     * 카탈로그 제품을 클라이언트가 고른 좌표/회전 그대로 Layout에 추가한다. AI 배치와
     * 달리 서버는 좌표를 안전한 위치로 옮기거나 clamp하지 않는다 — 방 밖이거나 다른
     * 가구와 겹쳐도 추가 자체는 허용하고, 결과 validationResult.issues로 알려준다
     * (프론트가 3D 뷰에 빨강/주황으로 표시). 최종 확정(confirm)은 여전히
     * hard-valid(ERROR 이슈 없음)를 요구한다.
     */
    @Transactional
    public LayoutResponse addFurnitureDirect(Long layoutId, AddFurnitureRequest request) {
        Layout layout = findLayoutOrThrow(layoutId);
        roomAccessService.findWritableRoom(layout.getRoomId());
        if (layout.isConfirmed()) {
            throw new CustomException(ErrorCode.ALREADY_CONFIRMED);
        }
        if (request == null || request.getProductId() == null || request.getPosition() == null) {
            throw new CustomException(ErrorCode.INVALID_REQUEST_BODY);
        }
        Room room = roomAccessService.findWritableRoom(layout.getRoomId());
        MockProduct product = mockProductRepository.findById(request.getProductId())
                .orElseThrow(() -> new CustomException(ErrorCode.PRODUCT_NOT_FOUND));

        List<Furniture> updated = deepCopyFurniture(layout.getFurniture());
        String furnitureId = generateDirectFurnitureId(product.getType(), updated);
        Position position = new Position(request.getPosition().getX(), request.getPosition().getZ());
        Furniture added = new Furniture(furnitureId, product.getType(), product.getName(),
                product.getWidth(), product.getDepth(), product.getHeight(), position, request.getRotation(),
                FurnitureStatus.USER_MODIFIED, product.getProductId(), product.getStyleTags(),
                product.getVariantId());
        updated.add(added);

        furnitureDomainPolicy.validateFinalState(updated);
        ValidationResult validationResult = validationService.validate(room, updated);
        ScoreSummary scoreSummary = scoreService.calculate(null, updated, validationResult);
        layout.setFurniture(updated);
        layoutRepository.save(layout);
        return LayoutResponse.ofUpdate(layout, RecommendationStatus.SUCCESS, scoreSummary, validationResult);
    }

    private String generateDirectFurnitureId(String type, List<Furniture> furniture) {
        String prefix = (type == null ? "furniture" : type).toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        if (prefix.isBlank()) prefix = "furniture";
        Set<String> existingIds = furniture.stream().map(Furniture::getId).collect(Collectors.toSet());
        int sequence = 1;
        while (existingIds.contains(prefix + "-" + sequence)) sequence++;
        return prefix + "-" + sequence;
    }

    public LayoutResponse recommend(RecommendRequest request) {
        AgentContext context = agentContextRepository.findById(request.getContextId())
                .orElseThrow(() -> new CustomException(ErrorCode.CONTEXT_NOT_FOUND));
        Room room = roomAccessService.findWritableRoom(request.getRoomId());

        if (!room.getId().equals(context.getRoomId())) {
            throw new CustomException(ErrorCode.ROOM_CONTEXT_MISMATCH);
        }

        // discardExisting=true: recommend as if the room had no furniture yet,
        // without ever mutating/saving the persisted Room entity. `placementRoom`
        // is a detached copy used only as read-only input to the placement
        // engine (which only reads geometry/furniture off it) — the real `room`
        // (and its confirmed furniture) is untouched either way.
        Room placementRoom = request.isDiscardExisting() ? withoutFurniture(room) : room;
        List<Furniture> baselineFurniture = request.isDiscardExisting() ? List.of() : room.getFurniture();

        PlacementResult placementResult;
        try {
            placementResult = placementService.recommend(context, placementRoom);
        } catch (Exception e) {
            // TODO: AI Agent 호출 실패 시 규칙 기반 fallback 로직으로 재시도.
            // 지금은 스켈레톤이라 바로 예외 처리.
            throw new CustomException(ErrorCode.RECOMMENDATION_FAILED);
        }

        furnitureDomainPolicy.validateFinalState(placementResult.getRecommendedFurniture());
        ValidationResult changeValidationResult = validationService.validateChange(
                room, baselineFurniture, placementResult.getRecommendedFurniture());
        ValidationResult validationResult = validationService.validate(
                room, placementResult.getRecommendedFurniture());
        // A PlacementService may use a provisional summary while it constructs a
        // candidate. The API must always expose the score calculated from this
        // exact validation result, including normal FAILED outcomes.
        ScoreSummary scoreSummary = scoreService.calculate(context, placementResult.getRecommendedFurniture(), validationResult);
        PlacementResult scoredPlacementResult = new PlacementResult(placementResult.getStatus(),
                placementResult.getRecommendedFurniture(), scoreSummary,
                placementResult.getRequestedFurnitureCount(), placementResult.getPlacedFurnitureCount(),
                placementResult.getUnplacedFurniture(), placementResult.getRecommendationStatus(),
                placementResult.getWarningCode(), placementResult.getMessage());
        if (placementResult.getRecommendationStatus() == RecommendationExecutionStatus.FAILED) {
            // A normal lack of physical space is a valid recommendation outcome, not a server error.
            // Do not persist an empty or invalid recommendation snapshot.
            return LayoutResponse.ofRecommendationFailure(room.getId(), scoredPlacementResult, validationResult);
        }
        // Legacy scripted sample layouts intentionally preserve their historical
        // composition (including decorative rug/table overlap) and do not carry
        // request-instance metadata. New deterministic recommendations must be
        // hard-valid before they are persisted.
        if (placementResult.getRequestedFurnitureCount() > 0 && !isHardValid(changeValidationResult)) {
            throw new CustomException(ErrorCode.RECOMMENDATION_FAILED);
        }
        Layout layout = new Layout(room.getId(), context.getId(), placementResult.getRecommendedFurniture());
        layoutRepository.save(layout);

        return LayoutResponse.ofRecommendation(layout, scoredPlacementResult, validationResult);
    }

    private boolean isHardValid(ValidationResult result) {
        return result.isCollisionFree() && result.isBoundaryValid() && result.isDoorClearance()
                && result.isWindowClearance() && result.isPathSecured();
    }

    /**
     * A detached copy of {@code room} with an empty furniture list, for
     * discardExisting recommendations. Never persisted — {@link RoomRepository}
     * is never called with this instance, and the caller must keep using the
     * original {@code room} for anything that touches the database.
     */
    private Room withoutFurniture(Room room) {
        return new Room(room.getId(), room.getName(), room.getWidth(), room.getDepth(), room.getHeight(),
                room.getUnit(), room.getWalls(), room.getOpenings(), List.of(),
                room.getSource(), room.getCreatedAt(), room.getClientScope());
    }

    public LayoutResponse getLayout(Long layoutId) {
        Layout layout = findLayoutOrThrow(layoutId);
        roomAccessService.findReadableRoom(layout.getRoomId());
        return snapshotResponse(layout);
    }

    public LayoutResponse getLatestConfirmedLayout(Long roomId) {
        roomAccessService.findReadableRoom(roomId);
        Layout layout = layoutRepository.findFirstByRoomIdAndConfirmedTrueOrderByConfirmedAtDesc(roomId)
                .orElseThrow(() -> new CustomException(ErrorCode.LAYOUT_NOT_FOUND));
        return snapshotResponse(layout);
    }

    public LayoutResponse createDraft(Long sourceLayoutId) {
        Layout source = findLayoutOrThrow(sourceLayoutId);
        roomAccessService.findWritableRoom(source.getRoomId());
        if (!source.isConfirmed()) {
            throw new CustomException(ErrorCode.LAYOUT_NOT_CONFIRMED);
        }

        Room room = roomAccessService.findWritableRoom(source.getRoomId());
        List<Furniture> furniture = deepCopyFurniture(source.getFurniture());
        ValidationResult changeValidationResult = validationService.validateChange(
                room, room.getFurniture(), furniture);
        if (!changeValidationResult.isBoundaryValid()) {
            throw new CustomException(ErrorCode.INVALID_FURNITURE_POSITION);
        }
        ValidationResult validationResult = validationService.validate(room, furniture);

        Layout draft = new Layout(source.getRoomId(), source.getContextId(), furniture, source.getId());
        layoutRepository.save(draft);
        AgentContext context = agentContextRepository.findById(source.getContextId())
                .orElseThrow(() -> new CustomException(ErrorCode.CONTEXT_NOT_FOUND));
        ScoreSummary scoreSummary = scoreService.calculate(context, furniture, validationResult);
        return LayoutResponse.ofSnapshot(draft, scoreSummary, validationResult);
    }

    public ValidationResult validateOnly(ValidateRequest request) {
        Layout layout = findLayoutOrThrow(request.getLayoutId());
        Room room = roomAccessService.findReadableRoom(layout.getRoomId());

        List<Furniture> mergedFurniture = applyPositionOverrides(layout.getFurniture(), request.getFurniture(), room);
        furnitureDomainPolicy.validateFinalState(mergedFurniture);
        return validationService.validate(room, mergedFurniture);
    }

    @Transactional
    public LayoutResponse updateLayout(Long layoutId, LayoutUpdateRequest request) {
        Layout layout = findLayoutOrThrow(layoutId);
        roomAccessService.findWritableRoom(layout.getRoomId());
        if (layout.isConfirmed()) {
            throw new CustomException(ErrorCode.ALREADY_CONFIRMED);
        }
        Room room = roomAccessService.findWritableRoom(layout.getRoomId());
        AgentContext context = findContextOrNull(layout.getContextId());

        List<Furniture> updated = applyPositionOverrides(layout.getFurniture(), request.getFurniture(), room);
        furnitureDomainPolicy.validateFinalState(updated);
        ValidationResult validationResult = validationService.validate(room, updated);
        ScoreSummary scoreSummary = scoreService.calculate(context, updated, validationResult);
        layout.setFurniture(updated);
        layoutRepository.save(layout);
        return LayoutResponse.ofUpdate(layout, RecommendationStatus.SUCCESS, scoreSummary, validationResult);
    }

    @Transactional
    public LayoutResponse addFurniture(Long layoutId, DraftFurnitureAdditionRequest request) {
        Layout layout = findLayoutOrThrow(layoutId);
        roomAccessService.findWritableRoom(layout.getRoomId());
        if (layout.isConfirmed()) {
            throw new CustomException(ErrorCode.ALREADY_CONFIRMED);
        }
        if (request == null || request.getContextId() == null) {
            throw new CustomException(ErrorCode.INVALID_REQUEST_BODY);
        }

        Room room = roomAccessService.findWritableRoom(layout.getRoomId());
        AgentContext context = agentContextRepository.findById(request.getContextId())
                .orElseThrow(() -> new CustomException(ErrorCode.CONTEXT_NOT_FOUND));
        if (!room.getId().equals(context.getRoomId())) {
            throw new CustomException(ErrorCode.ROOM_CONTEXT_MISMATCH);
        }

        List<String> requestedTypes = context.getRequiredItems().stream()
                .map(GeneratedFurnitureCatalog.get()::normalizeType)
                .filter(type -> type != null && !type.isBlank())
                .toList();

        furnitureAdditionPolicy.validate(layout.getFurniture(), requestedTypes);

        List<AdditionRequest> additions = new ArrayList<>();
        for (int index = 0; index < requestedTypes.size(); index++) {
            additions.add(new AdditionRequest(index, requestedTypes.get(index)));
        }
        additions.sort(Comparator.comparingInt(addition -> additionDependencyRank(addition.type())));

        List<Furniture> baseline = deepCopyFurniture(layout.getFurniture());
        List<Furniture> updated = deepCopyFurniture(baseline);
        List<UnplacedFurniture> unplaced = new ArrayList<>();
        int placedCount = 0;
        for (AdditionRequest addition : additions) {
            if (activeFurnitureCount(updated) >= FurnitureAdditionPolicy.MAX_ACTIVE_FURNITURE) {
                unplaced.add(unplaced(addition, "ACTIVE_FURNITURE_LIMIT"));
                continue;
            }
            FeedbackOperation operation = additionOperation(addition);
            FeedbackPlan plan = new FeedbackPlan(
                    "2.0",
                    FeedbackRequestKind.DIRECT,
                    List.of(operation),
                    List.of(),
                    null,
                    "Add Furniture selection",
                    FeedbackSource.RULE_BASED,
                    false
            );
            FeedbackExecution execution = feedbackExecutor.execute(plan, room, updated, context,
                    FurnitureAdditionPolicy.MAX_NEW_ADDITIONS);
            FeedbackOperationExecution operationResult = execution.operationResults().stream().findFirst().orElse(null);
            if (operationResult != null && operationResult.status() == FeedbackOperationExecution.Status.APPLIED) {
                updated = deepCopyFurniture(execution.furniture());
                placedCount++;
            } else {
                String reasonCode = operationResult == null || operationResult.reasonCode() == null
                        ? "NO_VALID_ADD_PLACEMENT" : operationResult.reasonCode();
                unplaced.add(unplaced(addition, reasonCode));
            }
        }

        furnitureDomainPolicy.validateFinalState(updated);
        ValidationResult validationResult = validationService.validate(room, updated);
        ScoreSummary scoreSummary = scoreService.calculate(context, updated, validationResult);
        if (placedCount > 0) {
            layout.setContextId(context.getId());
            layout.setFurniture(updated);
            layoutRepository.save(layout);
        }
        RecommendationExecutionStatus outcome = unplaced.isEmpty()
                ? RecommendationExecutionStatus.SUCCESS
                : placedCount == 0 ? RecommendationExecutionStatus.FAILED
                : RecommendationExecutionStatus.PARTIAL_SUCCESS;
        String message = switch (outcome) {
            case SUCCESS -> "선택한 가구를 모두 안전하게 배치했습니다.";
            case PARTIAL_SUCCESS -> "선택한 가구 중 배치 가능한 항목만 안전하게 추가했습니다.";
            case FAILED -> "선택한 가구를 안전하게 배치할 공간을 찾지 못했습니다.";
        };
        PlacementResult placementResult = new PlacementResult(
                RecommendationStatus.SUCCESS, updated, scoreSummary,
                requestedTypes.size(), placedCount, unplaced, outcome,
                unplaced.isEmpty() ? null : "INSUFFICIENT_ROOM_SPACE", message);
        return LayoutResponse.ofRecommendation(layout, placementResult, validationResult);
    }

    private FeedbackOperation additionOperation(AdditionRequest addition) {
        return new FeedbackOperation(
                "add-selection-" + (addition.requestIndex() + 1),
                FeedbackOperationType.ADD_FURNITURE,
                new FeedbackTargetSelector("", addition.type(), ""),
                null,
                new FeedbackPlacement(FeedbackRelation.NEAR_WALL, null, null, null),
                null,
                new FeedbackProductRequirements(addition.type(), FeedbackSizePreference.ANY, false, List.of()),
                null,
                List.of());
    }

    private int additionDependencyRank(String type) {
        return switch (GeneratedFurnitureCatalog.get().normalizeType(type)) {
            case "desk", "media_console" -> 0;
            case "monitor", "tv" -> 2;
            default -> 1;
        };
    }

    private long activeFurnitureCount(List<Furniture> furniture) {
        return furniture.stream().filter(FurnitureAdditionPolicy::active).count();
    }

    private UnplacedFurniture unplaced(AdditionRequest addition, String reasonCode) {
        String message = switch (reasonCode) {
            case "ACTIVE_FURNITURE_LIMIT" -> "현재 배치의 가구 수 제한 때문에 추가하지 못했습니다.";
            case "NO_RENDERABLE_PRODUCT" -> "배치 가능한 제품 정보를 찾지 못했습니다.";
            case "NO_VALID_BOUNDARY_PLACEMENT" -> "가구 전체가 방 안에 들어오는 위치를 찾지 못했습니다.";
            default -> "기존 가구를 침범하지 않는 안전한 위치를 찾지 못했습니다.";
        };
        return new UnplacedFurniture(addition.requestIndex(), addition.type(), null, null, reasonCode, message);
    }

    private record AdditionRequest(int requestIndex, String type) {
    }

    @Transactional
    public ConfirmResponse confirmLayout(Long layoutId) {
        Layout layout = findLayoutOrThrow(layoutId);
        roomAccessService.findWritableRoom(layout.getRoomId());
        furnitureDomainPolicy.validateFinalState(layout.getFurniture());
        if (layout.isConfirmed()) {
            throw new CustomException(ErrorCode.ALREADY_CONFIRMED);
        }
        Room room = roomAccessService.findWritableRoom(layout.getRoomId());
        if (!isHardValid(validationService.validateChange(room, room.getFurniture(), layout.getFurniture()))) {
            throw new CustomException(ErrorCode.INVALID_FURNITURE_POSITION);
        }
        layout.confirm();
        layoutRepository.save(layout);

        // 확정된 배치를 Room에도 되반영한다 — 이게 없으면 GET /api/rooms/{roomId}
        // (및 목록 재조회)가 여전히 확정 이전 가구 배치를 보여준다. Layout은
        // Room과 독립된 값 복사 스냅샷이라(Layout.java 참고) 여기서 명시적으로
        // 동기화해야 한다.
        room.setFurniture(layout.getFurniture());
        roomRepository.save(room);

        return ConfirmResponse.from(layout);
    }

    @Transactional
    public FeedbackResponse feedback(FeedbackRequest request) {
        Layout baseLayout = findLayoutOrThrow(request.getLayoutId());
        roomAccessService.findWritableRoom(baseLayout.getRoomId());
        AgentContext context = agentContextRepository.findById(baseLayout.getContextId())
                .orElseThrow(() -> new CustomException(ErrorCode.CONTEXT_NOT_FOUND));
        Room room = roomAccessService.findWritableRoom(baseLayout.getRoomId());

        FeedbackPlan plan = feedbackPlanInterpreter.interpret(request.getFeedback(), room, baseLayout.getFurniture(), context,
                request.getSelectedFurnitureId());
        FeedbackExecution execution = feedbackExecutor.execute(plan, room, baseLayout.getFurniture(), context);
        furnitureDomainPolicy.validateFinalState(execution.furniture());
        ValidationResult validationResult = validationService.validate(room, execution.furniture());
        ScoreSummary scoreSummary = scoreService.calculate(context, execution.furniture(), validationResult);
        Layout responseLayout = baseLayout;
        if (execution.result().applied()) {
            responseLayout = layoutRepository.findBySourceLayoutIdOrderByIdDesc(baseLayout.getId()).stream()
                    .filter(layout -> sameFurnitureSnapshot(layout.getFurniture(), execution.furniture()))
                    .findFirst()
                    .orElseGet(() -> layoutRepository.save(new Layout(baseLayout.getRoomId(), baseLayout.getContextId(),
                            deepCopyFurniture(execution.furniture()), baseLayout.getId())));
        }

        return FeedbackResponse.of(responseLayout, RecommendationStatus.SUCCESS,
                scoreSummary, validationResult, interpretedPlan(plan), execution.result(),
                feedbackStatus(plan, execution), operationResults(plan, execution, baseLayout.getFurniture()),
                clarifications(plan, execution, baseLayout.getFurniture(), room));
    }

    /** Reuse an identical derived snapshot so a transport retry remains idempotent. */
    private boolean sameFurnitureSnapshot(List<Furniture> first, List<Furniture> second) {
        if (first.size() != second.size()) return false;
        for (int index = 0; index < first.size(); index++) {
            Furniture left = first.get(index);
            Furniture right = second.get(index);
            if (!Objects.equals(left.getId(), right.getId())
                    || !Objects.equals(left.getType(), right.getType())
                    || !Objects.equals(left.getLabel(), right.getLabel())
                    || Double.compare(left.getWidth(), right.getWidth()) != 0
                    || Double.compare(left.getDepth(), right.getDepth()) != 0
                    || Double.compare(left.getHeight(), right.getHeight()) != 0
                    || Double.compare(left.getPosition().getX(), right.getPosition().getX()) != 0
                    || Double.compare(left.getPosition().getZ(), right.getPosition().getZ()) != 0
                    || Double.compare(left.getRotation(), right.getRotation()) != 0
                    || left.getStatus() != right.getStatus()
                    || !Objects.equals(left.getProductId(), right.getProductId())
                    || !Objects.equals(left.getVariantId(), right.getVariantId())
                    || !Objects.equals(left.getStyleTags(), right.getStyleTags())) {
                return false;
            }
        }
        return true;
    }

    private FeedbackStatus feedbackStatus(FeedbackPlan plan, FeedbackExecution execution) {
        if (execution.result().applied()) {
            return FeedbackStatus.SUCCESS;
        }
        if (plan.needsClarification() || execution.operationResults().stream()
                .anyMatch(result -> needsClarification(result.reasonCode()))) {
            return FeedbackStatus.NEEDS_CLARIFICATION;
        }
        return FeedbackStatus.FAILED;
    }

    /**
     * The executor exposes compact internal execution data. Convert it here so
     * the HTTP response remains additive and is ordered exactly as the Plan.
     */
    private List<FeedbackOperationResult> operationResults(FeedbackPlan plan, FeedbackExecution execution,
                                                            List<Furniture> originalFurniture) {
        Map<String, FeedbackOperationExecution> executions = execution.operationResults().stream()
                .collect(Collectors.toMap(FeedbackOperationExecution::operationId, result -> result,
                        (first, ignored) -> first, LinkedHashMap::new));
        return plan.operations().stream().map(operation -> {
            FeedbackOperationExecution executionResult = executions.get(operation.operationId());
            if (executionResult == null) {
                return new FeedbackOperationResult(operation.operationId(), operation.type(),
                        FeedbackOperationResult.Status.SKIPPED_DEPENDENCY, "DEPENDENCY_NOT_APPLIED",
                        operationMessage(operation.type(), FeedbackOperationResult.Status.SKIPPED_DEPENDENCY,
                                "DEPENDENCY_NOT_APPLIED"),
                        targetFurnitureId(operation, null), null, null, null);
            }
            FeedbackOperationResult.Status status = publicOperationStatus(executionResult);
            String affectedId = executionResult.affectedFurnitureId();
            Furniture resultFurniture = findFurniture(execution.furniture(), affectedId).orElse(null);
            if (resultFurniture == null && status == FeedbackOperationResult.Status.APPLIED
                    && operation.type() == FeedbackOperationType.REMOVE_FURNITURE) {
                resultFurniture = findFurniture(originalFurniture, affectedId).orElse(null);
            }
            String targetId = targetFurnitureId(operation, affectedId);
            return new FeedbackOperationResult(operation.operationId(), operation.type(), status,
                    executionResult.reasonCode(),
                    operationMessage(operation.type(), status, executionResult.reasonCode()),
                    targetId, affectedId,
                    resultFurniture == null ? null : resultFurniture.getProductId(),
                    resultFurniture == null ? null : resultFurniture.getVariantId());
        }).toList();
    }

    private List<FeedbackClarificationResponse> clarifications(FeedbackPlan plan, FeedbackExecution execution,
                                                                 List<Furniture> originalFurniture, Room room) {
        List<FeedbackClarificationResponse> result = new ArrayList<>();
        if (plan.needsClarification()) {
            FeedbackClarification clarification = plan.clarification();
            String type = clarification == null ? "" : clarification.targetFurnitureType();
            List<FeedbackClarificationResponse.Candidate> candidates = clarificationCandidates(type, "", originalFurniture, room);
            boolean ambiguous = candidates.size() > 1;
            result.add(new FeedbackClarificationResponse(ambiguous ? "AMBIGUOUS_TARGET" : "NEEDS_CLARIFICATION",
                    clarificationQuestion(type, false), null, ambiguous ? "targetFurnitureId" : null,
                    ambiguous ? candidates : List.of()));
        }
        Map<String, FeedbackOperation> operations = plan.operations().stream()
                .collect(Collectors.toMap(FeedbackOperation::operationId, operation -> operation,
                        (first, ignored) -> first, LinkedHashMap::new));
        for (FeedbackOperationExecution executionResult : execution.operationResults()) {
            if (!needsClarification(executionResult.reasonCode())) continue;
            FeedbackOperation operation = operations.get(executionResult.operationId());
            if (operation == null) continue;
            boolean reference = executionResult.reasonCode().contains("REFERENCE");
            FeedbackTargetSelector target = reference ? operation.referenceTarget() : operation.target();
            String type = target == null ? "" : target.furnitureType();
            String keyword = target == null ? "" : target.labelKeyword();
            boolean ambiguous = "AMBIGUOUS_TARGET".equals(executionResult.reasonCode())
                    || "AMBIGUOUS_REFERENCE_TARGET".equals(executionResult.reasonCode());
            result.add(new FeedbackClarificationResponse(executionResult.reasonCode(),
                    clarificationQuestion(type, reference), operation.operationId(),
                    ambiguous ? reference ? "referenceTargetFurnitureId" : "targetFurnitureId" : null,
                    ambiguous ? clarificationCandidates(type, keyword, originalFurniture, room) : List.of()));
        }
        return List.copyOf(result);
    }

    private FeedbackOperationResult.Status publicOperationStatus(FeedbackOperationExecution result) {
        if (result.status() == FeedbackOperationExecution.Status.APPLIED) {
            return FeedbackOperationResult.Status.APPLIED;
        }
        if (result.status() == FeedbackOperationExecution.Status.SKIPPED) {
            return FeedbackOperationResult.Status.SKIPPED_DEPENDENCY;
        }
        return needsClarification(result.reasonCode())
                ? FeedbackOperationResult.Status.NEEDS_CLARIFICATION
                : FeedbackOperationResult.Status.FAILED;
    }

    private boolean needsClarification(String reasonCode) {
        if (reasonCode == null) return false;
        return Set.of("NEEDS_CLARIFICATION", "AMBIGUOUS_TARGET", "AMBIGUOUS_REFERENCE_TARGET",
                        "UNSUPPORTED_LOCATION_HINT", "UNSUPPORTED_REFERENCE_LOCATION_HINT")
                .contains(reasonCode);
    }

    private String targetFurnitureId(FeedbackOperation operation, String affectedId) {
        if (operation.target() != null && !operation.target().furnitureId().isBlank()) {
            return operation.target().furnitureId();
        }
        return operation.type() == FeedbackOperationType.ADD_FURNITURE ? null : affectedId;
    }

    private Optional<Furniture> findFurniture(List<Furniture> furniture, String furnitureId) {
        if (furnitureId == null) return Optional.empty();
        return furniture.stream().filter(item -> furnitureId.equals(item.getId())).findFirst();
    }

    private List<FeedbackClarificationResponse.Candidate> clarificationCandidates(String type, String labelKeyword,
                                                                                    List<Furniture> furniture, Room room) {
        if (type == null || type.isBlank()) return List.of();
        String normalizedKeyword = labelKeyword == null ? "" : labelKeyword.toLowerCase(java.util.Locale.ROOT);
        List<Furniture> matches = furniture.stream()
                .filter(item -> item.getStatus() != FurnitureStatus.DELETED)
                .filter(item -> GeneratedFurnitureCatalog.get().sameType(type, item.getType()))
                .filter(item -> normalizedKeyword.isBlank() || (item.getLabel() != null
                        && item.getLabel().toLowerCase(java.util.Locale.ROOT).contains(normalizedKeyword)))
                .sorted(Comparator.comparingDouble((Furniture item) -> item.getPosition().getX())
                        .thenComparingDouble(item -> item.getPosition().getZ())
                        .thenComparing(Furniture::getId))
                .limit(10)
                .toList();
        return java.util.stream.IntStream.range(0, matches.size())
                .mapToObj(index -> {
                    Furniture item = matches.get(index);
                    return new FeedbackClarificationResponse.Candidate(item.getId(), item.getType(),
                            clarificationCandidateLabel(item, type, room, index + 1));
                }).toList();
    }

    private String clarificationQuestion(String type, boolean reference) {
        String subject = koreanFurnitureType(type);
        return reference ? subject + " 중 기준으로 사용할 가구를 선택해주세요."
                : subject + " 중 변경할 가구를 선택해주세요.";
    }

    private String clarificationCandidateLabel(Furniture item, String type, Room room, int ordinal) {
        String label = item.getLabel() == null ? "" : item.getLabel().trim();
        if (!label.isBlank() && !label.equalsIgnoreCase(item.getType()) && !label.equalsIgnoreCase(type)) {
            return label;
        }
        String location = clarificationLocationHint(item, room);
        return location + " " + koreanFurnitureType(type) + " " + ordinal;
    }

    private String clarificationLocationHint(Furniture item, Room room) {
        double x = item.getPosition().getX();
        double z = item.getPosition().getZ();
        if (room.getOpenings().stream().filter(opening -> "window".equals(opening.getType()))
                .anyMatch(opening -> distance(item.getPosition(), openingPosition(opening, room)) < 1.2)) {
            return "창가 쪽";
        }
        if (x < room.getWidth() / 3.0) return "방 왼쪽의";
        if (x > room.getWidth() * 2.0 / 3.0) return "방 오른쪽의";
        if (distance(item.getPosition(), new Position(room.getWidth() / 2.0, room.getDepth() / 2.0))
                < Math.min(room.getWidth(), room.getDepth()) / 4.0) return "중앙 근처의";
        return "방 안의";
    }

    private String koreanFurnitureType(String type) {
        String canonicalType = GeneratedFurnitureCatalog.get().normalizeType(type == null ? "" : type);
        if (canonicalType == null) {
            return "가구";
        }
        return switch (canonicalType) {
            case "bed" -> "침대";
            case "bookshelf" -> "책장";
            case "desk" -> "책상";
            case "desk_chair" -> "의자";
            case "drawer_chest" -> "서랍장";
            case "monitor" -> "모니터";
            case "nightstand" -> "협탁";
            case "sofa" -> "소파";
            case "tv" -> "TV";
            case "wardrobe" -> "옷장";
            default -> "가구";
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

    private double distance(Position first, Position second) {
        return Math.hypot(first.getX() - second.getX(), first.getZ() - second.getZ());
    }

    private String operationMessage(FeedbackOperationType type, FeedbackOperationResult.Status status,
                                    String reasonCode) {
        if (status == FeedbackOperationResult.Status.APPLIED) {
            return switch (type) {
                case MOVE -> "가구 위치를 이동했습니다.";
                case ROTATE -> "가구 방향을 회전했습니다.";
                case REPLACE_PRODUCT -> "가구 제품을 교체했습니다.";
                case ADD_FURNITURE -> "가구를 추가했습니다.";
                case REMOVE_FURNITURE -> "가구를 제거했습니다.";
                case SWAP_FURNITURE -> "가구 종류를 교체했습니다.";
                default -> "작업을 적용했습니다.";
            };
        }
        return switch (reasonCode == null ? "" : reasonCode) {
            case "DEPENDENCY_NOT_APPLIED" -> "선행 작업이 적용되지 않아 실행하지 않았습니다.";
            case "AMBIGUOUS_TARGET" -> "변경할 가구를 하나로 특정할 수 없습니다.";
            case "AMBIGUOUS_REFERENCE_TARGET" -> "기준 가구를 하나로 특정할 수 없습니다.";
            case "TARGET_NOT_FOUND" -> "요청한 가구를 찾을 수 없습니다.";
            case "REFERENCE_TARGET_NOT_FOUND" -> "기준 가구를 찾을 수 없습니다.";
            case "NO_RENDERABLE_PRODUCT" -> "렌더링 가능한 제품을 찾을 수 없습니다.";
            case "NO_SAFE_SWAP_CANDIDATE" -> "요청 조건에 맞는 교체 제품을 하나로 정할 수 없습니다.";
            case "NO_LARGER_PRODUCT_AVAILABLE" -> "현재 가구보다 큰 교체 제품이 없습니다.";
            case "NO_SMALLER_PRODUCT_AVAILABLE" -> "현재 가구보다 작은 교체 제품이 없습니다.";
            case "NO_VALID_ADD_PLACEMENT" -> "추가 가구를 놓을 유효한 위치를 찾을 수 없습니다.";
            case "NO_VALID_SWAP_PLACEMENT" -> "교체 가구를 놓을 유효한 위치를 찾을 수 없습니다.";
            case "NO_VALID_BOUNDARY_PLACEMENT" -> "가구를 방 경계 안에 배치할 수 없습니다.";
            case "ROTATION_OUT_OF_BOUNDS" -> "회전하면 가구가 방 경계를 벗어납니다.";
            case "UNSUPPORTED_LOCATION_HINT", "UNSUPPORTED_REFERENCE_LOCATION_HINT" ->
                    "현재 방 정보로 위치 표현을 안전하게 판별할 수 없습니다.";
            default -> "작업을 안전하게 적용할 수 없습니다.";
        };
    }

    private Map<String, Object> interpretedPlan(FeedbackPlan plan) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", plan.source().name());
        result.put("fallbackUsed", plan.fallbackUsed());
        result.put("version", plan.version());
        result.put("requestKind", plan.requestKind().name());
        result.put("targetFurnitureId", plan.furnitureId());
        result.put("targetFurniture", plan.furnitureType());
        result.put("operations", plan.operations().stream().map(operation -> operation.type().name()).toList());
        result.put("operationIds", plan.operations().stream().map(FeedbackOperation::operationId).toList());
        result.put("reason", plan.source() == FeedbackSource.LLM ? "" : plan.reason());
        if (plan.clarification() != null) {
            result.put("clarificationQuestion",
                    clarificationQuestion(plan.clarification().targetFurnitureType(), false));
        }
        if (plan.source() == FeedbackSource.RULE_BASED && !plan.operations().isEmpty()) {
            FeedbackOperation operation = plan.operations().get(0);
            if (operation.type() == FeedbackOperationType.REPLACE_PRODUCT && operation.constraints().largerThanCurrent()) {
                result.put("rawIntent", "LARGER_DESK");
                result.put("deskMinWidth", 1.4);
            }
            if (operation.type() == FeedbackOperationType.REPLACE_PRODUCT && operation.constraints().storagePreferred()) {
                result.put("storagePriority", "HIGH");
            }
            if (operation.type() == FeedbackOperationType.MOVE && "방이 넓어 보이게".equals(plan.reason())) {
                result.put("openSpacePriority", "HIGH");
            }
        }
        return result;
    }

    private Layout findLayoutOrThrow(Long layoutId) {
        return layoutRepository.findById(layoutId)
                .orElseThrow(() -> new CustomException(ErrorCode.LAYOUT_NOT_FOUND));
    }

    private LayoutResponse snapshotResponse(Layout layout) {
        Room room = roomAccessService.findReadableRoom(layout.getRoomId());
        AgentContext context = findContextOrNull(layout.getContextId());
        ValidationResult validationResult = validationService.validate(room, layout.getFurniture());
        ScoreSummary scoreSummary = scoreService.calculate(context, layout.getFurniture(), validationResult);
        return LayoutResponse.ofSnapshot(layout, scoreSummary, validationResult);
    }

    /** contextId가 null인 blank/direct-placement Layout(§8)을 위한 조회 — null이면 그대로 null 반환. */
    private AgentContext findContextOrNull(Long contextId) {
        if (contextId == null) {
            return null;
        }
        return agentContextRepository.findById(contextId)
                .orElseThrow(() -> new CustomException(ErrorCode.CONTEXT_NOT_FOUND));
    }

    private List<Furniture> deepCopyFurniture(List<Furniture> furniture) {
        return furniture.stream()
                .map(item -> copyFurniture(item, item.getPosition(), item.getRotation(),
                        item.getWidth(), item.getDepth(), item.getHeight(), item.getStatus()))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    /**
     * 기존 furniture 리스트에 position/rotation override를 적용해 새 리스트를 만든다.
     */
    private List<Furniture> applyPositionOverrides(List<Furniture> base, List<FurniturePositionDto> overrides, Room room) {
        validateFurnitureArray(base, overrides);

        Map<String, FurniturePositionDto> overrideById = overrides.stream()
                .collect(Collectors.toMap(FurniturePositionDto::getId, o -> o));

        return base.stream().map(f -> {
            FurniturePositionDto override = overrideById.get(f.getId());
            validateRotation(override.getRotation());
            validatePosition(room, f, override);
            return copyWithOverride(f, override);
        }).collect(Collectors.toList());
    }

    private void validateFurnitureArray(List<Furniture> base, List<FurniturePositionDto> overrides) {
        if (overrides == null || overrides.isEmpty()) {
            throw new CustomException(ErrorCode.INVALID_REQUEST_BODY);
        }
        boolean hasInvalidItem = overrides.stream()
                .anyMatch(override -> override == null || isBlank(override.getId()));
        if (hasInvalidItem) {
            throw new CustomException(ErrorCode.INVALID_REQUEST_BODY);
        }

        Set<String> baseIds = base.stream()
                .map(Furniture::getId)
                .collect(Collectors.toSet());
        Set<String> requestIds = overrides.stream()
                .map(FurniturePositionDto::getId)
                .collect(Collectors.toSet());

        boolean hasUnknownId = requestIds.stream().anyMatch(id -> !baseIds.contains(id));
        if (hasUnknownId) {
            throw new CustomException(ErrorCode.FURNITURE_NOT_FOUND);
        }
        if (!requestIds.containsAll(baseIds) || requestIds.size() != baseIds.size()) {
            throw new CustomException(ErrorCode.FURNITURE_ARRAY_MISMATCH);
        }
    }

    private void validateRotation(double rotation) {
        if (rotation < 0 || rotation >= 360) {
            throw new CustomException(ErrorCode.INVALID_ROTATION);
        }
    }

    private void validatePosition(Room room, Furniture furniture, FurniturePositionDto override) {
        if (override.getPosition() == null) {
            throw new CustomException(ErrorCode.INVALID_FURNITURE_POSITION);
        }

        FurnitureBoundary.Footprint footprint = FurnitureBoundary.footprint(
                furniture.getWidth(), furniture.getDepth(), override.getRotation(), furniture.getVariantId());
        Position position = new Position(override.getPosition().getX(), override.getPosition().getZ());
        if (!FurnitureBoundary.isInside(room, position, footprint)) {
            throw new CustomException(ErrorCode.INVALID_FURNITURE_POSITION);
        }
    }

    private Furniture copyWithOverride(Furniture furniture, FurniturePositionDto override) {
        FurnitureStatus status = parseFurnitureStatus(override.getStatus(), furniture.getStatus());
        return new Furniture(
                furniture.getId(),
                furniture.getType(),
                furniture.getLabel(),
                furniture.getWidth(),
                furniture.getDepth(),
                furniture.getHeight(),
                new Position(override.getPosition().getX(), override.getPosition().getZ()),
                override.getRotation(),
                status,
                furniture.getProductId(),
                furniture.getStyleTags(),
                furniture.getVariantId()
        );
    }

    private FurnitureStatus parseFurnitureStatus(String rawStatus, FurnitureStatus fallback) {
        if (rawStatus == null) {
            return fallback;
        }
        try {
            return FurnitureStatus.valueOf(rawStatus);
        } catch (IllegalArgumentException e) {
            throw new CustomException(ErrorCode.INVALID_FURNITURE_STATUS);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private List<Furniture> applyFeedbackIntent(Room room, List<Furniture> furniture, FeedbackIntent intent) {
        return switch (intent.type()) {
            case LARGER_DESK -> furniture.stream()
                    .map(item -> copyWithLargerDesk(room, item))
                    .collect(Collectors.toList());
            case STORAGE_PRIORITY -> applyStoragePriority(furniture);
            case OPEN_SPACE_PRIORITY -> applyOpenSpacePriority(furniture);
        };
    }

    private Furniture copyWithLargerDesk(Room room, Furniture furniture) {
        if (!"desk".equals(furniture.getType())) {
            return copyFurniture(furniture, furniture.getPosition(), furniture.getRotation(),
                    furniture.getWidth(), furniture.getDepth(), furniture.getHeight(), furniture.getStatus());
        }

        double width = Math.max(furniture.getWidth(), 1.4);
        Position position = clampPositionInsideRoom(room, furniture.getPosition(), width,
                furniture.getDepth(), furniture.getRotation(), furniture.getVariantId());

        return copyFurniture(furniture, position, furniture.getRotation(), width,
                furniture.getDepth(), furniture.getHeight(), furniture.getStatus());
    }

    private Position clampPositionInsideRoom(Room room, Position position, double width,
                                             double depth, double rotation, String variantId) {
        FurnitureBoundary.Footprint footprint = FurnitureBoundary.footprint(width, depth, rotation, variantId);
        return FurnitureBoundary.clamp(room, position, footprint).orElse(position);
    }

    private List<Furniture> applyStoragePriority(List<Furniture> furniture) {
        boolean hasStorage = furniture.stream().anyMatch(item -> "storage".equals(item.getType()));
        List<Furniture> updated = furniture.stream()
                .map(item -> {
                    if (!"storage".equals(item.getType())) {
                        return copyFurniture(item, item.getPosition(), item.getRotation(),
                                item.getWidth(), item.getDepth(), item.getHeight(), item.getStatus());
                    }
                    return copyFurniture(item, item.getPosition(), item.getRotation(),
                            Math.max(item.getWidth(), 1.0), Math.max(item.getDepth(), 0.45),
                            Math.max(item.getHeight(), 1.8), item.getStatus());
                })
                .collect(Collectors.toList());

        if (!hasStorage) {
            updated.add(new Furniture("storage-feedback-1", "storage", "storage",
                    1.0, 0.45, 1.8, new Position(0.7, 3.6), 0,
                    FurnitureStatus.RECOMMENDED, null, List.of()));
        }
        return updated;
    }

    private List<Furniture> applyOpenSpacePriority(List<Furniture> furniture) {
        return furniture.stream()
                .map(item -> {
                    Position position = switch (item.getType()) {
                        case "bed" -> new Position(0.8, 1.4);
                        case "desk" -> new Position(2.4, 1.0);
                        case "chair" -> new Position(2.4, 1.7);
                        case "storage" -> new Position(2.6, 3.7);
                        default -> item.getPosition();
                    };
                    return copyFurniture(item, position, item.getRotation(),
                            item.getWidth(), item.getDepth(), item.getHeight(), item.getStatus());
                })
                .collect(Collectors.toList());
    }

    private Furniture copyFurniture(Furniture furniture, Position position, double rotation,
                                     double width, double depth, double height, FurnitureStatus status) {
        return new Furniture(
                furniture.getId(),
                furniture.getType(),
                furniture.getLabel(),
                width,
                depth,
                height,
                new Position(position.getX(), position.getZ()),
                rotation,
                status,
                furniture.getProductId(),
                furniture.getStyleTags(),
                furniture.getVariantId()
        );
    }

}
