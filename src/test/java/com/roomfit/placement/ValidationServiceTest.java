package com.roomfit.placement;

import com.roomfit.room.Furniture;
import com.roomfit.room.FurnitureBoundary;
import com.roomfit.room.FurnitureStatus;
import com.roomfit.room.Opening;
import com.roomfit.room.Position;
import com.roomfit.room.Room;
import com.roomfit.agent.domain.AgentContext;
import com.roomfit.agent.domain.DesignStyle;
import com.roomfit.agent.domain.LifestyleGoal;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ValidationServiceTest {

    private final ValidationService validationService = new ValidationService();
    private final Room room = new Room(null, 3.2, 4.5, 2.4, "meter", List.of(), List.of());

    @Test
    void validate_withRotatedDeskOutsideBoundary_returnsBoundaryInvalid() {
        ValidationResult result = validationService.validate(room, List.of(rotatedDeskAt(0.4)));

        assertThat(result.isBoundaryValid()).isFalse();
    }

    @Test
    void validate_withRotatedDeskInsideBoundary_returnsBoundaryValid() {
        ValidationResult result = validationService.validate(room, List.of(rotatedDeskAt(0.78)));

        assertThat(result.isBoundaryValid()).isTrue();
    }

    @Test
    void validate_withCenterInsideButRotatedCornerOutside_returnsBoundaryInvalid() {
        Furniture diagonal = new Furniture("desk-1", "desk", "desk", 2.0, 1.0, 0.72,
                new Position(1.05, 1.05), 45.0, FurnitureStatus.EXISTING);

        assertThat(validationService.validate(room, List.of(diagonal)).isBoundaryValid()).isFalse();
    }

    @Test
    void validate_requiresWallClearance() {
        Furniture touching = new Furniture("desk-1", "desk", "desk", 1.0, 0.5, 0.72,
                new Position(0.5, 1.0), 0.0, FurnitureStatus.EXISTING);
        Furniture safe = new Furniture("desk-1", "desk", "desk", 1.0, 0.5, 0.72,
                new Position(0.5 + FurnitureBoundary.DEFAULT_WALL_THICKNESS_METERS / 2.0
                        + FurnitureBoundary.WALL_CLEARANCE_METERS, 1.0),
                0.0, FurnitureStatus.EXISTING);

        assertThat(validationService.validate(room, List.of(touching)).isBoundaryValid()).isFalse();
        assertThat(validationService.validate(room, List.of(safe)).isBoundaryValid()).isTrue();
    }

    @Test
    void validate_withFurnitureLargerThanRoom_returnsBoundaryInvalid() {
        Furniture oversized = new Furniture("bed-1", "bed", "bed", 3.2, 1.0, 0.5,
                new Position(1.6, 2.0), 0.0, FurnitureStatus.EXISTING);

        assertThat(validationService.validate(room, List.of(oversized)).isBoundaryValid()).isFalse();
    }

    @Test
    void validate_commonRotationFixtures_matchRectangularBoundaryPolicy() {
        for (double rotation : List.of(0.0, 45.0, 90.0, 135.0, 180.0, 270.0)) {
            FurnitureBoundary.Footprint footprint = FurnitureBoundary.footprint(2.0, 1.0, rotation);
            FurnitureBoundary.UsableBounds usable = FurnitureBoundary.usableBounds(room).orElseThrow();
            Position safeCorner = new Position(
                    usable.minX() - footprint.minX(),
                    usable.minZ() - footprint.minZ());
            Furniture furniture = new Furniture("fixture-" + rotation, "desk", "desk", 2.0, 1.0, 0.72,
                    safeCorner, rotation, FurnitureStatus.EXISTING);

            assertThat(validationService.validate(room, List.of(furniture)).isBoundaryValid())
                    .as("rotation %s", rotation)
                    .isTrue();
        }
    }

    @Test
    void validate_withRotationAwareFootprintsOverlapping_returnsCollision() {
        // Types deliberately avoid desk/desk_chair — that pair is now a full
        // collision exemption (see isCollisionExempt_deskAndDeskChairPairIsNeverFlagged
        // below), which would defeat this test's actual purpose of checking
        // rotation-aware footprint overlap.
        Furniture desk = new Furniture("desk-1", "desk", "desk", 1.4, 0.5, 0.72,
                new Position(1.0, 1.0), 90.0, FurnitureStatus.EXISTING);
        Furniture bookshelf = new Furniture("bookshelf-1", "bookshelf", "bookshelf", 0.2, 0.2, 0.8,
                new Position(1.0, 1.6), 0.0, FurnitureStatus.EXISTING);

        ValidationResult result = validationService.validate(room, List.of(desk, bookshelf));

        assertThat(result.isCollisionFree()).isFalse();
    }

    @Test
    void strictDeskMonitorStackIsCollisionFree() {
        Furniture desk = furniture("desk-1", "desk", 1.2, 0.7, 2.0, 2.0);
        Furniture monitor = furniture("monitor-1", "monitor", 0.5, 0.2, 2.0, 2.0);

        assertThat(validationService.validate(room, List.of(desk, monitor)).isCollisionFree()).isTrue();
    }

    @Test
    void monitorWithinDeskTopFootprintRemainsCollisionFree() {
        Furniture desk = furniture("desk-1", "desk", 1.2, 0.7, 2.0, 2.0);
        Furniture monitor = furniture("monitor-1", "monitor", 0.5, 0.2, 2.01, 2.0);

        assertThat(validationService.validate(room, List.of(desk, monitor)).isCollisionFree()).isTrue();
    }

    @Test
    void monitorOutsideDeskTopFootprintRemainsCollision() {
        Furniture desk = furniture("desk-1", "desk", 1.2, 0.7, 2.0, 2.0);
        Furniture monitor = furniture("monitor-1", "monitor", 0.5, 0.2, 2.61, 2.0);

        assertThat(validationService.validate(room, List.of(desk, monitor)).isCollisionFree()).isFalse();
    }

    @Test
    void widerMonitorCenteredOnDeskIsCollisionFree() {
        Furniture desk = furniture("desk-1", "desk", 1.2, 0.7, 2.0, 2.0);
        Furniture monitor = furniture("monitor-1", "monitor", 1.3, 0.2, 2.0, 2.0);

        assertThat(validationService.validate(room, List.of(desk, monitor)).isCollisionFree()).isTrue();
    }

    @Test
    void blindAttachedToItsWindowIsExcludedFromThatWindowClearance() {
        Room windowRoom = roomWithNorthWindow();
        Furniture blind = blindAt(1.5, northWallCenterZ(windowRoom, 180), 180);

        ValidationResult validation = validationService.validate(windowRoom, List.of(blind));

        assertThat(validation.isBoundaryValid()).isTrue();
        assertThat(validation.isWindowClearance()).isTrue();
    }

    @Test
    void blindNotAttachedToCurrentWindowStillBlocksItsClearance() {
        Room windowRoom = roomWithNorthWindow();
        double northWallZ = northWallCenterZ(windowRoom, 180);
        List<Furniture> unattached = List.of(
                blindAt(1.7, northWallZ, 180),
                blindAt(1.5, northWallZ - 0.1, 180),
                blindAt(1.5, northWallZ, 0)
        );

        for (Furniture blind : unattached) {
            ValidationResult validation = validationService.validate(windowRoom, List.of(blind));

            assertThat(validation.isBoundaryValid()).as("blind %s", blind.getId()).isTrue();
            assertThat(validation.isWindowClearance()).as("blind %s", blind.getId()).isFalse();
        }
    }

    @Test
    void rugCanOverlapBedSofaAndDeskWithoutFurnitureCollision() {
        for (String type : List.of("bed", "sofa", "desk")) {
            Furniture furniture = furniture(type + "-1", type, 1.2, 1.2, 2.0, 2.0);
            Furniture rug = furniture("rug-1", "RUG", 1.8, 1.8, 2.0, 2.0);

            assertThat(validationService.validate(room, List.of(furniture, rug)).isCollisionFree())
                    .as("rug overlapping %s", type)
                    .isTrue();
        }
    }

    @Test
    void rugDoesNotBlockMovementPathOrReduceCollisionScore() {
        Furniture rug = furniture("rug-1", "rug", 2.0, 2.0, 1.6, 2.2);

        ValidationResult validation = validationService.validate(room, List.of(rug));
        AgentContext context = new AgentContext(1L, LifestyleGoal.RELAX_FOCUSED,
                List.of(DesignStyle.MINIMAL), List.of(), List.of(), List.of(), List.of(), List.of());
        ScoreSummary score = new ScoreService().calculate(context, List.of(rug), validation);

        assertThat(validation.isPathSecured()).isTrue();
        assertThat(validation.isCollisionFree()).isTrue();
        assertThat(score.getCollisionScore()).isEqualTo(100);
    }

    @Test
    void rugOutsideRoomBoundaryIsStillRejected() {
        Furniture rug = furniture("rug-1", "rug", 1.8, 1.8, 0.5, 0.5);

        assertThat(validationService.validate(room, List.of(rug)).isBoundaryValid()).isFalse();
    }

    @Test
    void rugDoesNotHideCollisionBetweenTwoOtherFurnitureItems() {
        Furniture bed = furniture("bed-1", "bed", 1.5, 1.5, 2.0, 2.0);
        Furniture sofa = furniture("sofa-1", "sofa", 1.5, 1.5, 2.1, 2.0);
        Furniture rug = furniture("rug-1", "rug", 2.0, 2.0, 2.0, 2.0);

        assertThat(validationService.validate(room, List.of(bed, sofa, rug)).isCollisionFree()).isFalse();
    }

    @Test
    void rugIsExcludedFromDoorAndWindowClearanceButStillBoundaryChecked() {
        Room roomWithOpenings = new Room(null, 3.2, 4.5, 2.4, "meter", List.of(
                new com.roomfit.room.Opening("door-1", "door", "south", 1.0, 0.9, 2.0, null),
                new com.roomfit.room.Opening("window-1", "window", "north", 1.0, 1.0, 1.0, 0.8)
        ), List.of());
        Furniture rugAtDoor = furniture("rug-door", "rug", 0.8, 0.6, 1.4, 0.55);
        Furniture rugAtWindow = furniture("rug-window", "rug", 0.8, 0.2, 1.4, 4.15);

        ValidationResult validation = validationService.validate(roomWithOpenings, List.of(rugAtDoor, rugAtWindow));

        assertThat(validation.isBoundaryValid()).isTrue();
        assertThat(validation.isDoorClearance()).isTrue();
        assertThat(validation.isWindowClearance()).isTrue();
    }

    @Test
    void validateChange_excludesUnchangedBaselineCollision() {
        Furniture first = furniture("existing-1", "desk", 1.0, 1.0, 1.2, 1.2);
        Furniture second = furniture("existing-2", "sofa", 1.0, 1.0, 1.3, 1.2);
        Furniture added = furniture("new-chair", "desk_chair", 0.5, 0.5, 2.7, 3.6);

        ValidationResult validation = validationService.validateChange(
                room, List.of(first, second), List.of(first, second, added));

        assertThat(validation.isCollisionFree()).isTrue();
        assertThat(validation.getWarnings())
                .contains("기존 가구의 선행 검증 문제는 신규 배치 평가에서 제외되었습니다.");
    }

    @Test
    void validateChange_rejectsCollisionIntroducedByAddedFurniture() {
        // desk/desk_chair avoided here too — see comment on
        // validate_withRotationAwareFootprintsOverlapping_returnsCollision above.
        Furniture existing = furniture("existing-1", "desk", 1.0, 1.0, 1.2, 1.2);
        Furniture added = furniture("new-bookshelf", "bookshelf", 0.5, 0.5, 1.2, 1.2);

        ValidationResult validation = validationService.validateChange(
                room, List.of(existing), List.of(existing, added));

        assertThat(validation.isCollisionFree()).isFalse();
    }

    private Furniture furniture(String id, String type, double width, double depth, double x, double z) {
        return new Furniture(id, type, type, width, depth, 0.5,
                new Position(x, z), 0, FurnitureStatus.RECOMMENDED);
    }

    private Room roomWithNorthWindow() {
        return new Room(null, 3.2, 4.5, 2.4, "meter",
                List.of(new Opening("window-1", "window", "north", 1.0, 1.0, 1.0, 0.8)),
                List.of());
    }

    private Furniture blindAt(double x, double z, double rotation) {
        return new Furniture("blind-" + x + "-" + z + "-" + rotation,
                "curtain_blind", "blind", 1.0, 0.1, 1.8,
                new Position(x, z), rotation, FurnitureStatus.RECOMMENDED);
    }

    private double northWallCenterZ(Room windowRoom, double rotation) {
        FurnitureBoundary.UsableBounds usable = FurnitureBoundary.usableBounds(windowRoom).orElseThrow();
        FurnitureBoundary.Footprint footprint = FurnitureBoundary.footprint(1.0, 0.1, rotation);
        return usable.maxZ() - footprint.maxZ();
    }

    private Furniture rotatedDeskAt(double z) {
        return new Furniture("desk-1", "desk", "desk", 1.4, 0.5, 0.72,
                new Position(2.7, z), 90.0, FurnitureStatus.EXISTING);
    }
}
