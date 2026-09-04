package com.roomfit.room.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "RoomPlan iOS App 또는 Mock/Manual 입력에서 생성한 RoomFit JSON 업로드 요청")
public class RoomUploadRequest {

    @Schema(description = "업로드 요청에서 전달할 수 있는 방 이름. 생략하면 백엔드가 방 표시 이름을 부여합니다.", example = "RoomPlan Scan Room")
    private String name;
    @Schema(description = "필수 방 크기 정보")
    private RoomData room;
    @Schema(description = "실제로 스캔된 벽 세그먼트 목록. 빈 배열/생략 허용 (없으면 width/depth 사각형으로 대체됨)")
    private List<WallData> walls;
    @Schema(description = "문/창문 목록. 빈 배열 허용")
    private List<OpeningData> openings;
    @Schema(description = "기존 가구 목록. 빈 배열 허용")
    private List<FurnitureData> furniture;
    // `thumbnailBase64` used to be accepted here. The iOS app may still send it;
    // Spring Boot's Jackson defaults ignore unknown properties, so those uploads
    // keep working unchanged — the value is simply dropped instead of stored.

    protected RoomUploadRequest() {
        // JSON 역직렬화용
    }

    public String getName() {
        return name;
    }

    public RoomData getRoom() {
        return room;
    }

    public List<WallData> getWalls() {
        return walls;
    }

    public List<OpeningData> getOpenings() {
        return openings;
    }

    public List<FurnitureData> getFurniture() {
        return furniture;
    }

    @Schema(description = "업로드 방 크기. width/depth/height는 필수이며 meter 단위입니다.")
    public static class RoomData {
        @Schema(description = "방 가로 길이(meter)", example = "3.2")
        private Double width;
        @Schema(description = "방 깊이(meter)", example = "4.5")
        private Double depth;
        @Schema(description = "방 높이(meter)", example = "2.4")
        private Double height;
        @Schema(description = "단위. 없으면 meter로 처리됩니다.", example = "meter")
        private String unit;

        protected RoomData() {
            // JSON 역직렬화용
        }

        public Double getWidth() {
            return width;
        }

        public Double getDepth() {
            return depth;
        }

        public Double getHeight() {
            return height;
        }

        public String getUnit() {
            return unit;
        }
    }

    @Schema(description = "업로드 문/창문 데이터")
    public static class OpeningData {
        @Schema(description = "문/창문 ID", example = "door-1")
        private String id;
        @Schema(description = "문/창문 타입", example = "door", allowableValues = {"door", "window"})
        private String type;
        @Schema(description = "이 문/창문이 위치한 벽. `walls[].id`를 참조하는 wall id 문자열이 표준이며, "
                + "walls가 비어 있던 과거 업로드/샘플과의 하위 호환을 위해 레거시 north/south/east/west "
                + "리터럴도 계속 허용된다.", example = "wall-2")
        private String wall;
        @Schema(description = "해당 벽 시작점 기준 offset(meter)", example = "0.7")
        private Double offset;
        @Schema(description = "폭(meter)", example = "0.8")
        private Double width;
        @Schema(description = "높이(meter)", example = "2.1")
        private Double height;
        @Schema(description = "창문 하단 높이(meter). door는 null 허용", example = "0.9")
        private Double sillHeight;

        protected OpeningData() {
            // JSON 역직렬화용
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

        public Double getOffset() {
            return offset;
        }

        public Double getWidth() {
            return width;
        }

        public Double getHeight() {
            return height;
        }

        public Double getSillHeight() {
            return sillHeight;
        }
    }

    @Schema(description = "업로드 가구 데이터")
    public static class FurnitureData {
        @Schema(description = "가구 ID", example = "bed-1")
        private String id;
        @Schema(description = "가구 타입", example = "bed")
        private String type;
        @Schema(description = "화면 표시 라벨", example = "침대")
        private String label;
        @Schema(description = "가구 가로 길이(meter)", example = "1.1")
        private Double width;
        @Schema(description = "가구 깊이(meter)", example = "2.0")
        private Double depth;
        @Schema(description = "가구 높이(meter)", example = "0.45")
        private Double height;
        @Schema(description = "x-z 평면에서의 가구 중심 좌표")
        private PositionData position;
        @Schema(description = "degree 단위 회전 각도. 백엔드에서 0/90/180/270 중 가장 가까운 값으로 스냅합니다.", example = "0")
        private Double rotation;
        @Schema(description = "가구 상태. 없으면 EXISTING으로 처리됩니다.", example = "EXISTING")
        private String status;

        protected FurnitureData() {
            // JSON 역직렬화용
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

        public Double getWidth() {
            return width;
        }

        public Double getDepth() {
            return depth;
        }

        public Double getHeight() {
            return height;
        }

        public PositionData getPosition() {
            return position;
        }

        public Double getRotation() {
            return rotation;
        }

        public String getStatus() {
            return status;
        }
    }

    @Schema(description = "실제로 스캔된 벽 세그먼트 데이터. start/end는 방 코너 원점(0..width, 0..depth) 기준")
    public static class WallData {
        @Schema(description = "벽 ID", example = "wall-1")
        private String id;
        @Schema(description = "벽 시작점(방 코너 원점 기준)")
        private PositionData start;
        @Schema(description = "벽 끝점(방 코너 원점 기준)")
        private PositionData end;
        @Schema(description = "벽 높이(meter)", example = "2.4")
        private Double height;
        @Schema(description = "벽 두께(meter)", example = "0.12")
        private Double thickness;

        protected WallData() {
            // JSON 역직렬화용
        }

        public String getId() {
            return id;
        }

        public PositionData getStart() {
            return start;
        }

        public PositionData getEnd() {
            return end;
        }

        public Double getHeight() {
            return height;
        }

        public Double getThickness() {
            return thickness;
        }
    }

    @Schema(description = "x-z 평면에서의 중심 좌표")
    public static class PositionData {
        @Schema(description = "x축 중심 좌표(meter)", example = "0.8")
        private Double x;
        @Schema(description = "z축 중심 좌표(meter)", example = "1.4")
        private Double z;

        protected PositionData() {
            // JSON 역직렬화용
        }

        public Double getX() {
            return x;
        }

        public Double getZ() {
            return z;
        }
    }
}
