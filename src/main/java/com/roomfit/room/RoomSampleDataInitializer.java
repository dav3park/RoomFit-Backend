package com.roomfit.room;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 서버 기동 시 AI 추천 전 데모에 사용하는 샘플 원룸을 시딩한다.
 */
@Component
public class RoomSampleDataInitializer implements CommandLineRunner {

    static final String CANONICAL_SAMPLE_NAME = "Sample Room";
    static final String L_STUDIO_SAMPLE_NAME = "Sample Room - L Studio";
    static final String ALCOVE_STUDIO_SAMPLE_NAME = "Sample Room - Alcove Studio";
    private static final String CANONICAL_WARDROBE_ID = "wardrobe-1";
    private static final double CANONICAL_WARDROBE_X = 5.39;
    private static final double CANONICAL_WARDROBE_Z = 3.85;
    private static final double CANONICAL_WARDROBE_ROTATION = 90;

    private final RoomRepository roomRepository;

    public RoomSampleDataInitializer(RoomRepository roomRepository) {
        this.roomRepository = roomRepository;
    }

    @Override
    public void run(String... args) {
        // 이름과 geometry를 canonical key로 사용한다. SAMPLE 행이 하나라도 있다는
        // 이유로 건너뛰면 legacy sample만 남은 DB에서 canonical sample이 복구되지
        // 않는다. 반대로 canonical이 이미 있으면 재시작해도 새 행을 만들지 않는다.
        List<Room> samples = roomRepository.findBySourceOrderByIdAsc(RoomSource.SAMPLE);
        Room existingCanonical = samples.stream().filter(RoomSampleDataInitializer::isCanonicalSample)
                .findFirst().orElse(null);
        if (existingCanonical != null) {
            updateCanonicalWardrobeIfNeeded(existingCanonical);
        } else {
            roomRepository.save(buildCanonicalSample());
        }

        // 비정형(직사각형이 아닌) 방을 웹 프론트/에디터에서 눈으로 확인할 수
        // 있도록, 각각 이름만으로 식별되는 두 개의 추가 샘플을 별도로 시딩한다
        // (canonical과 달리 width/depth로 legacy 행과 헷갈릴 위험이 없으므로
        // 이름 매치만으로 충분 — isSeededExtraSample 참고).
        boolean hasLStudio = samples.stream().anyMatch(room -> L_STUDIO_SAMPLE_NAME.equals(room.getName()));
        if (!hasLStudio) {
            roomRepository.save(buildLStudioSample());
        }

        boolean hasAlcoveStudio = samples.stream().anyMatch(room -> ALCOVE_STUDIO_SAMPLE_NAME.equals(room.getName()));
        if (!hasAlcoveStudio) {
            roomRepository.save(buildAlcoveStudioSample());
        }
    }

    private Room buildCanonicalSample() {
        Opening door = new Opening("door-1", "door", "south", 4.6, 0.8, 2.1, null);
        Opening window = new Opening("window-1", "window", "north", 0.65, 2.2, 1.5, 0.7);

        Furniture bed = new Furniture("bed-1", "bed", "우드 침대", 1.45, 2.1, 0.48,
                new Position(1.35, 1.55), 0, FurnitureStatus.EXISTING);
        Furniture desk = new Furniture("desk-1", "desk", "우드 책상", 1.35, 0.6, 0.72,
                new Position(3.0, 1.05), 0, FurnitureStatus.EXISTING);
        Furniture chair = new Furniture("chair-1", "chair", "우드 의자", 0.55, 0.55, 0.82,
                new Position(3.0, 1.85), 180, FurnitureStatus.EXISTING);
        // rotation 90: width (1.2, the door-bearing face) runs parallel to the
        // east wall, depth (0.65) sticks into the room — back flush against the
        // wall, doors facing west into the room. x=5.39 puts the back edge
        // (center + depth/2 = 5.715) just inside the room's usable bound
        // (width 5.8 minus the default 0.08m wall-clearance inset = 5.72).
        Furniture wardrobe = new Furniture(CANONICAL_WARDROBE_ID, "wardrobe", "우드 옷장", 1.2, 0.65, 2.1,
                new Position(CANONICAL_WARDROBE_X, CANONICAL_WARDROBE_Z), CANONICAL_WARDROBE_ROTATION,
                FurnitureStatus.EXISTING);

        return new Room(null, 5.8, 5.4, 2.7, "meter",
                List.of(door, window), List.of(bed, desk, chair, wardrobe));
    }

