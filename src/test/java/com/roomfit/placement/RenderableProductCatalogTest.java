package com.roomfit.placement;

import com.roomfit.product.domain.MockProduct;
import com.roomfit.product.domain.RequiredClearance;
import com.roomfit.product.repository.MockProductRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RenderableProductCatalogTest {

    @Test
    void productionCatalogOnlyExposesWebRegisteredVariantsOrLegacyProducts() {
        RenderableProductCatalog catalog = new RenderableProductCatalog(new MockProductRepository());

        List<MockProduct> deskCandidates = catalog.findCandidates(
                requirements("desk"), null);

        assertThat(deskCandidates).hasSize(RenderableProductCatalog.MAX_PRODUCT_CANDIDATES);
        assertThat(deskCandidates).allMatch(product -> product.getVariantId() == null
                || catalog.supportedVariantIds().contains(product.getVariantId()));
        assertThat(catalog.supportedVariantIds()).hasSize(193).contains(
                "desk-compact", "chair-midcentury-shell", "bookshelf-high", "hanger-basic",
                "nightstand-open", "sofa-single", "lamp-floor");
    }

    @Test
    void generatedProductsTakePrecedenceOverLegacyFallbacksForCanonicalRequests() {
        RenderableProductCatalog catalog = new RenderableProductCatalog(new MockProductRepository());

        assertThat(catalog.findCandidates(requirements("mood_lamp"), null))
                .isNotEmpty()
                .allSatisfy(product -> {
                    assertThat(product.getType()).isEqualTo("mood_lamp");
                    assertThat(product.getVariantId()).isNotBlank();
                });
    }

    @Test
    void legacyProductRemainsAvailableWhenNoMatchingGeneratedProductExists() {
        MockProduct legacy = product("lamp-legacy", null, "lamp", 0.2, 0.2, 1);
        RenderableProductCatalog catalog = new RenderableProductCatalog(List.of(legacy));

        assertThat(catalog.findCandidates(requirements("mood_lamp"), null)).containsExactly(legacy);
    }

    @Test
    void metadataConstrainedSwapKeepsLegacyMatchesForAmbiguityChecks() {
        MockProduct legacy = product("lamp-legacy", null, "lamp", 0.2, 0.2, 1);
        MockProduct generated = product("lamp-floor-01", "lamp-floor", "mood_lamp", 0.3, 0.3, 1.4);
        RenderableProductCatalog catalog = new RenderableProductCatalog(List.of(legacy, generated));
        FeedbackProductRequirements requirements = new FeedbackProductRequirements(
                "mood_lamp", FeedbackSizePreference.ANY, false, List.of("minimal"));

        assertThat(catalog.findSwapCandidates(requirements, null))
                .containsExactlyInAnyOrder(legacy, generated);
    }

    @Test
    void unsupportedFrontendVariantIsExcludedFromCandidates() {
        MockProduct unsupported = product("future-chair-01", "future-chair", "desk_chair", 0.5, 0.5, 0.8);
        RenderableProductCatalog catalog = new RenderableProductCatalog(List.of(unsupported));

        assertThat(catalog.findCandidates(requirements("chair"), null)).isEmpty();
    }

    @Test
    void duplicateProductOrVariantIdsAreRejected() {
        MockProduct first = product("desk-a", "desk-compact", "desk", 1.2, 0.6, 0.73);
        MockProduct duplicateProduct = product("desk-a", "desk-storage", "desk", 1.4, 0.62, 0.73);
        MockProduct duplicateVariant = product("desk-b", "desk-compact", "desk", 1.3, 0.7, 0.73);

        assertThatThrownBy(() -> new RenderableProductCatalog(List.of(first, duplicateProduct)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate productId: desk-a");
        assertThatThrownBy(() -> new RenderableProductCatalog(List.of(first, duplicateVariant)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate variantId: desk-compact");
    }

    @Test
    void invalidTypeOrMissingDimensionsAreRejected() {
        MockProduct missingType = product("missing-type", null, "", 1, 1, 1);
        MockProduct missingDimensions = product("missing-size", null, "desk", 0, 1, 1);

        assertThatThrownBy(() -> new RenderableProductCatalog(List.of(missingType)))
                .hasMessageContaining("invalid type");
        assertThatThrownBy(() -> new RenderableProductCatalog(List.of(missingDimensions)))
                .hasMessageContaining("invalid dimensions");
    }

    @Test
    void renderCapabilityDistinguishesGeneratedLegacyAndUnsupportedProducts() {
        RenderableProductCatalog catalog = new RenderableProductCatalog(new MockProductRepository());
        MockProduct variant = new MockProductRepository().findById("lamp-floor-01").orElseThrow();
        MockProduct legacy = new MockProductRepository().findById("lamp-01").orElseThrow();
        MockProduct unsupported = product("future-01", "future-variant", "lamp", 0.2, 0.2, 1.0);

        assertThat(catalog.renderCapability(variant)).isEqualTo(RenderableProductCatalog.RenderCapability.VARIANT_JSON);
        assertThat(catalog.renderCapability(legacy)).isEqualTo(RenderableProductCatalog.RenderCapability.LEGACY_RENDERER);
        assertThat(catalog.renderCapability(unsupported)).isEqualTo(RenderableProductCatalog.RenderCapability.UNSUPPORTED);
    }

    private FeedbackProductRequirements requirements(String type) {
        return new FeedbackProductRequirements(type, FeedbackSizePreference.ANY, false, List.of());
    }

    private MockProduct product(String productId, String variantId, String type,
                                double width, double depth, double height) {
        return new MockProduct(productId, variantId, type, productId, null,
                width, depth, height, (Integer) null, List.of("minimal"), null, null,
                new RequiredClearance(0.2, 0.1));
    }
}
