package com.roomfit.product.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.roomfit.product.domain.MockProduct;
import com.roomfit.product.domain.RequiredClearance;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class GeneratedFurnitureCatalog {

    public static final String RESOURCE_PATH = "/catalog/furniture-catalog.json";

    private final Map<String,FurniturePlacementPolicy> placementPolicies;
    private final String catalogVersion;
    private final String sourceHash;
    private final Map<String, String> typeAliases;
    private final List<MockProduct> products;
    private final Set<String> variantIds;
    private final Map<String, VisualFootprint> visualFootprints;

    private GeneratedFurnitureCatalog(CatalogDocument document) {
        validateDocument(document);
        this.catalogVersion = document.catalogVersion();
        this.sourceHash = document.sourceHash();
        this.typeAliases = Map.copyOf(document.typeAliases());
        this.products = document.products().stream().map(this::toProduct).toList();
        this.variantIds = this.products.stream().map(MockProduct::getVariantId).collect(java.util.stream.Collectors.toUnmodifiableSet());
        this.visualFootprints = document.products().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                CatalogProduct::variantId, CatalogProduct::visualFootprint));
        this.placementPolicies=document.products().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                CatalogProduct::variantId, product -> product.placement()==null?FurniturePlacementPolicy.legacy(product.furnitureType()):product.placement()));
        validateUniqueIds(this.products);
    }

    public static GeneratedFurnitureCatalog get() {
        return Holder.INSTANCE;
    }

    public Optional<FurniturePlacementPolicy> placementPolicy(String variantId) {
        return Optional.ofNullable(variantId==null?null:placementPolicies.get(variantId));
    }

    public List<MockProduct> products() {
        return products;
    }

    public Set<String> variantIds() {
        return variantIds;
    }

    public String catalogVersion() {
        return catalogVersion;
    }

    public String sourceHash() {
        return sourceHash;
    }

    public Optional<VisualFootprint> visualFootprint(String variantId) {
        return Optional.ofNullable(visualFootprints.get(variantId));
    }

    public String normalizeType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        String direct = typeAliases.get(trimmed);
        if (direct != null) {
            return direct;
        }
        String upper = typeAliases.get(trimmed.toUpperCase(Locale.ROOT));
        if (upper != null) {
            return upper;
        }
        String normalized = trimmed.toLowerCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
        return typeAliases.getOrDefault(normalized, normalized);
    }

    public boolean sameType(String first, String second) {
        String normalizedFirst = normalizeType(first);
        String normalizedSecond = normalizeType(second);
        return normalizedFirst != null && normalizedFirst.equals(normalizedSecond);
    }

    private MockProduct toProduct(CatalogProduct product) {
        if (!"VARIANT_JSON".equals(product.renderCapability())) {
            throw new IllegalArgumentException("Unsupported render capability for " + product.productId());
        }
        String canonicalType = normalizeType(product.furnitureTypeCode());
        if (!product.furnitureType().equals(canonicalType)) {
            throw new IllegalArgumentException("Canonical type mismatch for " + product.variantId());
        }
        if (!product.productId().equals(product.variantId() + "-01")) {
            throw new IllegalArgumentException("Product ID must be derived from variantId: " + product.variantId());
        }
        validateVisualFootprint(product.variantId(), product.visualFootprint());
        Dimensions dimensions = product.dimensions();
        Clearance clearance = product.requiredClearance();
        return new MockProduct(
                product.productId(), product.variantId(), product.furnitureType(), product.label(), null,
                dimensions.width(), dimensions.depth(), dimensions.height(), (Integer) null,
                product.styleTags(), null, product.purchaseUrl(),
                new RequiredClearance(clearance.front(), clearance.side()), product.lifestyleTags(), product.materials()
        );
    }

    private static void validateVisualFootprint(String variantId, VisualFootprint footprint) {
        if (footprint == null
                || !Double.isFinite(footprint.minX()) || !Double.isFinite(footprint.maxX())
                || !Double.isFinite(footprint.minZ()) || !Double.isFinite(footprint.maxZ())
                || footprint.minX() >= footprint.maxX() || footprint.minZ() >= footprint.maxZ()) {
            throw new IllegalArgumentException("Invalid visual footprint for generated variant: " + variantId);
        }
    }

    private static void validateDocument(CatalogDocument document) {
        if (document == null || !"1.0".equals(document.schemaVersion())) {
            throw new IllegalArgumentException("Generated furniture catalog must use schemaVersion 1.0");
        }
        if (document.catalogVersion() == null || document.catalogVersion().isBlank()
                || document.sourceHash() == null || !document.sourceHash().matches("sha256:[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Generated furniture catalog metadata is invalid");
        }
        if (document.typeAliases() == null || document.typeAliases().isEmpty()
                || document.products() == null || document.products().isEmpty()
                || document.variantCount() != document.products().size()) {
            throw new IllegalArgumentException("Generated furniture catalog contents are invalid");
        }
    }

    private static void validateUniqueIds(List<MockProduct> products) {
        Set<String> productIds = new HashSet<>();
        Set<String> variantIds = new HashSet<>();
        for (MockProduct product : products) {
            if (!productIds.add(product.getProductId())) {
                throw new IllegalArgumentException("Duplicate generated productId: " + product.getProductId());
            }
            if (!variantIds.add(product.getVariantId())) {
                throw new IllegalArgumentException("Duplicate generated variantId: " + product.getVariantId());
            }
        }
    }

    private static GeneratedFurnitureCatalog load() {
        try (InputStream input = GeneratedFurnitureCatalog.class.getResourceAsStream(RESOURCE_PATH)) {
            if (input == null) {
                throw new IllegalStateException("Missing generated furniture catalog resource " + RESOURCE_PATH);
            }
            CatalogDocument document = new ObjectMapper().readValue(input, CatalogDocument.class);
            return new GeneratedFurnitureCatalog(document);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load generated furniture catalog", e);
        }
    }

    private static final class Holder {
        private static final GeneratedFurnitureCatalog INSTANCE = load();
    }

    private record CatalogDocument(
            String schemaVersion,
            String catalogVersion,
            String sourceHash,
            int variantCount,
            LinkedHashMap<String, String> typeAliases,
            List<CatalogProduct> products
    ) {
    }

    private record CatalogProduct(
            String productId,
            String variantId,
            String furnitureType,
            String furnitureTypeCode,
            String label,
            Dimensions dimensions,
            VisualFootprint visualFootprint,
            List<String> styleTags,
            List<String> lifestyleTags,
            List<String> materials,
            String purchaseUrl,
            Clearance requiredClearance,
            String renderCapability,
            String variantPath,
            FurniturePlacementPolicy placement
    ) {
    }

    private record Dimensions(double width, double depth, double height) {
    }

    public record VisualFootprint(double minX, double maxX, double minZ, double maxZ) {
    }

    private record Clearance(double front, double side) {
    }
}
