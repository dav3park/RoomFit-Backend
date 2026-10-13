package com.roomfit.placement;

import com.roomfit.placement.dto.WorkspaceFeedbackPreviewRequest;
import com.roomfit.room.Furniture;
import com.roomfit.room.FurnitureStatus;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Source constraints are checked independently of the provider's preserve/unsupported fields. */
final class WorkspaceFeedbackIntentGuard {
    private static final Set<String> ALL = Set.of("PRODUCT", "DIMENSIONS", "POSITION", "ORIENTATION", "APPEARANCE", "EXISTENCE");
    private static final Pattern LENGTH = Pattern.compile("(?:[+-]?\\d+(?:[.,]\\d+)?|[영공일이삼사오육칠팔구십백천한두세네]+)(?:mm|cm|m(?![a-z])|밀리미터|센티미터|센티|센치미터|센치|미터)");
    private static final Pattern ANGLE = Pattern.compile("([+-]?\\d+(?:\\.\\d+)?)(?:도|°|deg(?:rees)?)");
    private static final Pattern NEGATIVE = Pattern.compile("(옮기|움직이|이동|돌리|회전|삭제|제거|없애|치우|추가|교체|바꾸|변경|건드리|건들|손대)(?:(?:하지|시키지|지)(?:말|마|않)|금지|말고|안(?:해|하))");
    private static final Pattern UNKNOWN_NEGATIVE = Pattern.compile("(?:하지|지)(?:말|마|않)|금지|제외|말고|안(?:옮|움직|이동|회전|돌|삭제|제거|추가|교체|바꾸)");
    private static final Pattern KEEP = Pattern.compile("그대로|유지(?:해|하|한|할)|고정(?:해|하|한|할)|건드리지|건들지");
    private static final Pattern CLAUSE = Pattern.compile("(?<=말고)|(?<=않고)|(?<=두고)|(?<=유지하고)|(?<=고정하고)|(?<=옮기고)|(?<=이동하고)|(?<=옮겨줘)|(?<=회전하고)|(?<=삭제하고)|(?<=추가하고)|(?<=돌리고)|(?<=회전해줘)|[,;!?]|\\.(?!\\d)");

