package com.roomfit.placement;

import com.roomfit.agent.domain.AgentContext;
import com.roomfit.agent.domain.DesignStyle;
import com.roomfit.agent.domain.LifestyleGoal;
import com.roomfit.agent.domain.PreferredColorTone;
import com.roomfit.common.CustomException;
import com.roomfit.common.ErrorCode;
import com.roomfit.placement.dto.FeedbackClarificationResponse;
import com.roomfit.placement.dto.WorkspaceFeedbackPreviewRequest;
import com.roomfit.placement.dto.WorkspaceFeedbackPreviewResponse;
import com.roomfit.room.*;
import org.springframework.stereotype.Service;

import java.util.*;

/** Preview only: no persistence dependency and no mutation of the readable Room. */
@Service
public class WorkspaceFeedbackPreviewService {
    private static final Set<String> ALL_FIELDS = Set.of("PRODUCT", "DIMENSIONS", "POSITION", "ORIENTATION", "APPEARANCE");
    private static final Set<String> LOCKED_FIELDS = Set.of("PRODUCT", "DIMENSIONS", "POSITION", "ORIENTATION");
    private final RoomAccessService roomAccess;
    private final OpenAiWorkspaceFeedbackInterpreter interpreter;
    private final DeterministicFeedbackExecutor executor;
    private final ValidationService validation;
    private final FurnitureDomainPolicy domainPolicy;

    public WorkspaceFeedbackPreviewService(RoomAccessService roomAccess, OpenAiWorkspaceFeedbackInterpreter interpreter,
                                           DeterministicFeedbackExecutor executor, ValidationService validation,
                                           FurnitureDomainPolicy domainPolicy) {
        this.roomAccess = roomAccess;
        this.interpreter = interpreter;
        this.executor = executor;
        this.validation = validation;
        this.domainPolicy = domainPolicy;
    }

    public WorkspaceFeedbackPreviewResponse preview(WorkspaceFeedbackPreviewRequest request) {
        Room room = roomAccess.findReadableRoom(request.roomId());
        List<Furniture> original = snapshot(request, room);
        ValidationResult baseline = validation.validate(room, original);
        var source = WorkspaceFeedbackIntentGuard.analyze(request, original);
        if (source.issue() != null) return guardedResponse(request, source.issue(), original, baseline, "INPUT_GUARD");
        WorkspaceFeedbackPlan interpreted = interpreter.interpret(request, room, original);
        var issue = source.check(interpreted.plan(), original);
        if (issue != null) return guardedResponse(request, issue, original, baseline, "LLM");
        Map<String, Set<String>> preserve = new LinkedHashMap<>();
        interpreted.preserve().forEach((id, fields) -> preserve.put(id, new HashSet<>(fields)));
        source.preserve().forEach((id, fields) -> preserve.computeIfAbsent(id, key -> new HashSet<>()).addAll(fields));
        interpreted = new WorkspaceFeedbackPlan(interpreted.plan(), preserve, interpreted.clarification(), interpreted.unsupportedReason());
        if (interpreted.unsupportedReason() != null) {
            return response(request, FeedbackStatus.FAILED, interpreted.unsupportedReason(),
                    "이번 연결에서는 이 요청을 아직 지원하지 않습니다. 현재 배치는 유지됩니다.", original, List.of(), null, baseline);
        }
        if (interpreted.plan().needsClarification()) {
            return response(request, FeedbackStatus.NEEDS_CLARIFICATION, "NEEDS_CLARIFICATION",
                    interpreted.clarification().question(), original, List.of(), interpreted.clarification(), baseline);
        }
        FeedbackClarificationResponse unconfirmed = unconfirmedDuplicate(request, interpreted.plan(), original);
        if (unconfirmed != null) {
            return response(request, FeedbackStatus.NEEDS_CLARIFICATION, unconfirmed.reasonCode(),
                    unconfirmed.question(), original, List.of(), unconfirmed, baseline);
        }

        // The executor also uses an isolated snapshot; preservation is checked again after execution.
        FeedbackExecution execution = executor.execute(interpreted.plan(), room, original, context(request));
        if (!execution.result().applied()) {
            FeedbackClarificationResponse question = clarification(execution, interpreted.plan(), original);
            return response(request, question == null ? FeedbackStatus.FAILED : FeedbackStatus.NEEDS_CLARIFICATION,
                    execution.result().noChangeReason(), execution.result().summary(), original,
                    execution.operationResults(), question, baseline);
        }
        List<Furniture> candidate = execution.furniture().stream().map(WorkspaceFeedbackPreviewService::canonicalCopy).toList();
        String violation = preservationViolation(request, interpreted, original, candidate, execution.operationResults());
        if (violation == null) {
            try { domainPolicy.validateFinalState(candidate); }
            catch (CustomException e) { violation = e.getErrorCode().name(); }
        }
        ValidationResult checked = validation.validate(room, candidate);
        if (violation == null && !safe(checked)) violation = "NO_SAFE_CANDIDATE";
        if (violation != null) {
            List<FeedbackOperationExecution> rolledBack = execution.operationResults().stream()
                    .map(result -> new FeedbackOperationExecution(result.operationId(), result.type(),
                            FeedbackOperationExecution.Status.FAILED, "ATOMIC_ROLLBACK", null)).toList();
            return response(request, FeedbackStatus.FAILED, violation,
                    "요청한 변경이 유지 조건 또는 공간 검증을 통과하지 못했습니다. 현재 배치는 유지됩니다.",
                    original, rolledBack, null, baseline);
        }
        return response(request, FeedbackStatus.SUCCESS, null, "배치 변경 후보를 만들었습니다. 아직 적용하거나 저장하지 않았습니다.",
                candidate, execution.operationResults(), null, checked);
    }

