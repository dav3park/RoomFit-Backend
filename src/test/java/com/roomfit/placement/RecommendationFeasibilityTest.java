package com.roomfit.placement;

import com.roomfit.agent.domain.AgentContext;
import com.roomfit.agent.domain.DesignStyle;
import com.roomfit.agent.domain.LifestyleGoal;
import com.roomfit.product.catalog.GeneratedFurnitureCatalog;
import com.roomfit.product.domain.MockProduct;
import com.roomfit.product.repository.MockProductRepository;
import com.roomfit.product.service.MockProductService;
import com.roomfit.product.service.PurchaseUrlHealthCheckService;
import com.roomfit.product.service.ProductRecommendationService;
import com.roomfit.room.Furniture;
import com.roomfit.room.FurnitureBoundary;
import com.roomfit.room.Opening;
import com.roomfit.room.Room;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class RecommendationFeasibilityTest {

    private final GeneratedFurnitureCatalog catalog = GeneratedFurnitureCatalog.get();
    private final RuleBasedPlacementService placementService = new RuleBasedPlacementService(
            new MockProductService(new MockProductRepository(), new PurchaseUrlHealthCheckService(new MockProductRepository())),
            new ProductRecommendationService(new MockProductRepository()));

    static Stream<String> canonicalFurnitureTypes() {
        return GeneratedFurnitureCatalog.get().products().stream()
                .map(MockProduct::getType)
                .distinct();
    }

    @ParameterizedTest(name = "{0} has a renderable deterministic placement")
    @MethodSource("canonicalFurnitureTypes")
    void eachCanonicalFurnitureType_isRecognizedAndPlacedInLargeRoom(String furnitureType) {
        Room room = roomFor(furnitureType, 30, 30);
        PlacementResult result = placementService.recommend(context(furnitureType), room);

        assertThat(catalog.normalizeType(furnitureType)).isEqualTo(furnitureType);
        assertThat(result.getRecommendationStatus()).isEqualTo(RecommendationExecutionStatus.SUCCESS);
        assertThat(result.getRequestedFurnitureCount()).isEqualTo(1);
        assertThat(result.getPlacedFurnitureCount()).isEqualTo(1);
        assertThat(result.getUnplacedFurniture()).isEmpty();
        assertThat(result.getRecommendedFurniture()).singleElement().satisfies(furniture -> {
            assertThat(furniture.getType()).isEqualTo(furnitureType);
            assertThat(furniture.getProductId()).isNotBlank();
            assertThat(furniture.getVariantId()).isNotBlank();
            assertThat(catalog.visualFootprint(furniture.getVariantId())).isPresent();
            assertThat(FurnitureBoundary.isInside(room, furniture)).isTrue();
        });
        assertThat(new ValidationService().validate(room, result.getRecommendedFurniture()).isBoundaryValid())
                .isTrue();
    }

    @Test
    void generatedCatalog_has21TypesAnd93UniqueRenderableVariantsWithFiniteRotatedFootprints() {
        List<MockProduct> products = catalog.products();
        assertThat(catalog.catalogVersion()).isEqualTo("2026-07-18.2");
        assertThat(products).hasSize(93);
        assertThat(products.stream().map(MockProduct::getType).collect(java.util.stream.Collectors.toSet())).hasSize(21);
        assertThat(products).extracting(MockProduct::getProductId).doesNotHaveDuplicates().allMatch(id -> !id.isBlank());
        assertThat(products).extracting(MockProduct::getVariantId).doesNotHaveDuplicates().allMatch(id -> !id.isBlank());

        for (MockProduct product : products) {
            assertThat(product.getWidth()).isPositive();
            assertThat(product.getDepth()).isPositive();
            assertThat(product.getHeight()).isPositive();
            assertThat(catalog.visualFootprint(product.getVariantId())).isPresent();
            for (double rotation : List.of(0d, 90d, 180d, 270d)) {
                FurnitureBoundary.Footprint footprint = FurnitureBoundary.footprint(
                        product.getWidth(), product.getDepth(), rotation, product.getVariantId());
                assertThat(footprint.effectiveWidth()).isPositive().isFinite();
                assertThat(footprint.effectiveDepth()).isPositive().isFinite();
                assertThat(footprint.minX()).isFinite();
                assertThat(footprint.maxX()).isFinite();
                assertThat(footprint.minZ()).isFinite();
                assertThat(footprint.maxZ()).isFinite();
            }
        }
    }

    @Test
    void smallRoomProducesNormalFailedRecommendationInsteadOfAnException() {
        PlacementResult result = placementService.recommend(context("bed"), room(1, 1));

        assertThat(result.getRecommendationStatus()).isEqualTo(RecommendationExecutionStatus.FAILED);
        assertThat(result.getPlacedFurnitureCount()).isZero();
        assertThat(result.getUnplacedFurniture()).singleElement()
                .extracting(UnplacedFurniture::reasonCode)
                .isEqualTo("NO_VALID_BOUNDARY_PLACEMENT");
    }

    @Test
    void windowlessRoomKeepsEarlierPlacementAndReturnsStablePartialFailureDetails() {
        AgentContext context = new AgentContext(1L, LifestyleGoal.STUDY_FOCUSED, List.of(DesignStyle.MINIMAL),
                List.of("bed", "curtain_blind"), List.of(), List.of(), List.of(), List.of());

        PlacementResult result = placementService.recommend(context, room(3, 2.8));

        assertThat(result.getRecommendationStatus()).isEqualTo(RecommendationExecutionStatus.PARTIAL_SUCCESS);
        assertThat(result.getRequestedFurnitureCount()).isEqualTo(2);
        assertThat(result.getPlacedFurnitureCount()).isEqualTo(1);
        assertThat(result.getUnplacedFurniture()).singleElement().satisfies(unplaced -> {
            assertThat(unplaced.requestIndex()).isEqualTo(1);
            assertThat(unplaced.furnitureType()).isEqualTo("curtain_blind");
        });
        assertThat(new ValidationService().validate(room(3, 2.8), result.getRecommendedFurniture()).isBoundaryValid())
                .isTrue();
    }

    private AgentContext context(String furnitureType) {
        return new AgentContext(1L, LifestyleGoal.STUDY_FOCUSED, List.of(DesignStyle.MINIMAL),
                List.of(furnitureType), List.of(), List.of(), List.of(), List.of());
    }

    private Room room(double width, double depth) {
        return new Room(null, width, depth, 3, "meter", List.of(), List.of());
    }

    private Room roomFor(String furnitureType, double width, double depth) {
        List<Opening> openings = "curtain_blind".equals(furnitureType)
                ? List.of(new Opening("window-1", "window", "north", 14, 2, 1.2, 0.9))
                : List.of();
        return new Room(null, width, depth, 3, "meter", openings, List.of());
    }
}