    record Issue(FeedbackStatus status, String code, String message, String requiredField) {}
    record Rules(Map<String, Set<String>> preserve, Map<String, Set<FeedbackOperationType>> forbidden,
                 Set<String> forbiddenAddTypes, List<String> positiveClauses, Map<String, FeedbackOrientation> rotations,
                 int angleCount, Issue issue) {
        Issue check(FeedbackPlan plan, List<Furniture> furniture) {
            if (issue != null || plan.needsClarification()) return issue;
            if (!rotations.isEmpty() && plan.operations().stream().filter(op -> op.type() == FeedbackOperationType.ROTATE).count() != angleCount)
                return failure("ANGLE_MISMATCH", "요청한 회전을 모두 정확하게 해석하지 못해 후보를 만들지 않았습니다.");
            for (FeedbackOperation operation : plan.operations()) {
                List<Furniture> targets = targets(operation.target(), furniture);
                if (operation.constraints() != null && operation.constraints().minWidth() != null)
                    return failure("NUMERIC_REQUIREMENT_MISMATCH", "명시하지 않은 수치 조건이 계획에 포함되어 후보를 만들지 않았습니다.");
                if (operation.type() == FeedbackOperationType.ADD_FURNITURE) {
                    if (forbiddenAddTypes.contains("*") || forbiddenAddTypes.contains(operation.target().furnitureType()))
                        return failure("FORBIDDEN_OPERATION", "추가 금지 조건을 위반하는 계획이라 후보를 만들지 않았습니다.");
                } else if (targets.stream().anyMatch(item -> forbidden.getOrDefault(item.getId(), Set.of()).contains(operation.type()))) {
                    boolean conflict = positiveClauses.stream().anyMatch(clause -> actionMatches(clause, operation.type())
                            && (types(clause).isEmpty() || targets.stream().anyMatch(item -> types(clause).contains(item.getType()))));
                    return conflict ? question("CONFLICTING_REQUIREMENTS", "유지·금지 조건과 변경 요청이 충돌합니다. 어느 조건을 유지할지 요청을 수정해 주세요.", "request")
                            : failure("FORBIDDEN_OPERATION", "금지한 작업이 변경 계획에 포함되어 후보를 만들지 않았습니다. 현재 배치는 유지됩니다.");
                }
                if (positiveClauses.stream().noneMatch(clause -> actionMatches(clause, operation.type())))
                    return failure("UNREQUESTED_OPERATION", "원문에 요청하지 않은 작업이 계획에 포함되어 후보를 만들지 않았습니다.");
                if (operation.type() == FeedbackOperationType.MOVE && Set.of(FeedbackRelation.LEFT, FeedbackRelation.RIGHT,
                        FeedbackRelation.FORWARD, FeedbackRelation.BACKWARD).contains(operation.placement().relation())
                        && positiveClauses.stream().noneMatch(clause -> clause.contains("방기준") || clause.contains("방을기준") || clause.contains("방좌표"))) {
                    return question("AMBIGUOUS_DIRECTION_FRAME", "방 기준 이동인지 확인해 주세요. 예: 책상을 방 기준 오른쪽으로 조금 옮겨줘.", "directionFrame");
                }
                if (operation.type() == FeedbackOperationType.ROTATE && !rotations.isEmpty()) {
                    FeedbackOrientation expected = targets.stream().map(item -> rotations.getOrDefault(item.getType(), rotations.get("*"))).filter(Objects::nonNull).findFirst().orElse(null);
                    if (expected != null && operation.placement().orientation() != expected) return failure("ANGLE_MISMATCH", "요청한 회전각·방향과 다른 계획이라 후보를 만들지 않았습니다.");
                }
            }
            return null;
        }
    }