    private static WorkspaceFeedbackPreviewResponse guardedResponse(WorkspaceFeedbackPreviewRequest request,
            WorkspaceFeedbackIntentGuard.Issue issue, List<Furniture> original, ValidationResult validation, String source) {
        var question = issue.status() == FeedbackStatus.NEEDS_CLARIFICATION
                ? new FeedbackClarificationResponse(issue.code(), issue.message(), null, issue.requiredField(), "targetFurnitureId".equals(issue.requiredField())
                        ? original.stream().filter(item -> item.getStatus() != FurnitureStatus.DELETED)
                            .map(item -> new FeedbackClarificationResponse.Candidate(item.getId(), item.getType(), item.getLabel())).toList()
                        : List.of()) : null;
        return new WorkspaceFeedbackPreviewResponse(request.revision(), issue.status(), source, issue.code(), issue.message(),
                original, List.of(), question, validation);
    }

    private List<Furniture> snapshot(WorkspaceFeedbackPreviewRequest request, Room room) {
        var size = request.roomDimensions();
        if (!near(size.width(), room.getWidth()) || !near(size.depth(), room.getDepth()) || !near(size.height(), room.getHeight())) {
            throw new CustomException(ErrorCode.FEEDBACK_ROOM_CHANGED);
        }
        Set<String> ids = new HashSet<>();
        List<Furniture> items = new ArrayList<>();
        for (var item : request.furniture()) {
            String type = FeedbackVocabularyNormalizer.normalizeCanonicalType(item.type());
            if (!ids.add(item.id()) || type.isBlank()
                    || !positive(item.width(), 50) || !positive(item.depth(), 50) || !positive(item.height(), 10)
                    || !bounded(item.position().x(), 100) || !bounded(item.position().z(), 100)
                    || !bounded(item.rotation(), 3600)) invalid();
            try {
                items.add(new Furniture(item.id(), type, item.label(), item.width(), item.depth(), item.height(),
                        new Position(item.position().x(), item.position().z()), item.rotation(), item.status(),
                        item.productId(), item.styleTags(), item.variantId()));
            } catch (IllegalArgumentException e) { invalid(); }
        }
        Set<String> active = new HashSet<>();
        items.stream().filter(item -> item.getStatus() != FurnitureStatus.DELETED).forEach(item -> active.add(item.getId()));
        if (active.size() > 12 || !active.containsAll(request.lockedFurnitureIds())
                || new HashSet<>(request.lockedFurnitureIds()).size() != request.lockedFurnitureIds().size()
                || (request.selectedFurnitureId() != null && !active.contains(request.selectedFurnitureId()))) invalid();
        Set<String> answeredFields = new HashSet<>();
        if (request.clarificationSelections() != null) for (var answer : request.clarificationSelections()) {
            if (!Objects.equals(answer.revision(), request.revision()) || !Objects.equals(answer.feedback(), request.feedback())
                    || !active.contains(answer.furnitureId()) || !answeredFields.add(answer.requiredField())
                    || !Set.of("targetFurnitureId", "referenceFurnitureId").contains(answer.requiredField())) invalid();
        }
        domainPolicy.validateFinalState(items);
        return List.copyOf(items);
    }

