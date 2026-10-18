package com.roomfit.room;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.roomfit.common.VariantIdValidator;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import java.util.List;

/**
 * 가구 도메인 모델.
 * width/depth/height: 전체 길이(full extent) 기준 — Three.js BoxGeometry 인자와 동일 기준.
 * rotation: y축 기준 회전, 단위 degree.
 */
@Embeddable
@Schema(description = "방 안의 가구. width/depth/height는 meter, position은 x-z 평면 중심 좌표입니다.")
public class Furniture {

    @Schema(description = "가구 ID", example = "desk-rec-1")
    private String id;
    @Schema(description = "가구 타입. 기존 6종 alias와 JSON Catalog의 canonical type을 지원합니다.", example = "desk")
    private String type;      // bed, desk, chair, storage 등
    @Schema(description = "화면 표시 라벨", example = "화이트 미니멀 책상")
    private String label;
    @Schema(description = "가구 가로 길이(meter)", example = "1.2")
    private double width;
    @Schema(description = "가구 깊이(meter)", example = "0.6")
    private double depth;
    @Schema(description = "가구 높이(meter)", example = "0.72")
    private double height;
    @Embedded
    @Schema(description = "x-z 평면에서의 가구 중심 좌표")
    private Position position;
    @Schema(description = "degree 단위 회전 각도", example = "0")
    private double rotation;  // degree
    @Enumerated(EnumType.STRING)
    @Schema(description = "가구 상태. DELETED는 렌더링/검증에서 제외하는 것이 자연스럽습니다.", example = "RECOMMENDED")
    private FurnitureStatus status;
    @Schema(description = "선택 제품 기반 추천인 경우 제품 ID. 기존 가구는 null 허용", example = "desk-01", nullable = true)
    private String productId;
    @Column(name = "variant_id", nullable = true)
    @Schema(description = "JSON 기반 Furniture Variant Registry 식별자. null 또는 미등록 값이면 프론트는 기존 Renderer를 사용합니다.",
            example = "desk-compact", pattern = "^[a-z0-9]+(?:-[a-z0-9]+)*$", nullable = true)
    private String variantId;
    @Convert(converter = StringListConverter.class)
    @Column(length = 1000)
    @Schema(description = "추천/스타일 계산에 사용되는 태그. 기존 가구는 빈 배열 허용")
    private List<String> styleTags = List.of();
    @Enumerated(EnumType.STRING)
    @Column(name = "mount", nullable = true)
    @Schema(description = "설치 방식. null이면 FLOOR. WALL이면 elevation만큼 바닥에서 떠 있습니다.", example = "WALL", nullable = true)
    private FurnitureMount mount;
    @Column(name = "elevation", nullable = true)
    @Schema(description = "바닥에서 가구 밑면까지 높이(meter). mount=WALL일 때만 의미가 있습니다.", example = "1.45", nullable = true)
    private Double elevation;

    protected Furniture() {
        // JSON 역직렬화용
    }

    public Furniture(String id, String type, String label, double width, double depth, double height,
                      Position position, double rotation, FurnitureStatus status) {
        this(id, type, label, width, depth, height, position, rotation, status, null, List.of(), null);
    }

    public Furniture(String id, String type, String label, double width, double depth, double height,
                      Position position, double rotation, FurnitureStatus status,
                      String productId, List<String> styleTags) {
        this(id, type, label, width, depth, height, position, rotation, status,
                productId, styleTags, null);
    }

    public Furniture(String id, String type, String label, double width, double depth, double height,
                     Position position, double rotation, FurnitureStatus status,
                     String productId, List<String> styleTags, String variantId) {
        this.id = id;
        this.type = type;
        this.label = label;
        this.width = width;
        this.depth = depth;
        this.height = height;
        this.position = position;
        this.rotation = rotation;
        this.status = status;
        this.productId = productId;
        this.styleTags = styleTags == null ? List.of() : List.copyOf(styleTags);
        this.variantId = VariantIdValidator.validateNullable(variantId);
    }

    public String getId() {
        return id;
    }

    public String getType() {
        return type;
    }

    public String getLabel() {
        return label;
    }

    public double getWidth() {
        return width;
    }

    public double getDepth() {
        return depth;
    }

    public double getHeight() {
        return height;
    }

    public Position getPosition() {
        return position;
    }

    public void setPosition(Position position) {
        this.position = position;
    }

    public double getRotation() {
        return rotation;
    }

    public void setRotation(double rotation) {
        this.rotation = rotation;
    }

    public FurnitureStatus getStatus() {
        return status;
    }

    public void setStatus(FurnitureStatus status) {
        this.status = status;
    }

    public String getProductId() {
        return productId;
    }

    public String getVariantId() {
        return variantId;
    }

    public List<String> getStyleTags() {
        return styleTags;
    }

    public FurnitureMount getMount() {
        return mount;
    }

    public Double getElevation() {
        return elevation;
    }

    /** mount/elevation을 설정하고 자기 자신을 반환한다. WALL이 아니면 elevation은 버린다. */
    public Furniture withMount(FurnitureMount mount, Double elevation) {
        this.mount = mount == FurnitureMount.FLOOR ? null : mount;
        this.elevation = mount == FurnitureMount.WALL ? elevation : null;
        return this;
    }

    /** 위치/크기만 바꾼 복사본을 만들 때 원본의 설치 방식을 이어받는다. */
    public Furniture copyMountFrom(Furniture source) {
        return source == null ? this : withMount(source.mount, source.elevation);
    }

    @JsonIgnore
    public boolean isWallMounted() {
        return mount == FurnitureMount.WALL;
    }

    /** 싱크대·하부장 같은 옮길 수 없는 붙박이 설비인지. 냉장고처럼 옮길 수 있는 가전은 false. */
    @JsonIgnore
    public boolean isImmovableFixture() {
        return FixtureType.isImmovable(type);
    }
}
