package com.roomfit.product.catalog;

import com.roomfit.product.domain.MockProduct;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class GeneratedFurnitureCatalogTest {

    private static final Set<String> CANONICAL_TYPES = Set.of(
            "bed", "bookshelf", "curtain_blind", "desk", "desk_chair", "drawer_chest",
            "full_length_mirror", "hanger", "media_console", "monitor", "mood_lamp",
            "multi_table", "nightstand", "partition_shelf", "plant", "rug", "side_table",
            "sofa", "sofa_bed", "tv", "wardrobe"
    );

    @Test
    void packagedCatalogLoadsAllProductsWithUniqueIdsAndValidDimensions() {
        GeneratedFurnitureCatalog catalog = GeneratedFurnitureCatalog.get();

        assertThat(catalog.products()).hasSize(193);
        assertThat(catalog.variantIds()).hasSize(193);
        assertThat(catalog.products()).extracting(MockProduct::getProductId).doesNotHaveDuplicates();
        assertThat(catalog.products()).extracting(MockProduct::getVariantId).doesNotHaveDuplicates();
        assertThat(catalog.products()).allMatch(product -> product.getProductId().equals(product.getVariantId() + "-01"));
        assertThat(catalog.products()).allMatch(product -> product.getWidth() > 0
                && product.getDepth() > 0 && product.getHeight() > 0);
        assertThat(catalog.variantIds()).allMatch(variantId -> catalog.visualFootprint(variantId).isPresent());
        assertThat(catalog.products().stream().map(MockProduct::getType).collect(Collectors.toSet()))
                .containsExactlyInAnyOrderElementsOf(CANONICAL_TYPES);
        assertThat(catalog.sourceHash()).matches("sha256:[a-f0-9]{64}");
    }

    @Test
    void visualFootprintsPreserveGeneratedGeometryBoundsAndOffsets() {
        GeneratedFurnitureCatalog catalog = GeneratedFurnitureCatalog.get();
        GeneratedFurnitureCatalog.VisualFootprint bed = catalog.visualFootprint("bed-classic-idanaes").orElseThrow();
        GeneratedFurnitureCatalog.VisualFootprint plant = catalog.visualFootprint("plant-corner").orElseThrow();

        assertThat(bed.minX()).isEqualTo(-0.785000026);
        assertThat(bed.maxX()).isEqualTo(0.785000026);
        assertThat(bed.minZ()).isEqualTo(-1.11);
        assertThat(bed.maxZ()).isEqualTo(1.11);
        assertThat(plant.maxX() - plant.minX()).isGreaterThan(0.6005779884792202);
        assertThat((plant.minX() + plant.maxX()) / 2.0).isNotZero();
    }

    @Test
    void aliasesNormalizeWithoutCollapsingDistinctStorageTypes() {
        GeneratedFurnitureCatalog catalog = GeneratedFurnitureCatalog.get();

        assertThat(catalog.normalizeType("MOOD_LAMP")).isEqualTo("mood_lamp");
        assertThat(catalog.normalizeType("lamp")).isEqualTo("mood_lamp");
        assertThat(catalog.normalizeType("lighting")).isEqualTo("mood_lamp");
        assertThat(catalog.normalizeType("side table")).isEqualTo("side_table");
        assertThat(catalog.normalizeType("bedside table")).isEqualTo("nightstand");
        assertThat(catalog.normalizeType("bookshelf")).isEqualTo("bookshelf");
        assertThat(catalog.normalizeType("hanger")).isEqualTo("hanger");
        assertThat(catalog.normalizeType("wardrobe")).isEqualTo("wardrobe");
        assertThat(catalog.normalizeType("storage")).isEqualTo("storage");
    }

    @Test
    void establishedDeskProductIdsAndContractsRemainAvailable() {
        GeneratedFurnitureCatalog catalog = GeneratedFurnitureCatalog.get();

        assertThat(catalog.products()).filteredOn(product -> product.getType().equals("desk"))
                .extracting(MockProduct::getProductId)
                .contains("desk-compact-01", "desk-storage-01", "desk-corner-01", "desk-midcentury-glass-01");
    }
}
