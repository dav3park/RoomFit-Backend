package com.roomfit.placement;

import com.roomfit.agent.domain.AgentContext;
import com.roomfit.agent.domain.DesignStyle;
import com.roomfit.agent.domain.LifestyleGoal;
import com.roomfit.product.repository.MockProductRepository;
import com.roomfit.product.service.MockProductService;
import com.roomfit.product.service.PurchaseUrlHealthCheckService;
import com.roomfit.product.service.ProductRecommendationService;
import com.roomfit.room.Furniture;
import com.roomfit.room.Room;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 작업 지시서 8절의 고정 평가 시나리오(A~D)를 그대로 재현한다. E(selectedProductIds
 * exact match)/F(deterministic 반복)/G(loft-desk 정책 회귀)는
 * RuleBasedPlacementServiceRecommendationQualityTest에서 이미 검증한다.
 */
class FixedEvaluationScenariosTest {

    private final MockProductService mockProductService = new MockProductService(new MockProductRepository(), new PurchaseUrlHealthCheckService(new MockProductRepository()));
    private final ProductRecommendationService productRecommendationService =
            new ProductRecommendationService(new MockProductRepository());
    private final ValidationService validationService = new ValidationService();
    private final RuleBasedPlacementService service =
            new RuleBasedPlacementService(mockProductService, productRecommendationService, validationService);

    @Test
    void scenarioA_smallOfficeRoom_placesAllRequiredItemsWithFittableDeskAndPassesValidation() {
        Room room = room(3.0, 3.0);
        AgentContext context = new AgentContext(1L, LifestyleGoal.STUDY_FOCUSED, List.of(DesignStyle.MODERN),
                List.of("desk", "desk_chair", "monitor"), List.of(), List.of(), List.of(), List.of());

        PlacementResult result = service.recommend(context, room);

        assertThat(result.getRecommendationStatus()).isEqualTo(RecommendationExecutionStatus.SUCCESS);
        assertThat(result.getPlacedFurnitureCount()).isEqualTo(3);
        assertThat(typesOf(result.getRecommendedFurniture())).containsExactlyInAnyOrder("desk", "desk_chair", "monitor");
        assertFullyValid(room, result);
    }

    @Test
    void scenarioB_smallStorageRoom_avoidsOversizedStorageAndMatchesNaturalStyle() {
        Room room = room(3.0, 3.2);
        AgentContext context = new AgentContext(1L, LifestyleGoal.STORAGE_FOCUSED, List.of(DesignStyle.NATURAL),
                List.of("wardrobe", "drawer_chest"), List.of(), List.of(), List.of(), List.of());

        PlacementResult result = service.recommend(context, room);

        assertThat(result.getRecommendationStatus()).isEqualTo(RecommendationExecutionStatus.SUCCESS);
        assertThat(result.getPlacedFurnitureCount()).isEqualTo(2);
        Furniture wardrobe = findByType(result.getRecommendedFurniture(), "wardrobe");
        // natural 스타일 태그를 가진 wardrobe 후보가 존재하므로(wardrobe-classic-gullaberg,
        // wardrobe-natural-nordkisa) 스타일이 실제로 반영됐다면 이 중 하나가 골라져야 한다.
        assertThat(wardrobe.getStyleTags()).contains("natural");
        assertFullyValid(room, result);
    }

    @Test
    void scenarioC_relaxFocusedRoom_bedCenteredCompositionAvoidsWorkOrientedVariant() {
        Room room = room(3.5, 4.0);
        AgentContext context = new AgentContext(1L, LifestyleGoal.RELAX_FOCUSED, List.of(DesignStyle.NATURAL),
                List.of("bed", "nightstand", "mood_lamp"), List.of(), List.of(), List.of(), List.of());

        PlacementResult result = service.recommend(context, room);

        assertThat(result.getRecommendationStatus()).isEqualTo(RecommendationExecutionStatus.SUCCESS);
        assertThat(result.getPlacedFurnitureCount()).isEqualTo(3);
        Furniture bed = findByType(result.getRecommendedFurniture(), "bed");
        // bed-loft-desk는 modern/minimal 스타일이라 natural과 겹치지 않아 style 비교에서
        // 밀린다 — RELAX_FOCUSED + natural 조합에서 업무용(WORK_STUDY) 침대 Variant가
        // 불필요하게 선택되면 안 된다.
        assertThat(bed.getVariantId()).isNotEqualTo("bed-loft-desk");
        assertThat(bed.getStyleTags()).contains("natural");
        assertFullyValid(room, result);
    }

    @Test
    void scenarioD_hobbyFocusedRoom_tvAndMediaConsoleShareStyleAndSofaFits() {
        Room room = room(4.0, 4.0);
        AgentContext context = new AgentContext(1L, LifestyleGoal.HOBBY_FOCUSED, List.of(DesignStyle.MODERN),
                List.of("tv", "media_console", "sofa"), List.of(), List.of(), List.of(), List.of());

        PlacementResult result = service.recommend(context, room);

        assertThat(result.getRecommendationStatus()).isEqualTo(RecommendationExecutionStatus.SUCCESS);
        assertThat(result.getPlacedFurnitureCount()).isEqualTo(3);
        Furniture tv = findByType(result.getRecommendedFurniture(), "tv");
        Furniture mediaConsole = findByType(result.getRecommendedFurniture(), "media_console");
        assertThat(tv.getStyleTags()).contains("modern");
        assertThat(mediaConsole.getStyleTags()).contains("modern");
        // sofa가 4x4 방에 실제로 들어갔다는 것 자체가 clearance 포함 배치가
        // 가능했다는 뜻이다(assertFullyValid의 boundary/collision 통과로 확인).
        assertFullyValid(room, result);
    }

    private List<String> typesOf(List<Furniture> furniture) {
        return furniture.stream().map(Furniture::getType).toList();
    }

    private Furniture findByType(List<Furniture> furniture, String type) {
        return furniture.stream()
                .filter(item -> type.equals(item.getType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no furniture of type " + type + " in " + furniture));
    }

    private void assertFullyValid(Room room, PlacementResult result) {
        ValidationResult validation = validationService.validate(room, result.getRecommendedFurniture());
        assertThat(validation.isCollisionFree()).isTrue();
        assertThat(validation.isBoundaryValid()).isTrue();
        assertThat(validation.isDoorClearance()).isTrue();
        assertThat(validation.isWindowClearance()).isTrue();
        assertThat(validation.isPathSecured()).isTrue();
    }

    private Room room(double width, double depth) {
        return new Room(null, width, depth, 2.4, "meter", List.of(), List.of());
    }
}
