package com.roomfit.placement;

import com.roomfit.room.Furniture;
import com.roomfit.room.FurnitureStatus;
import com.roomfit.room.Opening;
import com.roomfit.room.Position;
import com.roomfit.room.Room;
import com.roomfit.room.Wall;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers three behaviors added on top of the pre-existing AABB validator:
 * true OBB(SAT) collision for arbitrary rotations, per-furniture "clearance
 * zone" WARNING issues, and the wall-id door/window clearance fix (see
 * ValidationService#wallClearanceCorners).
 */
class ValidationServiceObbAndZoneTest {

    private final ValidationService validationService = new ValidationService();
    private final Room room = new Room(null, 4.0, 4.0, 2.4, "meter", List.of(), List.of());

    @Test
    void fortyFiveDegreeRotatedRectanglesThatOnlyOverlapInAabbAreNotFlaggedAsColliding() {
        // Two 1.0x0.3 desks, both rotated 45°, positioned so their *AABBs*
        // overlap but the actual rotated rectangles (diamonds) do not — this
        // is exactly the case AABB-only collision gets wrong and true OBB/SAT
        // gets right.
        Furniture a = new Furniture("desk-a", "desk", "desk", 1.0, 0.3, 0.72,
                new Position(1.5, 1.5), 45.0, FurnitureStatus.EXISTING);
        Furniture b = new Furniture("desk-b", "desk", "desk", 1.0, 0.3, 0.72,
                new Position(2.35, 0.65), 45.0, FurnitureStatus.EXISTING);

        ValidationResult result = validationService.validate(room, List.of(a, b));

        assertThat(result.isCollisionFree()).isTrue();
        assertThat(result.getIssues()).noneMatch(issue -> issue.type() == ValidationIssue.Type.BODY_COLLISION);
    }

    @Test
    void fortyFiveDegreeRotatedRectanglesThatActuallyOverlapAreFlaggedAsColliding() {
        Furniture a = new Furniture("desk-a", "desk", "desk", 1.0, 0.3, 0.72,
                new Position(1.5, 1.5), 45.0, FurnitureStatus.EXISTING);
        // Both rectangles share the same 45° rotation, so this reduces to an
        // axis-aligned overlap check in their common rotated frame: offsetting
        // mostly along their shared *width* axis (rather than the depth axis
        // used by the non-overlapping fixture above) keeps both centers within
        // half-width + half-width apart, i.e. genuinely overlapping.
        Furniture b = new Furniture("desk-b", "desk", "desk", 1.0, 0.3, 0.72,
                new Position(1.5 + 0.1414, 1.5 + 0.2828), 45.0, FurnitureStatus.EXISTING);

        ValidationResult result = validationService.validate(room, List.of(a, b));

        assertThat(result.isCollisionFree()).isFalse();
        assertThat(result.getIssues())
                .anyMatch(issue -> issue.type() == ValidationIssue.Type.BODY_COLLISION
                        && issue.furnitureId().equals("desk-a") && issue.otherFurnitureId().equals("desk-b"));
    }

    @Test
    void chairInFrontOfWardrobeIsAZoneIntrusionWarningNotAnError() {
        Furniture wardrobe = new Furniture("wardrobe-1", "wardrobe", "wardrobe", 1.0, 0.6, 2.0,
                new Position(1.0, 0.35), 0.0, FurnitureStatus.EXISTING);
        // Wardrobe's front-door zone extends from z=0.65 forward by its
        // required front clearance; a chair sitting just past that edge sits
        // squarely inside that zone without touching the wardrobe's own body.
        Furniture chair = new Furniture("chair-1", "desk_chair", "chair", 0.45, 0.45, 0.8,
                new Position(1.0, 0.9), 0.0, FurnitureStatus.EXISTING);

        ValidationResult result = validationService.validate(room, List.of(wardrobe, chair));

        assertThat(result.isCollisionFree())
                .as("zone intrusion must not fail the hard collisionFree boolean")
                .isTrue();
        assertThat(result.getIssues())
                .anyMatch(issue -> issue.type() == ValidationIssue.Type.ZONE_INTRUSION
                        && issue.severity() == ValidationIssue.Severity.WARNING
                        && issue.furnitureId().equals("wardrobe-1")
                        && issue.otherFurnitureId().equals("chair-1"));
    }

