package com.roomfit.room;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * 문/창문 도메인 모델.
 * wall: 레거시 north/south/east/west 리터럴 또는 Room.walls[]의 wall id 참조.
 * offset: 해당 벽 시작점(Wall.start) 기준 벽을 따라 떨어진 거리(meter).
 * 비정형(직사각형이 아닌) 방은 스캔된 벽이 4개보다 많거나 어느 방향에도
 * 깔끔히 대응되지 않을 수 있어, wall 필드는 4방향 리터럴 대신 실제
 * Room.walls[].id를 참조하는 것을 표준으로 한다 — 레거시 리터럴은 walls가
 * 비어 있던 과거 업로드/샘플과의 하위 호환을 위해 계속 허용된다.
 */
@Embeddable
@Schema(description = "방의 문/창문 정보")
public class Opening {

    @Schema(description = "문/창문 ID", example = "door-1")
    private String id;
    @Schema(description = "문/창문 타입", example = "door", allowableValues = {"door", "window"})
    private String type;   // door, window
    @Schema(description = "이 문/창문이 위치한 벽. Room.walls[].id를 참조하는 것이 표준이며, "
            + "레거시 north/south/east/west 리터럴도 하위 호환을 위해 계속 허용된다.",
            example = "wall-2")
    private String wall;
    // "offset"은 H2/PostgreSQL 예약어라 컬럼명으로 그대로 쓸 수 없어 opening_offset으로 매핑.
    @Column(name = "opening_offset")
    @Schema(description = "해당 벽 시작점 기준 offset(meter)", example = "0.7")
    private double offset;
    @Schema(description = "폭(meter)", example = "0.8")
    private double width;
    @Schema(description = "높이(meter)", example = "2.1")
    private double height;
    @Schema(description = "창문 하단 높이(meter). door는 null", example = "0.9", nullable = true)
    private Double sillHeight; // window 전용, door는 null

    protected Opening() {
        // JSON 역직렬화용
    }

    public Opening(String id, String type, String wall, double offset, double width, double height, Double sillHeight) {
        this.id = id;
        this.type = type;
        this.wall = wall;
        this.offset = offset;
        this.width = width;
        this.height = height;
        this.sillHeight = sillHeight;
    }

    public String getId() {
        return id;
    }

    public String getType() {
        return type;
    }

    public String getWall() {
        return wall;
    }

    public double getOffset() {
        return offset;
    }

    public double getWidth() {
        return width;
    }

    public double getHeight() {
        return height;
    }

    public Double getSillHeight() {
        return sillHeight;
    }
}