    static Rules analyze(WorkspaceFeedbackPreviewRequest request, List<Furniture> furniture) {
        Map<String, Set<String>> preserve = new LinkedHashMap<>();
        Map<String, Set<FeedbackOperationType>> forbidden = new LinkedHashMap<>();
        Set<String> noAdd = new LinkedHashSet<>();
        List<String> positive = new ArrayList<>();
        Issue issue = null;
        for (String clause : CLAUSE.split(compact(request.feedback()))) {
            if (clause.isBlank()) continue;
            var negative = NEGATIVE.matcher(clause);
            var keep = KEEP.matcher(clause);
            String stem = negative.find() ? negative.group(1) : null;
            boolean keeping = stem == null && keep.find();
            if (stem == null && !keeping) {
                if (UNKNOWN_NEGATIVE.matcher(clause).find()) {
                    issue = question("UNCLEAR_PROHIBITION", "금지·제외 조건의 대상과 작업을 구분해 요청을 수정해 주세요. 현재 배치는 유지됩니다.", "request");
                } else positive.add(clause);
                continue;
            }
            if (LENGTH.matcher(clause).find() || ANGLE.matcher(clause).find()) {
                issue = question("UNCLEAR_PROHIBITION", "수치가 포함된 유지·금지 조건은 아직 정확히 해석할 수 없습니다. 조건을 구분해 요청해 주세요.", "request");
                continue;
            }
            int at = stem == null ? keep.start() : negative.start();
            String prefix = clause.substring(0, at);
            Set<String> mentioned = types(prefix);
            if (mentioned.size() > 1 && (prefix.contains("옆") || prefix.contains("기준") || prefix.contains("근처"))) {
                issue = question("UNCLEAR_PROHIBITION", "금지 조건을 적용할 가구를 하나씩 명시해 주세요.", "request");
                continue;
            }
            if ("추가".equals(stem)) { noAdd.addAll(mentioned.isEmpty() ? Set.of("*") : mentioned); continue; }
            List<Furniture> scoped = furniture.stream().filter(item -> item.getStatus() != FurnitureStatus.DELETED)
                    .filter(item -> !mentioned.isEmpty() ? mentioned.contains(item.getType())
                            : Objects.equals(item.getId(), request.selectedFurnitureId()) || prefix.contains("가구") || prefix.contains("전체"))
                    .toList();
            if (scoped.isEmpty()) {
                issue = question("UNCLEAR_PROHIBITION", "유지·금지 조건을 적용할 가구를 명시해 주세요.", "request");
                continue;
            }
            Set<String> fields;
            Set<FeedbackOperationType> bans;
            if (stem == null || List.of("건드리", "건들", "손대").contains(stem)) {
                fields = prefix.contains("크기") ? Set.of("DIMENSIONS") : prefix.contains("색") ? Set.of("APPEARANCE") : ALL;
                bans = fields == ALL ? Set.of(FeedbackOperationType.MOVE, FeedbackOperationType.ROTATE,
                        FeedbackOperationType.REMOVE_FURNITURE, FeedbackOperationType.REPLACE_PRODUCT, FeedbackOperationType.SWAP_FURNITURE) : Set.of();
            } else if (List.of("옮기", "움직이", "이동").contains(stem)) {
                fields = Set.of("POSITION"); bans = Set.of(FeedbackOperationType.MOVE);
            } else if (List.of("돌리", "회전").contains(stem)) {
                fields = Set.of("ORIENTATION"); bans = Set.of(FeedbackOperationType.ROTATE);
            } else if (List.of("삭제", "제거", "없애", "치우").contains(stem)) {
                fields = Set.of("EXISTENCE"); bans = Set.of(FeedbackOperationType.REMOVE_FURNITURE);
            } else {
                fields = Set.of("PRODUCT", "DIMENSIONS", "APPEARANCE");
                bans = Set.of(FeedbackOperationType.REPLACE_PRODUCT, FeedbackOperationType.SWAP_FURNITURE);
            }
            for (Furniture item : scoped) {
                preserve.computeIfAbsent(item.getId(), key -> new LinkedHashSet<>()).addAll(fields);
                forbidden.computeIfAbsent(item.getId(), key -> new LinkedHashSet<>()).addAll(bans);
            }
        }
        Map<String, FeedbackOrientation> rotations = new LinkedHashMap<>();
        int angles = 0;
        for (String rawClause : positive) {
            String clause = normalizeAngles(rawClause);
            if ((clause.contains("색깔") || clause.contains("색상") || clause.contains("색만"))
                    && (clause.contains("바꿔") || clause.contains("변경") || clause.contains("바꾸"))) {
                issue = failure("UNSUPPORTED_COLOR_CHANGE", "가구 색상만 변경하는 기능은 아직 지원하지 않습니다. 복합 요청도 적용하지 않습니다."); break;
            }
            var intent = FeedbackActionIntentResolver.resolveFurnitureActionIntent(clause);
            Set<String> mentioned = types(clause);
            if (intent != FeedbackActionIntentResolver.ActionIntent.ADD && intent != FeedbackActionIntentResolver.ActionIntent.UNSPECIFIED) {
                for (String type : mentioned) if (furniture.stream().noneMatch(item -> item.getStatus() != FurnitureStatus.DELETED && type.equals(item.getType()))) {
                    issue = question("FURNITURE_NOT_FOUND", "요청의 대상 또는 기준 가구가 현재 방에 없습니다. 다른 가구를 명시해 주세요.", "request"); break;
                }
                if (issue != null) break;
                boolean selected = furniture.stream().anyMatch(item -> item.getStatus() != FurnitureStatus.DELETED && item.getId().equals(request.selectedFurnitureId()));
                boolean answered = request.clarificationSelections() != null && request.clarificationSelections().stream().anyMatch(answer ->
                        answer.requiredField().equals("targetFurnitureId") && answer.feedback().equals(request.feedback()) && answer.revision().equals(request.revision())
                        && furniture.stream().anyMatch(item -> item.getStatus() != FurnitureStatus.DELETED && item.getId().equals(answer.furnitureId())));
                if (mentioned.isEmpty() && !selected && !answered && forbidden.values().stream().anyMatch(bans -> bans.stream().anyMatch(banned -> actionMatches(clause, banned)))) {
                    issue = question("CONFLICTING_REQUIREMENTS", "유지·금지 조건과 변경 요청이 충돌합니다. 유지할지 변경할지 명시해 주세요.", "request"); break;
                }
                if ((mentioned.isEmpty() || clause.matches("^(?:그리고)?(?:이거|저거|그거|이가구|저가구|그가구).*$")) && !selected && !answered) {
                    issue = question("TARGET_NOT_SPECIFIED", "어떤 가구를 변경할까요? 가구를 선택하거나 이름을 명시해 주세요.", "targetFurnitureId"); break;
                }
                for (Furniture item : furniture) if (mentioned.contains(item.getType()) || (mentioned.isEmpty() && (selected ? item.getId().equals(request.selectedFurnitureId()) : forbidden.containsKey(item.getId())))) {
                    for (FeedbackOperationType banned : forbidden.getOrDefault(item.getId(), Set.of())) if (actionMatches(clause, banned)) {
                        issue = question("CONFLICTING_REQUIREMENTS", "유지·금지 조건과 변경 요청이 충돌합니다. 유지할지 변경할지 명시해 주세요.", "request"); break;
                    }
                    if (issue != null) break;
                }
                if (issue != null) break;
            }
            if (LENGTH.matcher(clause).find()) {
                issue = failure("UNSUPPORTED_DISTANCE", "명시한 거리·치수는 아직 지원하지 않습니다. 요청값을 다른 값으로 근사하지 않고 현재 배치를 유지합니다."); break;
            }
            var angle = ANGLE.matcher(clause);
            while (angle.find()) {
                double value = Double.parseDouble(angle.group(1));
                if (value != 90 && value != 180) { issue = failure("UNSUPPORTED_ANGLE", "회전은 현재 90°·180°만 지원합니다. 요청한 각도를 다른 각도로 근사하지 않습니다."); break; }
                boolean ccw = clause.contains("반시계") || clause.contains("시계반대");
                boolean cw = !ccw && clause.contains("시계방향");
                if (value == 90 && !cw && !ccw) { issue = question("AMBIGUOUS_ROTATION", "90° 회전의 시계 방향 또는 반시계 방향을 명시해 주세요.", "action"); break; }
                if (clause.substring(angle.end()).startsWith("로") && !cw && !ccw) { issue = question("AMBIGUOUS_ROTATION", "현재 방향에서 얼마나 회전할지 명시해 주세요. 절대 방향 각도는 아직 지원하지 않습니다.", "action"); break; }
                FeedbackOrientation expected = value == 180 ? FeedbackOrientation.HALF_TURN
                        : ccw ? FeedbackOrientation.QUARTER_TURN_CCW : FeedbackOrientation.QUARTER_TURN_CW;
                Set<String> rotationTypes = types(clause);
                for (String type : rotationTypes.isEmpty() ? Set.of("*") : rotationTypes) {
                    if (rotations.containsKey(type) && rotations.get(type) != expected) {
                        issue = question("AMBIGUOUS_ROTATION", "같은 가구에 서로 다른 회전 조건이 있습니다. 가구별 회전을 명시해 주세요.", "action"); break;
                    }
                    rotations.put(type, expected);
                }
                angles++;
            }
            String remaining = ANGLE.matcher(clause).replaceAll("").replaceAll("\\d{4}년(?:\\d{1,2}월)?(?:\\d{1,2}일)?", "");
            for (Furniture item : furniture) remaining = remaining.replace(compact(item.getLabel()), "");
            if (issue == null && Pattern.compile("\\d|[일이삼사오육칠팔구십백천]+도").matcher(remaining).find())
                issue = question("UNSPECIFIED_NUMBER", "수치의 단위와 의미를 명시해 주세요. 정확한 거리·치수 입력은 아직 지원하지 않습니다.", "request");
            if (issue != null) break;
        }
        if (issue == null && positive.stream().filter(clause -> FeedbackActionIntentResolver.resolveFurnitureActionIntent(clause) != FeedbackActionIntentResolver.ActionIntent.UNSPECIFIED).count() > 4)
            issue = failure("TOO_MANY_OPERATIONS", "한 요청에서는 최대 4개의 변경만 처리할 수 있습니다. 요청을 나눠 주세요.");
        if (issue == null && !positive.isEmpty() && positive.stream().allMatch(clause -> FeedbackActionIntentResolver.resolveFurnitureActionIntent(clause) == FeedbackActionIntentResolver.ActionIntent.UNSPECIFIED))
            issue = failure("UNSUPPORTED_OPERATION", "구체적인 가구 이동·회전·교체·추가·삭제를 요청해 주세요. 전체 동선 개선과 일반 대화는 아직 지원하지 않습니다.");
        if (issue == null && positive.isEmpty()) issue = question("NO_CHANGE_REQUEST", "금지·유지 조건에 따라 현재 배치를 유지합니다. 추가 변경이 필요하면 원하는 작업을 명시해 주세요.", "request");
        return new Rules(preserve, forbidden, noAdd, List.copyOf(positive), Map.copyOf(rotations), angles, issue);
    }