    @Test
    void overlappingBodiesAreNotAlsoDoubleReportedAsZoneIntrusion() {
        Furniture wardrobe = new Furniture("wardrobe-1", "wardrobe", "wardrobe", 1.0, 0.6, 2.0,
                new Position(1.0, 0.35), 0.0, FurnitureStatus.EXISTING);
        Furniture overlapping = new Furniture("bed-1", "bed", "bed", 1.0, 0.6, 0.5,
                new Position(1.0, 0.35), 0.0, FurnitureStatus.EXISTING);

        ValidationResult result = validationService.validate(room, List.of(wardrobe, overlapping));

        assertThat(result.getIssues())
                .filteredOn(issue -> issue.furnitureId().equals("wardrobe-1") || issue.furnitureId().equals("bed-1"))
                .extracting(ValidationIssue::type)
                .contains(ValidationIssue.Type.BODY_COLLISION)
                .doesNotContain(ValidationIssue.Type.ZONE_INTRUSION);
    }

    @Test
    void doorOnAWallIdInANonRectangularRoomNowBlocksClearanceInsteadOfBeingSilentlyIgnored() {
        // Mirrors RoomSampleDataInitializer's L-Studio sample: an L-shaped
        // polygon whose door references Room.walls[].id (the documented
        // standard form — see Opening.java) rather than a legacy
        // north/south/east/west literal.
        Room lStudio = new Room(null, "L Studio", 5.0, 4.0, 2.7, "meter", List.of(
                new Wall("l-wall-north", new Position(0, 0), new Position(3, 0), 2.7, 0.12),
                new Wall("l-wall-notch-in", new Position(3, 0), new Position(3, 1.5), 2.7, 0.1),
                new Wall("l-wall-notch-across", new Position(3, 1.5), new Position(5, 1.5), 2.7, 0.1),
                new Wall("l-wall-east", new Position(5, 1.5), new Position(5, 4), 2.7, 0.12),
                new Wall("l-wall-south", new Position(5, 4), new Position(0, 4), 2.7, 0.12),
                new Wall("l-wall-west", new Position(0, 4), new Position(0, 0), 2.7, 0.12)
        ), List.of(
                new Opening("l-door-1", "door", "l-wall-west", 1.6, 0.9, 2.1, null)
        ), List.of(), com.roomfit.room.RoomSource.SAMPLE, null);

        // l-wall-west runs from (0,4) to (0,0); offset 1.6 from the start means
        // the door spans z in [1.5, 2.4] at x=0. A wardrobe sitting right in
        // front of it must be caught.
        Furniture wardrobeBlockingDoor = new Furniture("wardrobe-1", "wardrobe", "wardrobe", 1.0, 0.5, 2.0,
                new Position(0.5, 1.9), 90.0, FurnitureStatus.EXISTING);

        ValidationResult result = validationService.validate(lStudio, List.of(wardrobeBlockingDoor));

        assertThat(result.isDoorClearance())
                .as("wall-id doors must enforce clearance, not silently pass every layout")
                .isFalse();
        assertThat(result.getIssues())
                .anyMatch(issue -> issue.type() == ValidationIssue.Type.DOOR_CLEARANCE
                        && issue.furnitureId().equals("wardrobe-1"));
    }