    private String preservationViolation(WorkspaceFeedbackPreviewRequest request, WorkspaceFeedbackPlan interpreted,
                                         List<Furniture> original, List<Furniture> candidate,
                                         List<FeedbackOperationExecution> results) {
        Map<String, Set<String>> allowed = new HashMap<>();
        Set<String> removed = new HashSet<>();
        Set<String> added = new HashSet<>();
        for (FeedbackOperationExecution result : results) {
            if (result.status() != FeedbackOperationExecution.Status.APPLIED) continue;
            String id = result.affectedFurnitureId();
            Set<String> fields = switch (result.type()) {
                case MOVE -> Set.of("POSITION");
                case ROTATE -> Set.of("ORIENTATION");
                case REPLACE_PRODUCT, SWAP_FURNITURE -> Set.of("PRODUCT", "DIMENSIONS", "APPEARANCE");
                case REMOVE_FURNITURE -> { removed.add(id); yield Set.of(); }
                case ADD_FURNITURE -> { added.add(id); yield Set.of(); }
                default -> Set.of();
            };
            allowed.computeIfAbsent(id, key -> new HashSet<>()).addAll(fields);
        }
        Map<String, Furniture> after = new HashMap<>();
        for (Furniture item : candidate) {
            if (after.put(item.getId(), item) != null) return "UNREQUESTED_CHANGE";
        }
        if (candidate.stream().filter(item -> item.getStatus() != FurnitureStatus.DELETED).count() > 12) return "FURNITURE_LIMIT_EXCEEDED";
        for (Furniture before : original) {
            Furniture next = after.remove(before.getId());
            Set<String> explicit = interpreted.preserve().getOrDefault(before.getId(), Set.of());
            boolean locked = request.lockedFurnitureIds().contains(before.getId());
            if (next == null || next.getStatus() != before.getStatus()) {
                if (locked) return "LOCKED_FURNITURE";
                if (!explicit.isEmpty()) return "PRESERVATION_CONFLICT";
                if (!removed.contains(before.getId())) return "UNREQUESTED_CHANGE";
                continue;
            }
            if (!before.getType().equals(next.getType())) return "UNREQUESTED_CHANGE";
            if (locked && changed(before, next, LOCKED_FIELDS)) return "LOCKED_FURNITURE";
            if (changed(before, next, explicit)) return "PRESERVATION_CONFLICT";
            Set<String> protectedFields = new HashSet<>(ALL_FIELDS);
            protectedFields.removeAll(allowed.getOrDefault(before.getId(), Set.of()));
            if (changed(before, next, protectedFields)) return "UNREQUESTED_CHANGE";
        }
        if (!added.containsAll(after.keySet())) return "UNREQUESTED_CHANGE";
        return null;
    }

