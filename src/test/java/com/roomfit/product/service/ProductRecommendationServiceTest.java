package com.roomfit.product.service;

import com.roomfit.product.catalog.OriginalCatalogFixture;

import com.roomfit.agent.domain.AgentContext;
import com.roomfit.agent.domain.DesignStyle;
import com.roomfit.agent.domain.LifestyleGoal;
import com.roomfit.product.domain.MockProduct;
import com.roomfit.product.repository.MockProductRepository;
import com.roomfit.room.Room;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 MockProductRepository 기본 카탈로그(desk 5종: desk-01, desk-compact-01,
 * desk-storage-01, desk-corner-01, desk-midcentury-glass-01)를 그대로 사용해
 * ProductRecommendationService의 선택 기준(스타일 교집합 -> 방 배치 가능 여부 -> 등록 순서)을
 * 검증한다.
 */
class ProductRecommendationServiceTest {

    private final ProductRecommendationService service = new ProductRecommendationService(OriginalCatalogFixture.repository());

    @Test
    void recommend_singleStyleMatch_picksTheOnlyOverlappingProduct() {
        AgentContext context = context(List.of(DesignStyle.MIDCENTURY));

        Optional<MockProduct> result = service.recommend("desk", context, room(4.0, 4.0));

        assertThat(result).isPresent();
        assertThat(result.get().getProductId()).isEqualTo("desk-midcentury-glass-01");
    }

    @Test
    void recommend_tiedTotalScore_prefersProductThatFitsInRoom() {
        // "modern" 스타일 태그로는 desk-corner-01/desk-midcentury-glass-01이 동점(1점)이고,
        // STUDY_FOCUSED -> WORK_STUDY lifestyle 태그로도 둘 다 1점씩 더 받아 총점 2점으로
        // 계속 동점이다. 방을 좁게 잡아 desk-midcentury-glass-01(필요 가로 2.15m)은 못
        // 들어가고 desk-corner-01(필요 가로 1.7m)만 들어가게 한다.
        AgentContext context = context(List.of(DesignStyle.MODERN), LifestyleGoal.STUDY_FOCUSED);

        Optional<MockProduct> result = service.recommend("desk", context, room(2.0, 2.0));

        assertThat(result).isPresent();
        assertThat(result.get().getProductId()).isEqualTo("desk-corner-01");
    }

    @Test
    void recommend_noStyleOrLifestyleOverlap_fallsBackToCatalogRegistrationOrder() {
        // "cozy" 스타일 태그는 어떤 desk도 갖고 있지 않고, RELAX_FOCUSED -> "REST" lifestyle
        // 태그도 desk 카탈로그 어디에도 없어 전부 0점 동점 -> 등록 순서상 첫 desk
        // 항목(desk-01)으로 결정적으로 fallback한다.
        AgentContext context = context(List.of(DesignStyle.COZY), LifestyleGoal.RELAX_FOCUSED);

        Optional<MockProduct> result = service.recommend("desk", context, room(10.0, 10.0));

        assertThat(result).isPresent();
        assertThat(result.get().getProductId()).isEqualTo("desk-01");
    }

    @Test
    void recommend_lifestyleGoalBreaksStyleTie_prefersHigherLifestyleOverlap() {
        // designStyle을 비워 style 점수를 전부 0으로 무력화하면, STORAGE_FOCUSED ->
        // "STORAGE" lifestyle 태그를 가진 desk-storage-01/desk-corner-01만 1점으로
        // 동점 최상위가 된다(desk-01/desk-compact-01/desk-midcentury-glass-01은
        // STORAGE 태그가 없어 0점). 방 깊이를 desk-corner-01이 못 들어가게 좁혀
        // desk-storage-01만 남긴다.
        AgentContext context = context(List.of(), LifestyleGoal.STORAGE_FOCUSED);

        Optional<MockProduct> result = service.recommend("desk", context, room(2.0, 1.3));

        assertThat(result).isPresent();
        assertThat(result.get().getProductId()).isEqualTo("desk-storage-01");
    }

    @Test
    void recommend_typeNotInCatalog_returnsEmpty() {
        AgentContext context = context(List.of(DesignStyle.MINIMAL), LifestyleGoal.STUDY_FOCUSED);

        Optional<MockProduct> result = service.recommend("unsupported-type", context, room(4.0, 4.0));

        assertThat(result).isEmpty();
    }

    // --- 이 아래는 실제 Generated Catalog(93종)까지 포함한 전체 기본 카탈로그를
    // 그대로 사용하는 테스트다 — MockProductRepository()는 legacy 6종 뒤에
    // GeneratedFurnitureCatalog.get().products()를 이어붙인 전체 목록을 돌려준다.

