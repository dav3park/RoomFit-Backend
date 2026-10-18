package com.roomfit.placement;

import com.roomfit.agent.domain.AgentContext;
import com.roomfit.agent.domain.DesignStyle;
import com.roomfit.agent.domain.LifestyleGoal;
import com.roomfit.product.domain.MockProduct;
import com.roomfit.product.domain.RequiredClearance;
import com.roomfit.product.service.MockProductService;
import com.roomfit.product.service.ProductRecommendationService;
import com.roomfit.room.Furniture;
import com.roomfit.room.FurnitureStatus;
import com.roomfit.room.Position;
import com.roomfit.room.Room;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuleBasedPlacementVariantIdTest {

    @Test
    void recommend_preservesSelectedProductVariantId() {
        MockProduct product = product("desk-compact");
        MockProductService productService = mock(MockProductService.class);
        when(productService.findByProductIds(List.of("desk-product"))).thenReturn(List.of(product));

        PlacementResult result = new RuleBasedPlacementService(productService, mock(ProductRecommendationService.class))
                .recommend(context(), room());

        assertThat(result.getRecommendedFurniture()).hasSize(1);
        Furniture furniture = result.getRecommendedFurniture().getFirst();
        assertThat(furniture.getProductId()).isEqualTo("desk-product");
        assertThat(furniture.getVariantId()).isEqualTo("desk-compact");
    }

    @Test
    void recommend_matchesLegacyChairAliasToSelectedCanonicalChairProduct() {
        MockProduct chair = new MockProduct("chair-basic-01", "chair-basic", "desk_chair", "기본 의자", null,
                0.46, 0.54, 0.8, (Integer) null, List.of("minimal", "modern"), null, null,
                new RequiredClearance(0.4, 0.1));
        MockProductService productService = mock(MockProductService.class);
        when(productService.findByProductIds(List.of("chair-basic-01"))).thenReturn(List.of(chair));
        AgentContext context = new AgentContext(1L, LifestyleGoal.STUDY_FOCUSED, List.of(DesignStyle.MINIMAL),
                List.of("chair"), List.of(), List.of(1L), List.of("chair-basic-01"), List.of("minimal"));

        PlacementResult result = new RuleBasedPlacementService(productService, mock(ProductRecommendationService.class))
                .recommend(context, room());

        assertThat(result.getRecommendedFurniture()).hasSize(1);
        Furniture furniture = result.getRecommendedFurniture().getFirst();
        assertThat(furniture.getType()).isEqualTo("desk_chair");
        assertThat(furniture.getProductId()).isEqualTo("chair-basic-01");
        assertThat(furniture.getVariantId()).isEqualTo("chair-basic");
    }

    @Test
    void recommend_keepsNullVariantIdCompatible() {
        MockProduct product = product(null);
        MockProductService productService = mock(MockProductService.class);
        when(productService.findByProductIds(List.of("desk-product"))).thenReturn(List.of(product));

        PlacementResult result = new RuleBasedPlacementService(productService, mock(ProductRecommendationService.class))
                .recommend(context(), room());

        assertThat(result.getRecommendedFurniture()).hasSize(1);
        assertThat(result.getRecommendedFurniture().getFirst().getVariantId()).isNull();
    }

    @Test
    void recommend_usesProductRecommendationService_whenNoExactSelectedProductForType() {
        MockProduct recommended = product("desk-corner");
        MockProductService productService = mock(MockProductService.class);
        when(productService.findByProductIds(List.of())).thenReturn(List.of());
        ProductRecommendationService productRecommendationService = mock(ProductRecommendationService.class);
        when(productRecommendationService.rankedCandidates(eq("desk"), any(AgentContext.class), any(Room.class)))
                .thenReturn(List.of(recommended));

        AgentContext context = new AgentContext(1L, LifestyleGoal.STUDY_FOCUSED, List.of(DesignStyle.MINIMAL),
                List.of("desk"), List.of(), List.of(1L), List.of(), List.of("minimal"));

        PlacementResult result = new RuleBasedPlacementService(productService, productRecommendationService)
                .recommend(context, room());

        assertThat(result.getRecommendedFurniture()).hasSize(1);
        Furniture furniture = result.getRecommendedFurniture().getFirst();
        assertThat(furniture.getProductId()).isEqualTo("desk-product");
        assertThat(furniture.getVariantId()).isEqualTo("desk-corner");
    }

    @Test
    void recommend_addsRequestedDeskWhenDeskAlreadyPlacedInRoom() {
        MockProductService productService = mock(MockProductService.class);
        when(productService.findByProductIds(List.of())).thenReturn(List.of());
        ProductRecommendationService productRecommendationService = mock(ProductRecommendationService.class);
        when(productRecommendationService.rankedCandidates(eq("desk"), any(AgentContext.class), any(Room.class)))
                .thenReturn(List.of(product("desk-corner")));

        Furniture existingDesk = new Furniture("existing-desk", "desk", "기존 책상", 1.2, 0.6, 0.72,
                new Position(2.0, 1.0), 0, FurnitureStatus.EXISTING);
        Room room = new Room(null, 4.0, 4.0, 2.4, "meter", List.of(), List.of(existingDesk));

        AgentContext context = new AgentContext(1L, LifestyleGoal.STUDY_FOCUSED, List.of(DesignStyle.MINIMAL),
                List.of("desk"), List.of(), List.of(1L), List.of(), List.of("minimal"));

        PlacementResult result = new RuleBasedPlacementService(productService, productRecommendationService)
                .recommend(context, room);

        assertThat(result.getRecommendedFurniture()).hasSize(2);
        assertThat(result.getRecommendedFurniture()).filteredOn(item -> item.getId().equals("existing-desk"))
                .singleElement().satisfies(item -> {
                    assertThat(item.getType()).isEqualTo("desk");
                    assertThat(item.getProductId()).isNull();
                    assertThat(item.getVariantId()).isNull();
                });
        assertThat(result.getRecommendedFurniture()).filteredOn(item -> !item.getId().equals("existing-desk"))
                .singleElement().extracting(Furniture::getType).isEqualTo("desk");
        verify(productRecommendationService).rankedCandidates(eq("desk"), any(), any());
    }

    @Test
    void recommend_addsEveryRequestedDeskBeyondTwoExistingDesks() {
        MockProductService productService = mock(MockProductService.class);
        when(productService.findByProductIds(List.of())).thenReturn(List.of());
        ProductRecommendationService recommendations = mock(ProductRecommendationService.class);
        when(recommendations.rankedCandidates(eq("desk"), any(AgentContext.class), any(Room.class)))
                .thenReturn(List.of(product("desk-compact")));
        Room room = new Room(null, 8.0, 8.0, 2.4, "meter", List.of(), List.of(
                existingDesk("existing-desk-1", 1.0, 1.0), existingDesk("existing-desk-2", 1.0, 3.0)));
        AgentContext context = new AgentContext(1L, LifestyleGoal.STUDY_FOCUSED, List.of(DesignStyle.MINIMAL),
                List.of("desk", "desk"), List.of(), List.of(1L), List.of(), List.of("minimal"));

        PlacementResult result = new RuleBasedPlacementService(productService, recommendations).recommend(context, room);

        assertThat(result.getRecommendedFurniture()).hasSize(4);
        assertThat(result.getRecommendedFurniture()).filteredOn(item -> item.getId().startsWith("existing-desk"))
                .hasSize(2);
        assertThat(result.getRecommendedFurniture()).filteredOn(item -> "desk".equals(item.getType())
                && !item.getId().startsWith("existing-desk")).hasSize(2);
    }

    @Test
    void recommend_doesNotSkipRequestedBookshelfWhenBookshelfAlreadyExists() {
        MockProduct bookshelf = new MockProduct("bookshelf-low-01", "bookshelf-low", "bookshelf", "낮은 책장", null,
                0.8, 0.35, 1.2, (Integer) null, List.of("minimal"), null, null,
                new RequiredClearance(0.4, 0.1));
        MockProductService productService = mock(MockProductService.class);
        when(productService.findByProductIds(List.of("bookshelf-low-01"))).thenReturn(List.of(bookshelf));
        Furniture existing = new Furniture("existing-bookshelf", "bookshelf", "기존 책장", 0.8, 0.35, 1.2,
                new Position(1.0, 1.0), 0, FurnitureStatus.EXISTING,
                "original-bookshelf", List.of("walnut"), "bookshelf-low");
        Room room = new Room(null, 8.0, 8.0, 2.4, "meter", List.of(), List.of(existing));
        AgentContext context = new AgentContext(1L, LifestyleGoal.STUDY_FOCUSED, List.of(DesignStyle.MINIMAL),
                List.of("bookshelf"), List.of(), List.of(1L), List.of("bookshelf-low-01"), List.of("minimal"));

        PlacementResult result = new RuleBasedPlacementService(productService, mock(ProductRecommendationService.class))
                .recommend(context, room);

        assertThat(result.getRecommendedFurniture()).hasSize(2);
        assertThat(result.getRecommendedFurniture()).filteredOn(item -> item.getId().equals("existing-bookshelf"))
                .singleElement().satisfies(item -> {
                    assertThat(item.getProductId()).isEqualTo("original-bookshelf");
                    assertThat(item.getVariantId()).isEqualTo("bookshelf-low");
                });
        assertThat(result.getRecommendedFurniture()).filteredOn(item -> !item.getId().equals("existing-bookshelf"))
                .singleElement().extracting(Furniture::getType).isEqualTo("bookshelf");
    }

    private Furniture existingDesk(String id, double x, double z) {
        return new Furniture(id, "desk", "기존 책상", 1.2, 0.6, 0.72,
                new Position(x, z), 0, FurnitureStatus.EXISTING);
    }

    private AgentContext context() {
        return new AgentContext(1L, LifestyleGoal.STUDY_FOCUSED, List.of(DesignStyle.MINIMAL),
                List.of("desk"), List.of(), List.of(1L), List.of("desk-product"), List.of("minimal"));
    }

    private Room room() {
        return new Room(null, 4.0, 4.0, 2.4, "meter", List.of(), List.of());
    }

    private MockProduct product(String variantId) {
        return new MockProduct("desk-product", variantId, "desk", "컴팩트 책상", "RoomFit Mock",
                1.2, 0.6, 0.73, 89000, List.of("minimal"), "/images/products/desk.png",
                null, new RequiredClearance(0.6, 0.2));
    }
}