    private static boolean changed(Furniture before, Furniture after, Set<String> fields) {
        if (fields.contains("POSITION") && (!near(before.getPosition().getX(), after.getPosition().getX())
                || !near(before.getPosition().getZ(), after.getPosition().getZ()))) return true;
        if (fields.contains("ORIENTATION") && !near(normalizeRotation(before.getRotation()), normalizeRotation(after.getRotation()))) return true;
        if (fields.contains("DIMENSIONS") && (!near(before.getWidth(), after.getWidth())
                || !near(before.getDepth(), after.getDepth()) || !near(before.getHeight(), after.getHeight()))) return true;
        if (fields.contains("PRODUCT") && (!Objects.equals(before.getProductId(), after.getProductId())
                || !Objects.equals(before.getVariantId(), after.getVariantId()) || !Objects.equals(before.getLabel(), after.getLabel()))) return true;
        return fields.contains("APPEARANCE") && (!Objects.equals(before.getVariantId(), after.getVariantId())
                || !before.getStyleTags().equals(after.getStyleTags()));
    }

    private static FeedbackClarificationResponse clarification(FeedbackExecution execution, FeedbackPlan plan, List<Furniture> items) {
        String reason = execution.result().noChangeReason();
        if (!Set.of("AMBIGUOUS_TARGET", "AMBIGUOUS_REFERENCE_TARGET", "TARGET_NOT_FOUND", "REFERENCE_TARGET_NOT_FOUND").contains(reason)) return null;
        FeedbackOperationExecution failed = execution.operationResults().stream()
                .filter(result -> Objects.equals(reason, result.reasonCode())).findFirst().orElse(null);
        if (failed == null) return null;
        FeedbackOperation operation = plan.operations().stream().filter(op -> op.operationId().equals(failed.operationId())).findFirst().orElseThrow();
        boolean reference = reason.contains("REFERENCE");
        FeedbackTargetSelector selector = reference ? operation.referenceTarget() : operation.target();
        List<FeedbackClarificationResponse.Candidate> candidates = items.stream()
                .filter(item -> item.getStatus() != FurnitureStatus.DELETED)
                .filter(item -> selector.furnitureType().isBlank() || item.getType().equals(FeedbackVocabularyNormalizer.normalizeCanonicalType(selector.furnitureType())))
                .filter(item -> selector.labelKeyword().isBlank() || item.getLabel().contains(selector.labelKeyword()))
                .map(item -> new FeedbackClarificationResponse.Candidate(item.getId(), item.getType(), item.getLabel())).toList();
        return new FeedbackClarificationResponse(reason, reference ? "어느 가구를 기준으로 할까요?" : "어느 가구를 변경할까요?",
                operation.operationId(), reference ? "referenceFurnitureId" : "targetFurnitureId", candidates);
    }

    /** A provider-supplied ID is not evidence that the user chose one of several items. */
    private static FeedbackClarificationResponse unconfirmedDuplicate(WorkspaceFeedbackPreviewRequest request,
                                                                     FeedbackPlan plan, List<Furniture> items) {
        for (FeedbackOperation operation : plan.operations()) {
            for (boolean reference : List.of(false, true)) {
                if (!reference && operation.type() == FeedbackOperationType.ADD_FURNITURE) continue;
                FeedbackTargetSelector selector = reference ? operation.referenceTarget() : operation.target();
                if (selector == null) continue;
                String type = selector.furnitureType();
                if (type.isBlank()) type = items.stream().filter(item -> item.getId().equals(selector.furnitureId()))
                        .map(Furniture::getType).findFirst().orElse("");
                final String targetType = type;
                List<Furniture> matches = items.stream().filter(item -> item.getStatus() != FurnitureStatus.DELETED)
                        .filter(item -> item.getType().equals(targetType)).toList();
                if (matches.size() < 2) continue;
                String field = reference ? "referenceFurnitureId" : "targetFurnitureId";
                boolean answered = request.clarificationSelections() != null && request.clarificationSelections().stream()
                        .anyMatch(answer -> answer.requiredField().equals(field) && answer.furnitureId().equals(selector.furnitureId()));
                if (answered) continue;
                String text = compact(request.feedback());
                String keyword = compact(selector.labelKeyword());
                List<Furniture> named = matches.stream().filter(item -> !compact(item.getLabel()).isBlank()
                        && text.contains(compact(item.getLabel()))).toList();
                if (named.isEmpty() && !keyword.isBlank() && text.contains(keyword)) {
                    named = matches.stream().filter(item -> compact(item.getLabel()).contains(keyword)).toList();
                }
                if (named.size() == 1 && named.getFirst().getId().equals(selector.furnitureId())) continue;
                // Toolbar selection resolves only an unqualified action target, never a reference or a conflicting name.
                if (!reference && named.size() != 1 && Objects.equals(request.selectedFurnitureId(), selector.furnitureId())) continue;
                List<FeedbackClarificationResponse.Candidate> candidates = matches.stream()
                        .map(item -> new FeedbackClarificationResponse.Candidate(item.getId(), item.getType(), item.getLabel())).toList();
                return new FeedbackClarificationResponse(reference ? "AMBIGUOUS_REFERENCE_TARGET" : "AMBIGUOUS_TARGET",
                        reference ? "어느 가구를 기준으로 할까요?" : "어느 가구를 변경할까요?",
                        operation.operationId(), field, candidates);
            }
        }
        return null;
    }

