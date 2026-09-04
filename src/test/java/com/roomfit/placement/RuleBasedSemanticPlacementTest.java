package com.roomfit.placement;

import com.roomfit.agent.domain.AgentContext;
import com.roomfit.agent.domain.DesignStyle;
import com.roomfit.agent.domain.LifestyleGoal;
import com.roomfit.product.repository.MockProductRepository;
import com.roomfit.product.service.MockProductService;
import com.roomfit.product.service.ProductRecommendationService;
import com.roomfit.product.service.PurchaseUrlHealthCheckService;
import com.roomfit.room.Furniture;
import com.roomfit.room.FurnitureBoundary;
import com.roomfit.room.FurnitureStatus;
import com.roomfit.room.Opening;
import com.roomfit.room.Position;
import com.roomfit.room.Room;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RuleBasedSemanticPlacementTest {

    private final MockProductRepository products = new MockProductRepository();
    private final RuleBasedPlacementService service = new RuleBasedPlacementService(
            new MockProductService(products, new PurchaseUrlHealthCheckService(products)),
            new ProductRecommendationService(products));

    @Test
    void requestOrderDoesNotPreventDeskMonitorStack() {
        PlacementResult result = recommend(emptyRoom(), List.of("monitor", "desk"),
                List.of("monitor-basic", "desk-compact"));

        Furniture desk = furnitureByType(result, "desk");
        Furniture monitor = furnitureByType(result, "monitor");
        assertThat(result.getRecommendedFurniture().indexOf(desk))
                .isLessThan(result.getRecommendedFurniture().indexOf(monitor));
        assertThat(monitor.getPosition().getX()).isEqualTo(desk.getPosition().getX());
        assertThat(monitor.getPosition().getZ()).isEqualTo(desk.getPosition().getZ());
        assertThat(monitor.getRotation()).isEqualTo(desk.getRotation());
    }

    @Test
    void requestOrderDoesNotPreventMediaConsoleTvStack() {
        PlacementResult result = recommend(emptyRoom(), List.of("tv", "media_console"),
                List.of("tv-small", "media-console-drawer"));

        Furniture console = furnitureByType(result, "media_console");
        Furniture tv = furnitureByType(result, "tv");
        assertThat(result.getRecommendedFurniture().indexOf(console))
                .isLessThan(result.getRecommendedFurniture().indexOf(tv));
        assertThat(tv.getPosition().getX()).isEqualTo(console.getPosition().getX());
        assertThat(tv.getPosition().getZ()).isEqualTo(console.getPosition().getZ());
        assertThat(tv.getRotation()).isEqualTo(console.getRotation());
    }

    @Test
    void nightstandUsesBedSideCandidate() {
        for (double rotation : List.of(0.0, 90.0)) {
            Furniture bed = existing("bed", 1.6, 2.0, 4.0, 4.0, rotation);
            Furniture nightstand = furnitureByType(
                    recommend(room(List.of(), List.of(bed)), List.of("nightstand"),
                            List.of("nightstand-drawer")),
                    "nightstand");

            assertOnLocalRightOutside(bed, nightstand);
            assertThat(nightstand.getRotation()).isEqualTo(rotation);
        }
    }

    @Test
    void sideTableUsesSofaSideCandidate() {
        for (double rotation : List.of(0.0, 90.0)) {
            Furniture sofa = existing("sofa", 1.8, 0.9, 4.0, 4.0, rotation);
            Furniture sideTable = furnitureByType(
                    recommend(room(List.of(), List.of(sofa)), List.of("side_table"),
                            List.of("side-table-midcentury-stockholm")),
                    "side_table");

            assertOnLocalRightOutside(sofa, sideTable);
            assertThat(sideTable.getRotation()).isEqualTo(rotation);
        }
    }

    @Test
    void plantUsesUsableCornerCandidate() {
        Room room = emptyRoom();
        Furniture plant = furnitureByType(
                recommend(room, List.of("plant"), List.of("plant-floor")), "plant");
        FurnitureBoundary.Footprint footprint = FurnitureBoundary.footprint(plant);
        FurnitureBoundary.UsableBounds usable = FurnitureBoundary.usableBounds(room).orElseThrow();

        assertThat(plant.getPosition().getX() + footprint.minX()).isCloseTo(usable.minX(), within(1.0e-9));
        assertThat(plant.getPosition().getZ() + footprint.minZ()).isCloseTo(usable.minZ(), within(1.0e-9));
    }

    @Test
    void bulkyFurnitureUsesWallCandidateAndFacesInward() {
        Room room = emptyRoom();
        Furniture wardrobe = furnitureByType(
                recommend(room, List.of("wardrobe"), List.of("wardrobe-hinged")), "wardrobe");

        assertTouchesWallWithMatchingRotation(room, wardrobe);
        assertThat(wardrobe.getRotation()).isEqualTo(0.0);
    }

    @Test
    void openingBlockedWallFallsThroughToAnotherWall() {
        Opening southDoor = new Opening("door-1", "door", "south", 0, 10, 2.1, null);
        Room room = room(List.of(southDoor), List.of());
        Furniture wardrobe = furnitureByType(
                recommend(room, List.of("wardrobe"), List.of("wardrobe-hinged")), "wardrobe");

        assertTouchesWallWithMatchingRotation(room, wardrobe);
        assertThat(wardrobe.getRotation()).isEqualTo(90.0);
    }

    @Test
    void curtainBlindWithoutWindowIsUnplacedInsteadOfUsingGrid() {
        PlacementResult result = recommend(emptyRoom(), List.of("curtain_blind"), List.of("blind-roller"));

        assertThat(result.getPlacedFurnitureCount()).isZero();
        assertThat(result.getUnplacedFurniture()).singleElement()
                .extracting(UnplacedFurniture::furnitureType)
                .isEqualTo("curtain_blind");
    }

    @Test
    void curtainBlindUsesWindowCandidateWithNormalSillHeight() {
        Opening northWindow = new Opening("window-1", "window", "north", 4.0, 2.0, 1.2, 0.9);
        Room room = room(List.of(northWindow), List.of());

        Furniture blind = furnitureByType(
                recommend(room, List.of("curtain_blind"), List.of("blind-roller")),
                "curtain_blind");

        assertTouchesWallWithMatchingRotation(room, blind);
        assertThat(blind.getRotation()).isEqualTo(180.0);
        assertThat(blind.getPosition().getX()).isEqualTo(5.0);
    }

    @Test
    void supportedMonitorStacksOnDeskEvenWhenDeskIsNarrower() {
        Furniture desk = existing("desk", 0.8, 0.5, 6.0, 6.0, 0);
        List<Furniture> existing = new ArrayList<>();
        existing.add(desk);
        existing.add(existing("blocker", 0.1, 0.1, 2.2, 2.0, 0));
        existing.add(existing("blocker", 0.1, 0.1, 1.6, 3.1, 0));
        existing.add(existing("blocker", 0.1, 0.1, 0.8, 3.3, 0));
        Room room = room(List.of(), existing);

        Furniture first = furnitureByType(
                recommend(room, List.of("monitor"), List.of("monitor-dual")), "monitor");
        Furniture second = furnitureByType(
                recommend(room, List.of("monitor"), List.of("monitor-dual")), "monitor");

        assertThat(first.getPosition().getX()).isEqualTo(desk.getPosition().getX());
        assertThat(first.getPosition().getZ()).isEqualTo(desk.getPosition().getZ());
        assertThat(second.getPosition().getX()).isEqualTo(first.getPosition().getX());
        assertThat(second.getPosition().getZ()).isEqualTo(first.getPosition().getZ());
    }

    private PlacementResult recommend(Room room, List<String> requestedTypes, List<String> variants) {
        List<String> productIds = variants.stream().map(variant -> variant + "-01").toList();
        AgentContext context = new AgentContext(1L, LifestyleGoal.STUDY_FOCUSED,
                List.of(DesignStyle.MINIMAL), requestedTypes, List.of(), List.of(), productIds, List.of());
        return service.recommend(context, room);
    }

    private Room emptyRoom() {
        return room(List.of(), List.of());
    }

    private Room room(List<Opening> openings, List<Furniture> furniture) {
        return new Room(null, 10.0, 10.0, 2.6, "meter", openings, furniture);
    }

    private Furniture existing(String type, double width, double depth,
                               double x, double z, double rotation) {
        return new Furniture(type + "-existing-" + x + "-" + z, type, type, width, depth, 0.8,
                new Position(x, z), rotation, FurnitureStatus.EXISTING);
    }

    private Furniture furnitureByType(PlacementResult result, String type) {
        return result.getRecommendedFurniture().stream()
                .filter(item -> type.equals(item.getType()))
                .findFirst()
                .orElseThrow();
    }

    private void assertOnLocalRightOutside(Furniture anchor, Furniture dependent) {
        FurnitureBoundary.Footprint anchorFootprint = FurnitureBoundary.footprint(anchor);
        FurnitureBoundary.Footprint dependentFootprint = FurnitureBoundary.footprint(dependent);
        if (anchor.getRotation() == 0) {
            assertThat(dependent.getPosition().getX() + dependentFootprint.minX())
                    .isGreaterThan(anchor.getPosition().getX() + anchorFootprint.maxX());
            assertThat(dependent.getPosition().getZ()).isEqualTo(anchor.getPosition().getZ());
        } else {
            assertThat(dependent.getPosition().getZ() + dependentFootprint.maxZ())
                    .isLessThan(anchor.getPosition().getZ() + anchorFootprint.minZ());
            assertThat(dependent.getPosition().getX()).isEqualTo(anchor.getPosition().getX());
        }
    }

    private void assertTouchesWallWithMatchingRotation(Room room, Furniture furniture) {
        FurnitureBoundary.Footprint footprint = FurnitureBoundary.footprint(furniture);
        FurnitureBoundary.UsableBounds usable = FurnitureBoundary.usableBounds(room).orElseThrow();
        switch ((int) furniture.getRotation()) {
            case 0 -> assertThat(furniture.getPosition().getZ() + footprint.minZ())
                    .isCloseTo(usable.minZ(), within(1.0e-9));
            case 90 -> assertThat(furniture.getPosition().getX() + footprint.maxX())
                    .isCloseTo(usable.maxX(), within(1.0e-9));
            case 180 -> assertThat(furniture.getPosition().getZ() + footprint.maxZ())
                    .isCloseTo(usable.maxZ(), within(1.0e-9));
            case 270 -> assertThat(furniture.getPosition().getX() + footprint.minX())
                    .isCloseTo(usable.minX(), within(1.0e-9));
            default -> throw new AssertionError("Expected cardinal inward rotation but was " + furniture.getRotation());
        }
    }
}
