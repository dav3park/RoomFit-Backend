package com.roomfit.room;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression coverage for the original axis-aligned-rectangle behavior, plus
 * new coverage for the polygon path added to support non-rectangular
 * (e.g. L-shaped) rooms. See FurnitureBoundary's `orderedPolygon` javadoc:
 * a plain 4-wall axis-aligned rectangle must always route through the
 * untouched original per-side-inset arithmetic.
 */
class FurnitureBoundaryTest {

    @Test
    void isInside_rectangleRoom_acceptsCenterAndRejectsOutOfBoundsPoint() {
        Room room = rectangleRoom(3.0, 4.0, 0.12);
        FurnitureBoundary.Footprint footprint = FurnitureBoundary.footprint(0.6, 0.4, 0);

        assertThat(FurnitureBoundary.isInside(room, new Position(1.5, 2.0), footprint)).isTrue();
        assertThat(FurnitureBoundary.isInside(room, new Position(-0.5, 2.0), footprint)).isFalse();
    }

    @Test
    void clamp_rectangleRoom_pullsOutOfBoundsPositionBackInside() {
        Room room = rectangleRoom(3.0, 4.0, 0.12);
        FurnitureBoundary.Footprint footprint = FurnitureBoundary.footprint(0.6, 0.4, 0);

        Optional<Position> clamped = FurnitureBoundary.clamp(room, new Position(-1, 2.0), footprint);

        assertThat(clamped).isPresent();
        assertThat(FurnitureBoundary.isInside(room, clamped.get(), footprint)).isTrue();
    }

    @Test
    void usableBounds_rectangleRoom_insetsBySameWallClearanceAsBefore() {
        Room room = rectangleRoom(3.0, 4.0, 0.12);

        Optional<FurnitureBoundary.UsableBounds> bounds = FurnitureBoundary.usableBounds(room);

        assertThat(bounds).isPresent();
        assertThat(bounds.get().minX()).isCloseTo(0.06 + 0.02, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(bounds.get().maxX()).isCloseTo(3.0 - 0.06 - 0.02, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void isInside_lShapedRoom_rejectsPointInsideBoundingBoxButOutsideRealFootprint() {
        Room room = lShapedRoom();
        FurnitureBoundary.Footprint footprint = FurnitureBoundary.footprint(0.2, 0.2, 0);

        // (3, 0.5) sits inside the room's 4x3 bounding box but inside the
        // notch that was actually cut away (x in [2,4], z in [0,1]).
        assertThat(FurnitureBoundary.isInside(room, new Position(3.0, 0.5), footprint)).isFalse();
    }

    @Test
    void isInside_lShapedRoom_acceptsPointNearReflexCornerWithEnoughClearance() {
        Room room = lShapedRoom();
        FurnitureBoundary.Footprint footprint = FurnitureBoundary.footprint(0.2, 0.2, 0);

        assertThat(FurnitureBoundary.isInside(room, new Position(2.3, 1.3), footprint)).isTrue();
    }

    @Test
    void isInside_lShapedRoom_rejectsPointTooCloseToReflexCornerWall() {
        Room room = lShapedRoom();
        FurnitureBoundary.Footprint footprint = FurnitureBoundary.footprint(0.2, 0.2, 0);

        // Right against wall-3 ((2,1)-(4,1)) with no clearance margin.
        assertThat(FurnitureBoundary.isInside(room, new Position(3.0, 1.03), footprint)).isFalse();
    }

    @Test
    void clamp_lShapedRoom_movesNotchPositionIntoRealFootprint() {
        Room room = lShapedRoom();
        FurnitureBoundary.Footprint footprint = FurnitureBoundary.footprint(0.2, 0.2, 0);

        Optional<Position> clamped = FurnitureBoundary.clamp(room, new Position(3.0, 0.5), footprint);

        assertThat(clamped).isPresent();
        assertThat(FurnitureBoundary.isInside(room, clamped.get(), footprint)).isTrue();
    }

    @Test
    void usableBounds_lShapedRoom_returnsPolygonBoundingBoxAsSearchEnvelope() {
        Room room = lShapedRoom();

        Optional<FurnitureBoundary.UsableBounds> bounds = FurnitureBoundary.usableBounds(room);

        assertThat(bounds).isPresent();
        assertThat(bounds.get().minX()).isEqualTo(0.0);
        assertThat(bounds.get().maxX()).isEqualTo(4.0);
        assertThat(bounds.get().minZ()).isEqualTo(0.0);
        assertThat(bounds.get().maxZ()).isEqualTo(3.0);
    }

    private Room rectangleRoom(double width, double depth, double thickness) {
        List<Wall> walls = List.of(
                new Wall("wall-1", new Position(0, 0), new Position(width, 0), 2.4, thickness),
                new Wall("wall-2", new Position(width, 0), new Position(width, depth), 2.4, thickness),
                new Wall("wall-3", new Position(width, depth), new Position(0, depth), 2.4, thickness),
                new Wall("wall-4", new Position(0, depth), new Position(0, 0), 2.4, thickness)
        );
        return new Room(1L, "Rectangle Test Room", width, depth, 2.4, "meter",
                walls, List.of(), List.of(), RoomSource.SAMPLE, LocalDateTime.now());
    }

    // 4x3 bounding box with a 2x1 notch cut from the bottom-right corner:
    //   (0,0) -> (2,0) -> (2,1) -> (4,1) -> (4,3) -> (0,3) -> close
    private Room lShapedRoom() {
        double thickness = 0.1;
        List<Wall> walls = List.of(
                new Wall("wall-1", new Position(0, 0), new Position(2, 0), 2.4, thickness),
                new Wall("wall-2", new Position(2, 0), new Position(2, 1), 2.4, thickness),
                new Wall("wall-3", new Position(2, 1), new Position(4, 1), 2.4, thickness),
                new Wall("wall-4", new Position(4, 1), new Position(4, 3), 2.4, thickness),
                new Wall("wall-5", new Position(4, 3), new Position(0, 3), 2.4, thickness),
                new Wall("wall-6", new Position(0, 3), new Position(0, 0), 2.4, thickness)
        );
        return new Room(1L, "L-Shaped Test Room", 4.0, 3.0, 2.4, "meter",
                walls, List.of(), List.of(), RoomSource.SAMPLE, LocalDateTime.now());
    }
}
