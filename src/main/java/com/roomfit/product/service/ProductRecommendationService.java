package com.roomfit.product.service;

import com.roomfit.agent.domain.AgentContext;
import com.roomfit.agent.domain.DesignStyle;
import com.roomfit.agent.domain.LifestyleGoal;
import com.roomfit.agent.domain.PreferredColorTone;
import com.roomfit.product.domain.MockProduct;
import com.roomfit.product.catalog.GeneratedFurnitureCatalog;
import com.roomfit.product.repository.MockProductRepository;
import com.roomfit.room.Room;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 사용자가 특정 Product를 정확히 고르지 않은 가구 타입(예: "desk" 유형만 선택)에 대해
 * Catalog에서 Product 하나를 고른다. 호출부(RuleBasedPlacementService)가 이미
 * selectedProductIds의 exact match를 우선 처리하므로, 이 서비스는 그 매치가 없을
 * 때만 호출된다.
 *
 * 선택 기준(우선순위 순 — 점수를 합산하지 않고, 각 기준을 별도 단계로 비교하는
 * comparator 체인이다):
 * 1. styleScore(정규화된 designStyle과 product.styleTags 교집합 개수) 높은 순
 * 2. 그래도 동점이면 lifestyleScore(정규화된 lifestyleGoal과 product.lifestyleTags
 *    교집합 개수) 높은 순
 * 3. 그래도 동점이면 colorToneScore(PreferredColorTone.toMaterialTags()와
 *    product.materialTags 교집합 개수) 높은 순
 * 4. 이 선호도 순서 안에서, 방에 실제로 들어갈 수 있는(치수+clearance) 후보를
 *    우선한다 — 단, 들어가는 후보가 하나도 없을 때만 안 들어가는 후보를 허용한다.
 *    (앞 세 점수의 합이 가장 높은 후보라도 방에 안 들어가면, 그보다 점수가
 *    낮더라도 실제로 들어가는 후보를 대신 고른다 — 확정 실패가 예정된 후보를
 *    점수만 보고 고르지 않기 위함.)
 * 5. 그래도 동점이면 Catalog 등록 순서상 첫 번째 (deterministic fallback)
 *
 * style/lifestyle/colorTone을 하나의 합산 점수로 섞으면 "style 불일치 + 나머지
 * 2개 일치"와 "style 1개 일치 + 나머지 불일치"가 같은 점수로 동률 처리되어
 * 버린다 — 이는 style을 lifestyle보다, lifestyle을 colorTone보다 우선하기로 한
 * 결정과 어긋난다. 그래서 점수를 합산하지 않고 별도의 comparator 단계로
 * 순서대로 비교한다.
 *
 * JSON Variant Product에는 Furniture Catalog의 공식 lifestyleTags/materialTags가
 * 채워져 있고, 기존 legacy Product는 둘 다 빈 리스트를 유지한다.
 *
 * preferredColorTone 8종 중 BROWN_WOOD/WHITE_IVORY/GRAY만 현재 material id
 * 어휘(PreferredColorTone.toMaterialTags() 참고)와 명확히 대응된다 — 나머지
 * 다섯 톤은 toMaterialTags()가 빈 Set을 돌려줘 colorToneScore가 항상 0이 되고,
 * 결과적으로 이 comparator 단계에서는 아무 영향이 없다(그다음 단계로 자연히
 * 넘어간다).
 */
@Service
public class ProductRecommendationService {

    private final MockProductRepository mockProductRepository;

    public ProductRecommendationService(MockProductRepository mockProductRepository) {
        this.mockProductRepository = mockProductRepository;
    }

    public Optional<MockProduct> recommend(String type, AgentContext context, Room room) {
        return rankedCandidates(type, context, room).stream().findFirst();
    }

