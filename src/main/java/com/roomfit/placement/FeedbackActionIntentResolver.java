package com.roomfit.placement;

import java.util.List;

/** Shared, conservative action intent policy for rule and provider feedback plans. */
final class FeedbackActionIntentResolver {

    private static final List<String> MOVE_TERMS = List.of("옮겨", "옮기", "이동", "당겨", "밀어", "앞으로", "뒤로", "붙여", "가져다");
    private static final List<String> ROTATE_TERMS = List.of(
            "회전", "돌려", "돌리", "90도", "180도", "반대로", "벽과 평행");
    private static final List<String> PLACEMENT_TERMS = List.of("배치", "넣어", "놓아", "놔", "두어");
    private static final List<String> SPATIAL_TERMS = List.of(
            "구석", "모서리", "코너", "창가", "창문", "벽", "왼쪽", "좌측", "왼편",
            "오른쪽", "우측", "오른편", "가운데", "중앙", "옆", "근처", "가까이",
            "앞으로", "뒤로");
    private static final List<String> CREATION_TERMS = List.of("추가", "하나 더", "한 개 더");
    private static final List<String> SWAP_TERMS = List.of("교체", "바꿔", "바꾸", "다른 디자인", "다른 제품");
    private static final List<String> REMOVE_TERMS = List.of("삭제", "제거", "없애", "치워", "빼", "필요 없어");

    private FeedbackActionIntentResolver() {
    }

    static ActionIntent resolveFurnitureActionIntent(String feedback) {
        if (feedback == null || feedback.isBlank()) {
            return ActionIntent.UNSPECIFIED;
        }
        if (containsAny(feedback, ROTATE_TERMS)) {
            return ActionIntent.ROTATE;
        }
        if (containsAny(feedback, SWAP_TERMS) && (feedback.contains("수납 책상")
                || containsAny(feedback, List.of("수납공간", "넓", "크게", "키워", "더 큰", "큰 제품",
                "작게", "더 작은", "작은 제품")))) {
            return ActionIntent.REPLACE;
        }
        if (containsAny(feedback, SWAP_TERMS) && !isAmbiguousSwapRequest(feedback)) {
            return ActionIntent.SWAP;
        }
        if (containsAny(feedback, REMOVE_TERMS)) {
            return ActionIntent.REMOVE;
        }
        // Movement remains the action even when it is modified by "추가로" or
        // quantified as "하나 더".
        if (containsAny(feedback, MOVE_TERMS)) {
            return ActionIntent.MOVE;
        }
        if (hasExplicitFurnitureCreationIntent(feedback)) {
            return ActionIntent.ADD;
        }
        if (containsAny(feedback, PLACEMENT_TERMS) && containsAny(feedback, SPATIAL_TERMS)) {
            return ActionIntent.MOVE;
        }
        return ActionIntent.UNSPECIFIED;
    }

    static boolean hasExplicitFurnitureCreationIntent(String feedback) {
        if (feedback == null || feedback.isBlank()) {
            return false;
        }
        String compact = feedback.replaceAll("\\s+", "");
        if (compact.contains("추가로") || hasMoveAfter(compact, "하나더") || hasMoveAfter(compact, "한개더")) {
            return false;
        }
        return containsAny(feedback, CREATION_TERMS);
    }

    private static boolean hasMoveAfter(String feedback, String quantifiedCreationTerm) {
        int index = feedback.indexOf(quantifiedCreationTerm);
        return index >= 0 && containsAny(feedback.substring(index + quantifiedCreationTerm.length()), MOVE_TERMS);
    }

    private static boolean isAmbiguousSwapRequest(String feedback) {
        return feedback.contains("좀")
                && !containsAny(feedback, List.of("다른 디자인", "다른 제품", "작은", "큰", "슬림", "수납"))
                && !FeedbackMetadataKeywordNormalizer.containsMetadataRequest(feedback);
    }

    private static boolean containsAny(String feedback, List<String> terms) {
        return terms.stream().anyMatch(feedback::contains);
    }

    enum ActionIntent {
        ADD,
        MOVE,
        ROTATE,
        SWAP,
        REPLACE,
        REMOVE,
        UNSPECIFIED
    }
}