    // 5.0m x 4.0m 바운딩 박스에서 북동쪽 모서리 2.0m x 1.5m를 잘라낸 L자형
    // 원룸. 둘레를 이루는 6개 벽이 코너 원점(0..width, 0..depth) 기준으로
    // 끝점끼리 이어지며(체이닝), 축정렬 사각형 4개가 아니므로
    // FurnitureBoundary.orderedPolygon이 실제 다각형 경로를 타게 된다.
    private Room buildLStudioSample() {
        List<Wall> walls = List.of(
                new Wall("l-wall-north", new Position(0, 0), new Position(3, 0), 2.7, 0.12),
                new Wall("l-wall-notch-in", new Position(3, 0), new Position(3, 1.5), 2.7, 0.1),
                new Wall("l-wall-notch-across", new Position(3, 1.5), new Position(5, 1.5), 2.7, 0.1),
                new Wall("l-wall-east", new Position(5, 1.5), new Position(5, 4), 2.7, 0.12),
                new Wall("l-wall-south", new Position(5, 4), new Position(0, 4), 2.7, 0.12),
                new Wall("l-wall-west", new Position(0, 4), new Position(0, 0), 2.7, 0.12)
        );

        Opening door = new Opening("l-door-1", "door", "l-wall-west", 1.6, 0.9, 2.1, null);
        Opening window = new Opening("l-window-1", "window", "l-wall-south", 1.0, 1.4, 1.2, 0.9);

        Furniture bed = new Furniture("l-bed-1", "bed", "우드 침대", 1.3, 2.0, 0.48,
                new Position(1.0, 2.6), 0, FurnitureStatus.EXISTING);
        Furniture desk = new Furniture("l-desk-1", "desk", "우드 책상", 1.2, 0.6, 0.72,
                new Position(2.2, 0.75), 0, FurnitureStatus.EXISTING);

        return new Room(null, L_STUDIO_SAMPLE_NAME, 5.0, 4.0, 2.7, "meter",
                walls, List.of(door, window), List.of(bed, desk),
                RoomSource.SAMPLE, LocalDateTime.now());
    }

    // 5.0m x 4.0m 바운딩 박스, 본체(x:[1,5])의 서쪽에 현관 알코브
    // (x:[0,1], z:[1.0,2.5])가 붙어 있는 원룸. 노치(오목) 대신 돌출(볼록)
    // 형태라 L자 샘플과 다른 다각형 케이스를 검증한다. 벽 8개가 둘레를 이룬다.
    private Room buildAlcoveStudioSample() {
        List<Wall> walls = List.of(
                new Wall("alcove-wall-north", new Position(1, 0), new Position(5, 0), 2.6, 0.12),
                new Wall("alcove-wall-east", new Position(5, 0), new Position(5, 4), 2.6, 0.12),
                new Wall("alcove-wall-south", new Position(5, 4), new Position(1, 4), 2.6, 0.12),
                new Wall("alcove-wall-west-lower", new Position(1, 4), new Position(1, 2.5), 2.6, 0.12),
                new Wall("alcove-wall-in-north", new Position(1, 2.5), new Position(0, 2.5), 2.6, 0.1),
                new Wall("alcove-wall-entrance", new Position(0, 2.5), new Position(0, 1.0), 2.6, 0.1),
                new Wall("alcove-wall-in-south", new Position(0, 1.0), new Position(1, 1.0), 2.6, 0.1),
                new Wall("alcove-wall-west-upper", new Position(1, 1.0), new Position(1, 0), 2.6, 0.12)
        );

        Opening door = new Opening("alcove-door-1", "door", "alcove-wall-entrance", 0.75, 0.9, 2.1, null);
        Opening window = new Opening("alcove-window-1", "window", "alcove-wall-east", 2.5, 1.4, 1.2, 0.9);

        Furniture bed = new Furniture("alcove-bed-1", "bed", "우드 침대", 1.3, 2.0, 0.48,
                new Position(3.5, 1.2), 0, FurnitureStatus.EXISTING);
        Furniture desk = new Furniture("alcove-desk-1", "desk", "우드 책상", 1.2, 0.6, 0.72,
                new Position(3.5, 3.4), 0, FurnitureStatus.EXISTING);

        return new Room(null, ALCOVE_STUDIO_SAMPLE_NAME, 5.0, 4.0, 2.6, "meter",
                walls, List.of(door, window), List.of(bed, desk),
                RoomSource.SAMPLE, LocalDateTime.now());
    }

    private void updateCanonicalWardrobeIfNeeded(Room sample) {
        Furniture wardrobe = sample.getFurniture().stream()
                .filter(item -> CANONICAL_WARDROBE_ID.equals(item.getId()))
                .findFirst().orElse(null);
        if (wardrobe == null || wardrobe.getPosition() == null) return;
        boolean positionChanged = Double.compare(wardrobe.getPosition().getX(), CANONICAL_WARDROBE_X) != 0
                || Double.compare(wardrobe.getPosition().getZ(), CANONICAL_WARDROBE_Z) != 0;
        boolean rotationChanged = Double.compare(wardrobe.getRotation(), CANONICAL_WARDROBE_ROTATION) != 0;
        if (!positionChanged && !rotationChanged) return;

        if (positionChanged) wardrobe.setPosition(new Position(CANONICAL_WARDROBE_X, CANONICAL_WARDROBE_Z));
        if (rotationChanged) wardrobe.setRotation(CANONICAL_WARDROBE_ROTATION);
        roomRepository.save(sample);
    }

    static boolean isCanonicalSample(Room room) {
        return room.getSource() == RoomSource.SAMPLE
                && CANONICAL_SAMPLE_NAME.equals(room.getName())
                && Double.compare(room.getWidth(), 5.8) == 0
                && Double.compare(room.getDepth(), 5.4) == 0;
    }

    // 새로 추가된 두 샘플은 (canonical과 달리) 7-인자 편의 생성자의 기본
    // 이름과 겹칠 위험이 없는 고유한 이름으로만 생성되므로, width/depth까지
    // 확인할 필요 없이 이름 매치만으로 안전하게 식별할 수 있다.
    static boolean isSeededExtraSample(Room room) {
        return room.getSource() == RoomSource.SAMPLE
                && (L_STUDIO_SAMPLE_NAME.equals(room.getName())
                        || ALCOVE_STUDIO_SAMPLE_NAME.equals(room.getName()));
    }
}