    private static String normalizeAngles(String clause) {
        return clause.replace("백팔십도", "180도").replace("구십도", "90도").replace("사십오도", "45도").replace("이백칠십도", "270도");
    }

    private static List<Furniture> targets(FeedbackTargetSelector selector, List<Furniture> furniture) {
        return furniture.stream().filter(item -> item.getStatus() != FurnitureStatus.DELETED)
                .filter(item -> selector.furnitureId().isBlank() ? selector.furnitureType().isBlank() || item.getType().equals(selector.furnitureType())
                        : item.getId().equals(selector.furnitureId())).toList();
    }
    private static Set<String> types(String text) {
        Set<String> result = new LinkedHashSet<>();
        String remaining = text;
        for (var entry : FeedbackVocabularyNormalizer.aliasesByLength()) {
            String alias = compact(entry.getKey()).replace("_", "");
            if (remaining.contains(alias)) { result.add(entry.getValue()); remaining = remaining.replace(alias, ""); }
        }
        return result;
    }
    private static boolean actionMatches(String text, FeedbackOperationType type) {
        var action = FeedbackActionIntentResolver.resolveFurnitureActionIntent(text);
        return switch (type) {
            case MOVE -> action == FeedbackActionIntentResolver.ActionIntent.MOVE;
            case ROTATE -> action == FeedbackActionIntentResolver.ActionIntent.ROTATE;
            case REMOVE_FURNITURE -> action == FeedbackActionIntentResolver.ActionIntent.REMOVE;
            case ADD_FURNITURE -> action == FeedbackActionIntentResolver.ActionIntent.ADD;
            case REPLACE_PRODUCT, SWAP_FURNITURE -> action == FeedbackActionIntentResolver.ActionIntent.REPLACE || action == FeedbackActionIntentResolver.ActionIntent.SWAP;
            default -> false;
        };
    }
    static boolean hasPositiveCreation(String text, String type) {
        return Arrays.stream(CLAUSE.split(compact(text))).anyMatch(clause -> !UNKNOWN_NEGATIVE.matcher(clause).find()
                && FeedbackActionIntentResolver.hasExplicitFurnitureCreationIntent(clause)
                && (types(clause).isEmpty() || types(clause).contains(type)));
    }
    static String compact(String text) { return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replace('−', '-').replaceAll("\\s+", ""); }
    private static Issue failure(String code, String message) { return new Issue(FeedbackStatus.FAILED, code, message, null); }
    private static Issue question(String code, String message, String field) { return new Issue(FeedbackStatus.NEEDS_CLARIFICATION, code, message, field); }
}