    @Test
    void furnitureFarFromTheWallIdDoorIsUnaffected() {
        Room lStudio = new Room(null, "L Studio", 5.0, 4.0, 2.7, "meter", List.of(
                new Wall("l-wall-north", new Position(0, 0), new Position(3, 0), 2.7, 0.12),
                new Wall("l-wall-notch-in", new Position(3, 0), new Position(3, 1.5), 2.7, 0.1),
                new Wall("l-wall-notch-across", new Position(3, 1.5), new Position(5, 1.5), 2.7, 0.1),
                new Wall("l-wall-east", new Position(5, 1.5), new Position(5, 4), 2.7, 0.12),
                new Wall("l-wall-south", new Position(5, 4), new Position(0, 4), 2.7, 0.12),
                new Wall("l-wall-west", new Position(0, 4), new Position(0, 0), 2.7, 0.12)
        ), List.of(
                new Opening("l-door-1", "door", "l-wall-west", 1.6, 0.9, 2.1, null)
        ), List.of(), com.roomfit.room.RoomSource.SAMPLE, null);

        Furniture farAway = new Furniture("bed-1", "bed", "bed", 1.2, 1.2, 0.5,
                new Position(4.2, 3.4), 0.0, FurnitureStatus.EXISTING);

        ValidationResult result = validationService.validate(lStudio, List.of(farAway));

        assertThat(result.isDoorClearance()).isTrue();
        assertThat(result.getIssues()).noneMatch(issue -> issue.type() == ValidationIssue.Type.DOOR_CLEARANCE);
    }

    @Test
    void chairPushedCompletelyUnderADeskIsNeverFlaggedAsCollisionOrZoneIntrusion() {
        Furniture desk = new Furniture("desk-1", "desk", "desk", 1.2, 0.7, 0.72,
                new Position(1.0, 1.0), 0.0, FurnitureStatus.EXISTING);
        // Deliberately overlapping the desk's own body, not just its front
        // clearance zone — a chair tucked all the way under a desk.
        Furniture chair = new Furniture("chair-1", "desk_chair", "chair", 0.5, 0.5, 0.8,
                new Position(1.0, 1.05), 0.0, FurnitureStatus.EXISTING);

        ValidationResult result = validationService.validate(room, List.of(desk, chair));

        assertThat(result.isCollisionFree()).isTrue();
        assertThat(result.getIssues())
                .noneMatch(issue -> issue.type() == ValidationIssue.Type.BODY_COLLISION
                        || issue.type() == ValidationIssue.Type.ZONE_INTRUSION);
    }

    @Test
    void deskChairExemptionAppliesRegardlessOfWhichRawTypeAliasIsUsed() {
        // The catalog aliases the legacy "chair" literal to canonical
        // "desk_chair" — the exemption must key off the normalized type, not
        // the raw string, so old scanned/legacy furniture is covered too.
        Furniture desk = new Furniture("desk-1", "desk", "desk", 1.2, 0.7, 0.72,
                new Position(1.0, 1.0), 0.0, FurnitureStatus.EXISTING);
        Furniture legacyChair = new Furniture("chair-1", "chair", "chair", 0.5, 0.5, 0.8,
                new Position(1.0, 1.05), 0.0, FurnitureStatus.EXISTING);

        ValidationResult result = validationService.validate(room, List.of(desk, legacyChair));

        assertThat(result.isCollisionFree()).isTrue();
    }

    @Test
    void wardrobeFrontClearanceZoneDepthIsHalfItsOwnWidthNotAFixedCatalogConstant() {
        // Width 1.6m is chosen specifically so half-width (0.8m) differs from
        // the catalog's flat wardrobe front value (0.5m) — proving the zone
        // is actually derived from this item's own width, not a constant.
        Furniture wideWardrobe = new Furniture("wardrobe-1", "wardrobe", "wardrobe", 1.6, 0.6, 2.0,
                new Position(1.0, 0.3), 0.0, FurnitureStatus.EXISTING);
        // Wardrobe's front edge is at z=0.6. This chair's body spans
        // z:[1.1,1.5] — clear of the old fixed 0.5m zone (z:[0.6,1.1], only
        // touches at 1.1) but genuinely inside the new 0.8m half-width zone
        // (z:[0.6,1.4]).
        Furniture chair = new Furniture("chair-1", "desk_chair", "chair", 0.4, 0.4, 0.8,
                new Position(1.0, 1.3), 0.0, FurnitureStatus.EXISTING);

        ValidationResult result = validationService.validate(room, List.of(wideWardrobe, chair));

        assertThat(result.getIssues())
                .anyMatch(issue -> issue.type() == ValidationIssue.Type.ZONE_INTRUSION
                        && issue.furnitureId().equals("wardrobe-1"));
    }
}
