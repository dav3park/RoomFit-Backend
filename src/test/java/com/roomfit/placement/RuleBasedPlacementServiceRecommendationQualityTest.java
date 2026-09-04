package com.roomfit.placement;

import com.roomfit.agent.domain.AgentContext;
import com.roomfit.agent.domain.DesignStyle;
import com.roomfit.agent.domain.LifestyleGoal;
import com.roomfit.common.CustomException;
import com.roomfit.common.ErrorCode;
import com.roomfit.product.repository.MockProductRepository;
import com.roomfit.product.service.MockProductService;
import com.roomfit.product.service.PurchaseUrlHealthCheckService;
import com.roomfit.product.service.ProductRecommendationService;
import com.roomfit.room.Furniture;
import com.roomfit.room.Room;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ProductRecommendationService 개선(styleScore/lifestyleScore comparator, room-fit
 * 우선)이 RuleBasedPlacementService 전체 파이프라인(selectedProductIds exact match,
 * legacy->generated 업그레이드, deterministic 반복, ValidationService 통과, 기존
 * FurnitureDomainPolicy 정책)을 깨지 않는지 확인한다. Mock 없이 실제
 * MockProductRepository/MockProductService/ProductRecommendationService/
 * ValidationService를 그대로 조립해 end-to-end로 검증한다.
 */
class RuleBasedPlacementServiceRecommendationQualityTest {

    private final MockProductService mockProductService = new MockProductService(new MockProductRepository(), new PurchaseUrlHealthCheckService(new MockProductRepository()));
    private final ProductRecommendationService productRecommendationService =
            new ProductRecommendationService(new MockProductRepository());
    private final ValidationService validationService = new ValidationService();
    private final FurnitureDomainPolicy furnitureDomainPolicy = new FurnitureDomainPolicy();
    private final RuleBasedPlacementService service =
            new RuleBasedPlacementService(mockProductService, productRecommendationService, validationService);

    @Test
    void recommend_selectedProductIdExactMatch_isNotOverriddenByLifestyleOrStylePreference() {
        // STORAGE_FOCUSED + style 없음이면 ProductRecommendationService만으로는
        // desk-storage-01이 유력하지만(STORAGE lifestyle 태그), 사용자가 명시적으로
        // desk-midcentury-glass-01을 selectedProductIds로 골랐다면 그게 그대로
        // 나와야 한다 — 3번 우선순위(exact match)가 항상 최우선이다.
        AgentContext context = new AgentContext(1L, LifestyleGoal.STORAGE_FOCUSED, List.of(),
                List.of("desk"), List.of(), List.of(), List.of("desk-midcentury-glass-01"), List.of());

        PlacementResult result = service.recommend(context, room(6.0, 6.0));

        Furniture desk = findByType(result.getRecommendedFurniture(), "desk");
        assertThat(desk.getProductId()).isEqualTo("desk-midcentury-glass-01");
    }

    @Test
    void recommend_sameInputTwice_producesIdenticalDeterministicResult() {
        AgentContext context = new AgentContext(1L, LifestyleGoal.STUDY_FOCUSED, List.of(DesignStyle.MODERN),
                List.of("desk", "desk_chair", "monitor"), List.of(), List.of(), List.of(), List.of());

        PlacementResult first = service.recommend(context, room(4.0, 4.0));
        PlacementResult second = service.recommend(context, room(4.0, 4.0));

        assertThat(second.getRecommendationStatus()).isEqualTo(first.getRecommendationStatus());
        assertThat(second.getPlacedFurnitureCount()).isEqualTo(first.getPlacedFurnitureCount());
        assertThat(furnitureSignatures(second.getRecommendedFurniture()))
                .isEqualTo(furnitureSignatures(first.getRecommendedFurniture()));
    }

