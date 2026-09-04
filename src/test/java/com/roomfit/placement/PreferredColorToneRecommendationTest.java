package com.roomfit.placement;

import com.roomfit.agent.domain.AgentContext;
import com.roomfit.agent.domain.DesignStyle;
import com.roomfit.agent.domain.LifestyleGoal;
import com.roomfit.agent.domain.PreferredColorTone;
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
 * 팀 내부 테스트에서 "휴식 중심 + 우드 + 내추럴" 조건을 선택해도 추천 결과가 기존
 * 데모 시나리오와 거의 동일하게 나온다고 보고된 문제를 재현/검증한다.
 *
 * 조사 결과: RuleBasedPlacementService.recommend()는 room.getName()이 정확히
 * "미드센추리 컬렉터 룸" 또는 "샘플룸2"일 때만 scriptedRecommendation()으로
 * 빠져 Context를 완전히 무시한다 — 하지만 현재 RoomSampleDataInitializer가
 * 만드는 canonical sample room 이름은 "Sample Room"이고, 이 두 이름을 만드는
 * 코드는 현재 트리에 이 파일(RuleBasedPlacementService) 말고는 없다. 즉
 * "보통 이름의 방"에서는 이 분기를 타지 않는다 — 이 테스트가 그것을 증명한다.
 *
 * 실제로 확인된, 코드로 재현 가능한 원인은 preferredColorTone이 지금까지
 * ProductRecommendationService의 어떤 점수 계산에도 전혀 쓰이지 않았다는
 * 것이었다(PreferredColorTone.java의 기존 주석이 스스로 그렇게 밝히고 있었다).
 * GeneratedFurnitureCatalog도 원본 JSON의 material id 목록을 MockProduct로
 * 옮기지 않고 버리고 있었다. 이번 커밋에서 MockProduct.materialTags +
 * PreferredColorTone.toMaterialTags()를 추가해 실제로 반영되게 했다.
 */
class PreferredColorToneRecommendationTest {

    private final MockProductService mockProductService = new MockProductService(new MockProductRepository(), new PurchaseUrlHealthCheckService(new MockProductRepository()));
    private final ProductRecommendationService productRecommendationService =
            new ProductRecommendationService(new MockProductRepository());
    private final ValidationService validationService = new ValidationService();
    private final RuleBasedPlacementService service =
            new RuleBasedPlacementService(mockProductService, productRecommendationService, validationService);

    @Test
    void recommend_relaxWoodNatural_differsFromStudyGrayModern_forSameRoomAndRequiredItems() {
        Room room = room(5.0, 5.0);
        List<String> requiredItems = List.of("bed", "nightstand", "mood_lamp");

        AgentContext relaxWoodNatural = new AgentContext(1L, LifestyleGoal.RELAX_FOCUSED, List.of(DesignStyle.NATURAL),
                requiredItems, List.of(), List.of(), List.of(), List.of(), PreferredColorTone.BROWN_WOOD);
        AgentContext studyGrayModern = new AgentContext(1L, LifestyleGoal.STUDY_FOCUSED, List.of(DesignStyle.MODERN),
                requiredItems, List.of(), List.of(), List.of(), List.of(), PreferredColorTone.GRAY);

        PlacementResult relaxResult = service.recommend(relaxWoodNatural, room);
        PlacementResult studyResult = service.recommend(studyGrayModern, room);

        assertThat(relaxResult.getRecommendationStatus()).isEqualTo(RecommendationExecutionStatus.SUCCESS);
        assertThat(studyResult.getRecommendationStatus()).isEqualTo(RecommendationExecutionStatus.SUCCESS);

        Furniture relaxBed = findByType(relaxResult.getRecommendedFurniture(), "bed");
        Furniture studyBed = findByType(studyResult.getRecommendedFurniture(), "bed");
        assertThat(relaxBed.getProductId()).isEqualTo("bed-low-platform-01");
        assertThat(relaxBed.getVariantId()).isEqualTo("bed-low-platform");
        assertThat(studyBed.getProductId()).isEqualTo("bed-loft-desk-01");
        assertThat(studyBed.getVariantId()).isEqualTo("bed-loft-desk");
        assertThat(relaxBed.getProductId()).isNotEqualTo(studyBed.getProductId());

        Furniture relaxNightstand = findByType(relaxResult.getRecommendedFurniture(), "nightstand");
        Furniture studyNightstand = findByType(studyResult.getRecommendedFurniture(), "nightstand");
        assertThat(relaxNightstand.getProductId()).isEqualTo("nightstand-classic-gullaberg-01");
        assertThat(studyNightstand.getProductId()).isEqualTo("nightstand-midcentury-trolley-01");
        assertThat(relaxNightstand.getProductId()).isNotEqualTo(studyNightstand.getProductId());

        // mood_lamp 4종은 전부 "modern" style 태그를 공유해, 이 두 케이스에서는
        // 우연히 같은 Product(lamp-floor)로 수렴한다 — bug가 아니라 Catalog
        // 구성상의 한계다(카탈로그에 "natural" 계열 mood_lamp가 아예 없음).
        Furniture relaxLamp = findByType(relaxResult.getRecommendedFurniture(), "mood_lamp");
        Furniture studyLamp = findByType(studyResult.getRecommendedFurniture(), "mood_lamp");
        assertThat(relaxLamp.getProductId()).isEqualTo("lamp-floor-01");
        assertThat(studyLamp.getProductId()).isEqualTo("lamp-floor-01");

        assertFullyValid(room, relaxResult);
        assertFullyValid(room, studyResult);
    }