    public List<MockProduct> rankedCandidates(String type, AgentContext context, Room room) {
        List<MockProduct> candidates = mockProductRepository.findAll().stream()
                .filter(product -> GeneratedFurnitureCatalog.get().sameType(type, product.getType()))
                .toList();

        if (candidates.isEmpty()) {
            return List.of();
        }

        Set<String> normalizedStyleTags = normalizeDesignStyle(context.getDesignStyle());
        Set<String> normalizedLifestyleTags = normalizeLifestyleGoal(context.getLifestyleGoal());
        Set<String> normalizedColorToneTags = normalizePreferredColorTone(context.getPreferredColorTone());

        // Stream.sorted()는 stable sort라, 이 세 점수가 모두 동점인 후보들은
        // candidates(= Catalog 등록 순서)의 상대 순서를 그대로 유지한다 — 그게 곧
        // 5번째 기준(등록 순서 fallback)이라 별도 tie-break 키가 필요 없다.
        Comparator<MockProduct> byPreference = Comparator
                .comparingInt((MockProduct product) -> styleOverlapScore(product, normalizedStyleTags))
                .reversed()
                .thenComparing(Comparator
                        .comparingInt((MockProduct product) -> lifestyleOverlapScore(product, normalizedLifestyleTags))
                        .reversed())
                .thenComparing(Comparator
                        .comparingInt((MockProduct product) -> colorToneOverlapScore(product, normalizedColorToneTags))
                        .reversed());

        List<MockProduct> byPreferenceOrder = candidates.stream().sorted(byPreference).toList();

        // byPreferenceOrder의 상대 순서를 그대로 유지한 채 필터링하므로, 이 목록의
        // 첫 번째 원소가 곧 "들어가는 후보 중 선호도가 가장 높은 것"이다.
        List<MockProduct> fittable = byPreferenceOrder.stream()
                .filter(product -> fitsInRoom(product, room))
                .toList();
        List<MockProduct> pool = fittable.isEmpty() ? byPreferenceOrder : fittable;

        return pool;
    }

    private Set<String> normalizeDesignStyle(List<DesignStyle> designStyle) {
        Set<String> tags = new LinkedHashSet<>();
        if (designStyle != null) {
            designStyle.forEach(style -> tags.add(style.toStyleTag()));
        }
        return tags;
    }

    private Set<String> normalizeLifestyleGoal(LifestyleGoal lifestyleGoal) {
        if (lifestyleGoal == null) {
            return Set.of();
        }
        return Set.of(lifestyleGoal.toLifestyleTag());
    }

    private Set<String> normalizePreferredColorTone(PreferredColorTone preferredColorTone) {
        if (preferredColorTone == null) {
            return Set.of();
        }
        return preferredColorTone.toMaterialTags();
    }

    private int styleOverlapScore(MockProduct product, Set<String> normalizedStyleTags) {
        return overlapCount(product.getStyleTags(), normalizedStyleTags);
    }

    private int lifestyleOverlapScore(MockProduct product, Set<String> normalizedLifestyleTags) {
        return overlapCount(product.getLifestyleTags(), normalizedLifestyleTags);
    }

    private int colorToneOverlapScore(MockProduct product, Set<String> normalizedColorToneTags) {
        return overlapCount(product.getMaterialTags(), normalizedColorToneTags);
    }

    private int overlapCount(List<String> productTags, Set<String> normalizedContextTags) {
        if (normalizedContextTags.isEmpty()) {
            return 0;
        }
        return (int) productTags.stream()
                .filter(normalizedContextTags::contains)
                .count();
    }

    private boolean fitsInRoom(MockProduct product, Room room) {
        boolean floorOverlay = "rug".equals(GeneratedFurnitureCatalog.get().normalizeType(product.getType()));
        double requiredWidth = product.getWidth()
                + (floorOverlay ? 0 : product.getRequiredClearance().getSide() * 2);
        double requiredDepth = product.getDepth()
                + (floorOverlay ? 0 : product.getRequiredClearance().getFront());
        return requiredWidth <= room.getWidth() && requiredDepth <= room.getDepth();
    }
}
