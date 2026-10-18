package com.roomfit.room;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FixturesTest {

    private static Furniture item(String id, String type, double x) {
        return new Furniture(id, type, id, 1, 0.6, 0.9, new Position(x, 1), 0, FurnitureStatus.EXISTING);
    }

    @Test
    void pin_restoresMovedFixtureAndReaddsDeletedOne() {
        Furniture sink = item("sink-1", "sink", 1.0);
        Furniture cabinet = item("cabinet-1", "upper_cabinet", 1.0).withMount(FurnitureMount.WALL, 1.45);
        Furniture bed = item("bed-1", "bed", 2.0);

        List<Furniture> result = Fixtures.pin(List.of(sink, cabinet, bed),
                List.of(item("sink-1", "sink", 2.5), item("bed-1", "bed", 3.0)));

        assertThat(result).extracting(Furniture::getId).containsExactly("sink-1", "bed-1", "cabinet-1");
        assertThat(result.get(0).getPosition().getX()).isEqualTo(1.0);
        assertThat(result.get(1).getPosition().getX()).isEqualTo(3.0);
        assertThat(result.get(2).getMount()).isEqualTo(FurnitureMount.WALL);
        assertThat(result.get(2).getElevation()).isEqualTo(1.45);
    }

    @Test
    void pin_dropsFixturesTheEngineInvented() {
        List<Furniture> result = Fixtures.pin(List.of(), List.of(item("stove-x", "stove", 1.0)));
        assertThat(result).isEmpty();
    }

    @Test
    void fixtureType_acceptsLooseSpelling() {
        assertThat(FixtureType.fromCode("Washer-Dryer")).contains(FixtureType.WASHER_DRYER);
        assertThat(FixtureType.isFixture("bed")).isFalse();
    }

    @Test
    void floorMount_dropsElevation() {
        Furniture desk = item("desk-1", "desk", 1.0).withMount(FurnitureMount.FLOOR, 1.2);
        assertThat(desk.getMount()).isNull();
        assertThat(desk.getElevation()).isNull();
    }
}