    @Test
    void recommend_colorToneAlone_changesSelectionWhenStyleAndLifestyleAreNeutral() {
        // style/lifestyle을 비워 두 점수를 전부 0으로 무력화하면, colorTone만으로
        // 후보가 갈린다는 걸 단독으로 증명할 수 있다.
        Room room = room(5.0, 5.0);
        AgentContext wood = new AgentContext(1L, null, List.of(),
                List.of("nightstand"), List.of(), List.of(), List.of(), List.of(), PreferredColorTone.BROWN_WOOD);
        AgentContext gray = new AgentContext(1L, null, List.of(),
                List.of("nightstand"), List.of(), List.of(), List.of(), List.of(), PreferredColorTone.GRAY);

        Furniture woodNightstand = findByType(service.recommend(wood, room).getRecommendedFurniture(), "nightstand");
        Furniture grayNightstand = findByType(service.recommend(gray, room).getRecommendedFurniture(), "nightstand");

        assertThat(woodNightstand.getProductId()).isEqualTo("nightstand-classic-gullaberg-01");
        assertThat(grayNightstand.getProductId()).isEqualTo("nightstand-open-01");
        assertThat(woodNightstand.getProductId()).isNotEqualTo(grayNightstand.getProductId());
    }

    @Test
    void recommend_unsupportedColorTone_hasNoEffectAndFallsThroughGracefully() {
        // BEIGE_SAND 등 다섯 톤은 toMaterialTags()가 빈 Set이라, preferredColorTone이
        // null일 때와 동일하게(예외 없이) style/lifestyle/room-fit/catalog-order로만
        // 결정돼야 한다.
        Room room = room(5.0, 5.0);
        AgentContext withUnsupportedTone = new AgentContext(1L, null, List.of(),
                List.of("nightstand"), List.of(), List.of(), List.of(), List.of(), PreferredColorTone.BEIGE_SAND);
        AgentContext withoutTone = new AgentContext(1L, null, List.of(),
                List.of("nightstand"), List.of(), List.of(), List.of(), List.of());

        Furniture withTone = findByType(service.recommend(withUnsupportedTone, room).getRecommendedFurniture(), "nightstand");
        Furniture withoutToneFurniture = findByType(service.recommend(withoutTone, room).getRecommendedFurniture(), "nightstand");

        assertThat(withTone.getProductId()).isEqualTo(withoutToneFurniture.getProductId());
    }

    @Test
    void recommend_normalRoomName_isNotRoutedToScriptedDemoRecommendation() {
        // scriptedRecommendation()은 room.getName()이 정확히 "미드센추리 컬렉터 룸"
        // 또는 "샘플룸2"일 때만 탄다. 다른 이름(또는 이름 없음)의 방에서는 항상
        // Context 기반 추천 경로를 타야 한다 — requiredItems에 없는 타입("desk")이
        // scripted 목록에는 있지만 여기 결과엔 없어야 한다는 것으로 증명한다.
        Room room = room(5.0, 5.0);
        AgentContext context = new AgentContext(1L, LifestyleGoal.RELAX_FOCUSED, List.of(DesignStyle.NATURAL),
                List.of("bed"), List.of(), List.of(), List.of(), List.of(), PreferredColorTone.BROWN_WOOD);

        PlacementResult result = service.recommend(context, room);

        assertThat(result.getRecommendedFurniture()).hasSize(1);
        assertThat(result.getRecommendedFurniture().get(0).getType()).isEqualTo("bed");
        assertThat(result.getRecommendedFurniture().get(0).getProductId()).isEqualTo("bed-low-platform-01");
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