    @Test
    void recommend_studyFocusedLifestyle_prefersWorkStudyTaggedBedVariant() {
        // bed 6종 중 WORK_STUDY lifestyleTag를 가진 건 bed-loft-desk 하나뿐이다
        // (나머지는 REST만 있음). style을 비워 style 점수를 전부 0으로 무력화하면
        // lifestyle 점수만으로 유일하게 앞서는 bed-loft-desk가 선택된다.
        AgentContext context = context(List.of(), LifestyleGoal.STUDY_FOCUSED);

        Optional<MockProduct> result = service.recommend("bed", context, room(10.0, 10.0));

        assertThat(result).isPresent();
        assertThat(result.get().getProductId()).isEqualTo("bed-loft-desk-01");
        assertThat(result.get().getVariantId()).isEqualTo("bed-loft-desk");
    }

    @Test
    void recommend_relaxFocusedLifestyle_differsFromStudyFocusedForSameType() {
        // bed 6종 전부가 REST lifestyleTag를 갖고 있어 RELAX_FOCUSED로는 서로
        // 동점이 되고 Catalog 등록 순서상 첫 bed(bed-classic-idanaes)로
        // deterministic fallback한다 — 위 STUDY_FOCUSED 테스트와 동일한 조건(같은
        // type, style 없음)에서 결과가 달라진다는 것 자체가 lifestyleGoal이 실제
        // 선택에 반영된다는 증거다.
        AgentContext context = context(List.of(), LifestyleGoal.RELAX_FOCUSED);

        Optional<MockProduct> result = service.recommend("bed", context, room(10.0, 10.0));

        assertThat(result).isPresent();
        assertThat(result.get().getProductId()).isEqualTo("bed-classic-idanaes-01");
    }

    @Test
    void recommend_hobbyFocusedLifestyle_prefersHobbyLeisureTaggedMoodLamp() {
        // mood_lamp 4종 중 HOBBY_LEISURE lifestyleTag가 있는 건
        // lamp-midcentury-globe 하나뿐이다(나머지 3개는 REST만 있음).
        AgentContext context = context(List.of(), LifestyleGoal.HOBBY_FOCUSED);

        Optional<MockProduct> result = service.recommend("mood_lamp", context, room(10.0, 10.0));

        assertThat(result).isPresent();
        assertThat(result.get().getProductId()).isEqualTo("lamp-midcentury-globe-01");
    }

    @Test
    void recommend_styleScoreOutranksLifestyleScore_notSummedTogether() {
        // bookshelf 5종 중 "midcentury" style 태그가 있는 건
        // bookshelf-midcentury-stockholm(Catalog 등록 순서상 마지막) 하나뿐이고,
        // WORK_STUDY lifestyle 태그가 있는 건 double-open/high/low 3종(각각 그보다
        // 앞선 순서)이다. 만약 style/lifestyle 점수를 합산했다면 style 1점짜리와
        // lifestyle 1점짜리가 총점 동점이 되어, Catalog 순서상 앞선
        // bookshelf-double-open이 선택됐을 것이다. style을 lifestyle보다 먼저
        // 비교하는 별도 단계로 두면 style 일치가 확정적으로 이겨야 한다.
        AgentContext context = context(List.of(DesignStyle.MIDCENTURY), LifestyleGoal.STUDY_FOCUSED);

        Optional<MockProduct> result = service.recommend("bookshelf", context, room(10.0, 10.0));

        assertThat(result).isPresent();
        assertThat(result.get().getProductId()).isEqualTo("bookshelf-midcentury-stockholm-01");
    }

    @Test
    void recommend_smallRoom_prefersFittableProductOverHigherScoringOversizedOne() {
        // "classic" style 태그가 있는 bed는 bed-classic-idanaes 하나뿐이라
        // style/lifestyle 점수만 보면 유일한 최상위 후보지만, 필요 가로폭이
        // 1.97m라 1.3m 폭 방에는 들어가지 않는다. 같은 방에는 REST만 있어 style은
        // 0점인 bed-low-platform(필요 가로폭 1.23m)이 들어간다 — 점수 1위가 방에
        // 안 들어가면, 점수가 낮아도 실제로 들어가는 후보를 대신 골라야 한다.
        AgentContext context = context(List.of(DesignStyle.CLASSIC), LifestyleGoal.RELAX_FOCUSED);

        Optional<MockProduct> result = service.recommend("bed", context, room(1.3, 3.0));

        assertThat(result).isPresent();
        assertThat(result.get().getProductId()).isEqualTo("bed-low-platform-01");
    }

    private AgentContext context(List<DesignStyle> designStyle) {
        return context(designStyle, LifestyleGoal.STUDY_FOCUSED);
    }

    private AgentContext context(List<DesignStyle> designStyle, LifestyleGoal lifestyleGoal) {
        return new AgentContext(1L, lifestyleGoal, designStyle,
                List.of("desk"), List.of(), List.of(1L), List.of(), List.of());
    }

    private Room room(double width, double depth) {
        return new Room(null, width, depth, 2.4, "meter", List.of(), List.of());
    }
}