    private static String compact(String text) { return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("\\s+", ""); }

    private static AgentContext context(WorkspaceFeedbackPreviewRequest request) {
        LifestyleGoal purpose = switch (request.preferences().purpose()) {
            case "휴식" -> LifestyleGoal.RELAX_FOCUSED;
            case "업무 / 공부" -> LifestyleGoal.STUDY_FOCUSED;
            case "취미 / 여가" -> LifestyleGoal.HOBBY_FOCUSED;
            default -> LifestyleGoal.STORAGE_FOCUSED;
        };
        PreferredColorTone palette = switch (request.preferences().palette()) {
            case "ivory" -> PreferredColorTone.WHITE_IVORY;
            case "beige" -> PreferredColorTone.BEIGE_SAND;
            case "gray" -> PreferredColorTone.GRAY;
            case "brown" -> PreferredColorTone.BROWN_WOOD;
            case "green" -> PreferredColorTone.GREEN_OLIVE;
            case "blue" -> PreferredColorTone.BLUE_NAVY;
            case "pink" -> PreferredColorTone.PINK_CORAL;
            case "black" -> PreferredColorTone.BLACK_DARK;
            default -> null;
        };
        return new AgentContext(request.roomId(), purpose, List.of(DesignStyle.valueOf(request.preferences().style().toUpperCase(Locale.ROOT))),
                List.of(), List.of(), List.of(), List.of(), List.of(), palette);
    }

    private static Furniture canonicalCopy(Furniture item) {
        return new Furniture(item.getId(), FeedbackVocabularyNormalizer.normalizeCanonicalType(item.getType()), item.getLabel(),
                item.getWidth(), item.getDepth(), item.getHeight(), new Position(item.getPosition().getX(), item.getPosition().getZ()),
                item.getRotation(), item.getStatus(), item.getProductId(), item.getStyleTags(), item.getVariantId());
    }

    private static WorkspaceFeedbackPreviewResponse response(WorkspaceFeedbackPreviewRequest request, FeedbackStatus status,
            String reason, String summary, List<Furniture> items, List<FeedbackOperationExecution> operations,
            FeedbackClarificationResponse clarification, ValidationResult validation) {
        return new WorkspaceFeedbackPreviewResponse(request.revision(), status, "LLM", reason, summary, items, operations, clarification, validation);
    }
    private static boolean safe(ValidationResult result) {
        return result.isCollisionFree() && result.isBoundaryValid() && result.isDoorClearance() && result.isWindowClearance() && result.isPathSecured();
    }
    private static double normalizeRotation(double value) { return (value % 360 + 360) % 360; }
    private static boolean near(double first, double second) { return Double.isFinite(first) && Double.isFinite(second) && Math.abs(first - second) <= 1e-6; }
    private static boolean positive(double value, double max) { return Double.isFinite(value) && value > 0 && value <= max; }
    private static boolean bounded(double value, double max) { return Double.isFinite(value) && Math.abs(value) <= max; }
    private static void invalid() { throw new CustomException(ErrorCode.INVALID_REQUEST_BODY); }
}
