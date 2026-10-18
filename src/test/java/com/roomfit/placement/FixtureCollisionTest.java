package com.roomfit.placement;

import com.roomfit.room.Fixtures;
import com.roomfit.room.Furniture;
import com.roomfit.room.FurnitureMount;
import com.roomfit.room.FurnitureStatus;
import com.roomfit.room.Position;
import com.roomfit.room.Room;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FixtureCollisionTest {

    private final ValidationService validationService = new ValidationService();
    private final Room room = new Room(null, 4.0, 4.0, 2.4, "meter", List.of(), List.of());

    private static Furniture item(String id, String type, double x, double z, double w, double d, double h) {
        return new Furniture(id, type, id, w, d, h, new Position(x, z), 0, FurnitureStatus.EXISTING);
    }

    // 스캔된 주방: 개수대가 조리대 안으로 파여 있어 싱크대 밑면(0.64)이 하부장 윗면(0.86)보다 낮다.
    private final Furniture baseCabinet = item("base-1", "base_cabinet", 1.0, 0.4, 1.4, 0.65, 0.86);
    private final Furniture sink = item("sink-1", "sink", 1.3, 0.4, 0.5, 0.4, 0.22).withMount(FurnitureMount.WALL, 0.64);
    private final Furniture stove = item("stove-1", "stove", 0.6, 0.4, 0.7, 0.5, 0.05).withMount(FurnitureMount.WALL, 0.86);

    @Test
    void builtInKitchenPieces_doNotCollideWithEachOther() {
        ValidationResult result = validationService.validate(room, List.of(baseCabinet, sink, stove));

        assertThat(result.isCollisionFree()).isTrue();
        assertThat(result.getIssues()).noneMatch(issue -> issue.type() == ValidationIssue.Type.BODY_COLLISION
                || issue.type() == ValidationIssue.Type.ZONE_INTRUSION);
    }

    @Test
    void movableRefrigerator_stillCollidesWithBaseCabinet() {
        Furniture fridge = item("fridge-1", "refrigerator", 1.5, 0.4, 0.6, 0.6, 1.1);

        ValidationResult result = validationService.validate(room, List.of(baseCabinet, fridge));

        assertThat(result.isCollisionFree()).isFalse();
    }

    @Test
    void refrigerator_isMovableButKeptOnDiscard() {
        Furniture fridge = item("fridge-1", "refrigerator", 3.0, 3.0, 0.6, 0.6, 1.1);
        Furniture moved = item("fridge-1", "refrigerator", 2.0, 3.0, 0.6, 0.6, 1.1);

        assertThat(fridge.isImmovableFixture()).isFalse();
        assertThat(Fixtures.pin(List.of(baseCabinet, fridge), List.of(baseCabinet, moved)))
                .filteredOn(f -> f.getId().equals("fridge-1"))
                .singleElement()
                .satisfies(f -> assertThat(f.getPosition().getX()).isEqualTo(2.0));
        assertThat(Fixtures.keptOnDiscard(List.of(baseCabinet, fridge, item("bed-1", "bed", 2, 2, 1, 2, 0.5))))
                .extracting(Furniture::getId).containsExactly("base-1", "fridge-1");
    }
}
