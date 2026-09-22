package com.roomfit.placement;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "배치 검증 결과. 프론트 체크리스트와 경고 표시용으로 사용합니다.")
public class ValidationResult {

    @Schema(description = "가구 간 충돌이 없는지 여부", example = "true")
    private final boolean collisionFree;
    @Schema(description = "모든 가구가 방 경계 안에 있는지 여부", example = "true")
    private final boolean boundaryValid;
    @Schema(description = "문 앞 여유 공간 확보 여부", example = "true")
    private final boolean doorClearance;
    @Schema(description = "창문 영역을 과도하게 가리지 않는지 여부", example = "true")
    private final boolean windowClearance;
    @Schema(description = "주요 이동 동선 확보 여부", example = "true")
    private final boolean pathSecured;
    @Schema(description = "프론트 체크리스트 UI용 검증 항목")
    private final List<ValidationItem> validationItems;
    @Schema(description = "검증 경고 메시지 목록")
    private final List<String> warnings;
    @Schema(description = "가구별 검증 이슈(충돌/경계/문/창/동선/작동공간). 3D 뷰의 빨강(ERROR)/주황(WARNING) 표시에 사용합니다.")
    private final List<ValidationIssue> issues;

    public ValidationResult(boolean collisionFree, boolean boundaryValid, boolean doorClearance,
                             boolean windowClearance, boolean pathSecured,
                             List<ValidationItem> validationItems, List<String> warnings) {
        this(collisionFree, boundaryValid, doorClearance, windowClearance, pathSecured,
                validationItems, warnings, List.of());
    }

    public ValidationResult(boolean collisionFree, boolean boundaryValid, boolean doorClearance,
                             boolean windowClearance, boolean pathSecured,
                             List<ValidationItem> validationItems, List<String> warnings,
                             List<ValidationIssue> issues) {
        this.collisionFree = collisionFree;
        this.boundaryValid = boundaryValid;
        this.doorClearance = doorClearance;
        this.windowClearance = windowClearance;
        this.pathSecured = pathSecured;
        this.validationItems = List.copyOf(validationItems);
        this.warnings = warnings;
        this.issues = List.copyOf(issues);
    }

    public boolean isCollisionFree() {
        return collisionFree;
    }

    public boolean isBoundaryValid() {
        return boundaryValid;
    }

    public boolean isDoorClearance() {
        return doorClearance;
    }

    public boolean isWindowClearance() {
        return windowClearance;
    }

    public boolean isPathSecured() {
        return pathSecured;
    }

    public List<ValidationItem> getValidationItems() {
        return validationItems;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public List<ValidationIssue> getIssues() {
        return issues;
    }
}