    @Test
    void recommend_legacyTopChoice_isUpgradedToEquivalentGeneratedVariant() {
        // style/lifestyle이 어떤 desk와도 겹치지 않으면 전부 0점 동점이라 Catalog
        // 등록 순서상 첫 desk(legacy "desk-01", variantId 없음)로 fallback해야
        // 정상이지만, RuleBasedPlacementService는 legacy 선택을 렌더링 메타데이터가
        // 있는 동급 generated Variant(desk-compact)로 업그레이드해서 배치한다.
        AgentContext context = new AgentContext(1L, null, List.of(),
                List.of("desk"), List.of(), List.of(), List.of(), List.of());

        PlacementResult result = service.recommend(context, room(6.0, 6.0));

        Furniture desk = findByType(result.getRecommendedFurniture(), "desk");
        assertThat(desk.getProductId()).isEqualTo("desk-compact-01");
        assertThat(desk.getVariantId()).isEqualTo("desk-compact");
    }

    @Test
    void recommend_normalRoom_passesFullValidation() {
        AgentContext context = new AgentContext(1L, LifestyleGoal.STUDY_FOCUSED, List.of(DesignStyle.MODERN),
                List.of("desk", "desk_chair", "monitor"), List.of(), List.of(), List.of(), List.of());
        Room testRoom = room(4.0, 4.0);

        PlacementResult result = service.recommend(context, testRoom);

        assertThat(result.getRecommendationStatus()).isEqualTo(RecommendationExecutionStatus.SUCCESS);
        ValidationResult validation = validationService.validate(testRoom, result.getRecommendedFurniture());
        assertThat(validation.isCollisionFree()).isTrue();
        assertThat(validation.isBoundaryValid()).isTrue();
        assertThat(validation.isDoorClearance()).isTrue();
        assertThat(validation.isWindowClearance()).isTrue();
        assertThat(validation.isPathSecured()).isTrue();
    }

    @Test
    void recommend_bedAndDeskTogether_stillLetsFurnitureDomainPolicyCatchLoftDeskConflict() {
        // RuleBasedPlacementService 자체는 bed-loft-desk와 canonical desk의
        // 상호배제를 모른다(그건 FurnitureDomainPolicy의 책임) — 이번 추천 로직
        // 개선이 그 경계를 몰래 넘어 정책을 우회하지 않는지 확인한다. STUDY_FOCUSED
        // + style 없음이면 "bed" 요청에 bed-loft-desk(유일하게 WORK_STUDY 태그
        // 보유)가 선택된다.
        AgentContext context = new AgentContext(1L, LifestyleGoal.STUDY_FOCUSED, List.of(),
                List.of("bed", "desk"), List.of(), List.of(), List.of(), List.of());

        PlacementResult result = service.recommend(context, room(6.0, 6.0));

        Furniture bed = findByType(result.getRecommendedFurniture(), "bed");
        Furniture desk = findByType(result.getRecommendedFurniture(), "desk");
        assertThat(bed.getVariantId()).isEqualTo("bed-loft-desk");
        assertThat(desk.getType()).isEqualTo("desk");

        assertThatThrownBy(() -> furnitureDomainPolicy.validateFinalState(result.getRecommendedFurniture()))
                .isInstanceOf(CustomException.class)
                .satisfies(exception -> assertThat(((CustomException) exception).getErrorCode())
                        .isEqualTo(ErrorCode.FURNITURE_DOMAIN_CONFLICT));
    }

    private Furniture findByType(List<Furniture> furniture, String type) {
        return furniture.stream()
                .filter(item -> type.equals(item.getType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no furniture of type " + type + " in " + furniture));
    }

    private List<String> furnitureSignatures(List<Furniture> furniture) {
        return furniture.stream()
                .map(item -> item.getId() + ":" + item.getType() + ":" + item.getProductId() + ":"
                        + item.getVariantId() + ":" + item.getPosition().getX() + ":" + item.getPosition().getZ()
                        + ":" + item.getRotation())
                .toList();
    }

    private Room room(double width, double depth) {
        return new Room(null, width, depth, 2.4, "meter", List.of(), List.of());
    }
}
