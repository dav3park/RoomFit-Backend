package com.roomfit.placement;

import com.roomfit.product.domain.MockProduct;
import com.roomfit.product.catalog.GeneratedFurnitureCatalog;
import com.roomfit.product.repository.MockProductRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class RenderableProductCatalog {

    public static final int MAX_PRODUCT_CANDIDATES = 4;

    private static final Set<String> WEB_LEGACY_TYPES = Set.of(
            "bed", "desk", "chair", "storage", "rug", "lamp"
    );

    private final List<MockProduct> products;
    private final GeneratedFurnitureCatalog generatedCatalog;

    @Autowired
    public RenderableProductCatalog(MockProductRepository repository) {
        this(repository.findAll(), GeneratedFurnitureCatalog.get());
    }

    RenderableProductCatalog(List<MockProduct> products) {
        this(products, GeneratedFurnitureCatalog.get());
    }

    private RenderableProductCatalog(List<MockProduct> products, GeneratedFurnitureCatalog generatedCatalog) {
        this.products = List.copyOf(products);
        this.generatedCatalog = generatedCatalog;
        validateCatalog(this.products);
    }

    public List<MockProduct> findCandidates(FeedbackProductRequirements requirements, FurnitureSize referenceSize) {
        List<MockProduct> matching = matchingCandidates(requirements, referenceSize).toList();
        boolean hasGeneratedProduct = matching.stream().anyMatch(product -> product.getVariantId() != null);
        // Legacy shapes are fallbacks; generated products carry canonical renderer metadata.
        List<MockProduct> candidates = matching.stream()
                .filter(product -> !hasGeneratedProduct || product.getVariantId() != null)
                .limit(MAX_PRODUCT_CANDIDATES)
                .toList();
        return List.copyOf(candidates);
    }

    /** Full catalog result for a metadata-constrained swap; it must not silently truncate ambiguity. */
    public List<MockProduct> findSwapCandidates(FeedbackProductRequirements requirements, FurnitureSize referenceSize) {
        return matchingCandidates(requirements, referenceSize).toList();
    }

    private java.util.stream.Stream<MockProduct> matchingCandidates(FeedbackProductRequirements requirements,
                                                                      FurnitureSize referenceSize) {
        return products.stream()
                .filter(this::isRenderable)
                .filter(product -> sameFurnitureType(requirements.furnitureType(), product.getType()))
                .filter(product -> !requirements.storagePreferred() || hasStorage(product))
                .filter(product -> containsStyleKeywords(product, requirements.styleKeywords()))
                .sorted(productComparator(requirements.sizePreference(), referenceSize));
    }

    public boolean isRenderable(MockProduct product) {
        if (product.getVariantId() != null) {
            return generatedCatalog.variantIds().contains(product.getVariantId());
        }
        return WEB_LEGACY_TYPES.contains(product.getType());
    }

    public Set<String> supportedVariantIds() {
        return generatedCatalog.variantIds();
    }

    public RenderCapability renderCapability(MockProduct product) {
        if (product.getVariantId() != null && generatedCatalog.variantIds().contains(product.getVariantId())) {
            return RenderCapability.VARIANT_JSON;
        }
        if (product.getVariantId() == null && WEB_LEGACY_TYPES.contains(product.getType())) {
            return RenderCapability.LEGACY_RENDERER;
        }
        return RenderCapability.UNSUPPORTED;
    }

    public boolean sameFurnitureType(String first, String second) {
        return generatedCatalog.sameType(first, second);
    }

    private Comparator<MockProduct> productComparator(FeedbackSizePreference preference, FurnitureSize referenceSize) {
        Comparator<MockProduct> sizeComparator = switch (preference) {
            case SMALL -> Comparator.comparingDouble(this::footprintArea);
            case LARGE -> Comparator.comparingDouble(this::footprintArea).reversed();
            case SIMILAR -> Comparator.comparingDouble(product -> sizeDistance(product, referenceSize));
            case ANY -> (first, second) -> 0;
        };
        return sizeComparator.thenComparing(MockProduct::getProductId);
    }

    private double sizeDistance(MockProduct product, FurnitureSize referenceSize) {
        if (referenceSize == null) {
            return 0;
        }
        return Math.abs(product.getWidth() - referenceSize.width())
                + Math.abs(product.getDepth() - referenceSize.depth())
                + Math.abs(product.getHeight() - referenceSize.height());
    }

    private double footprintArea(MockProduct product) {
        return product.getWidth() * product.getDepth();
    }

    private boolean containsStyleKeywords(MockProduct product, List<String> keywords) {
        if (keywords.isEmpty()) {
            return true;
        }
        Set<String> tags = new HashSet<>();
        product.getStyleTags().forEach(tag -> tags.add(tag.toLowerCase(Locale.ROOT)));
        product.getMaterials().forEach(material -> tags.add(material.toLowerCase(Locale.ROOT)));
        return keywords.stream()
                .map(keyword -> keyword.toLowerCase(Locale.ROOT))
                .allMatch(tags::contains);
    }

    private boolean hasStorage(MockProduct product) {
        return product.getStyleTags().stream().anyMatch(tag -> "storage".equalsIgnoreCase(tag))
                || product.getLifestyleTags().stream().anyMatch(tag -> "STORAGE".equalsIgnoreCase(tag))
                || product.getVariantId() != null && product.getVariantId().contains("storage");
    }

    private void validateCatalog(List<MockProduct> catalogProducts) {
        if (catalogProducts == null) {
            throw new IllegalArgumentException("Renderable product catalog must not be null");
        }
        Set<String> productIds = new HashSet<>();
        Set<String> variantIds = new HashSet<>();
        List<String> errors = new ArrayList<>();
        for (MockProduct product : catalogProducts) {
            if (product == null) {
                errors.add("null product");
                continue;
            }
            if (product.getProductId() == null || product.getProductId().isBlank()) {
                errors.add("missing productId");
            } else if (!productIds.add(product.getProductId())) {
                errors.add("duplicate productId: " + product.getProductId());
            }
            if (product.getVariantId() != null && !variantIds.add(product.getVariantId())) {
                errors.add("duplicate variantId: " + product.getVariantId());
            }
            if (product.getType() == null || product.getType().isBlank()) {
                errors.add("invalid type for product: " + product.getProductId());
            }
            if (!positiveFinite(product.getWidth()) || !positiveFinite(product.getDepth())
                    || !positiveFinite(product.getHeight())) {
                errors.add("missing or invalid dimensions for product: " + product.getProductId());
            }
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join(", ", errors));
        }
    }

    private boolean positiveFinite(double value) {
        return Double.isFinite(value) && value > 0;
    }

    public record FurnitureSize(double width, double depth, double height) {
    }

    public enum RenderCapability {
        VARIANT_JSON,
        LEGACY_RENDERER,
        UNSUPPORTED
    }
}
