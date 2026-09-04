package com.roomfit.product.dto.response;

import com.roomfit.product.domain.MockProduct;
import com.roomfit.product.service.PurchaseUrlHealthCheckService;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Schema(description = "MVP 제품 카드 렌더링용 Mock Product 응답")
public class MockProductResponse {

    @Schema(description = "제품 ID. Agent Context의 selectedProductIds에 사용됩니다.", example = "desk-01")
    private final String productId;
    @Schema(description = "JSON 기반 Furniture Variant Registry 식별자. 기존 제품은 null 허용",
            example = "desk-compact", pattern = "^[a-z0-9]+(?:-[a-z0-9]+)*$", nullable = true)
    private final String variantId;
    @Schema(description = "제품 가구 타입", example = "desk")
    private final String type;
    @Schema(description = "제품명", example = "화이트 미니멀 책상")
    private final String name;
    @Schema(description = "브랜드명. 확인된 브랜드 정보가 없으면 null", example = "RoomFit Mock", nullable = true)
    private final String brand;
    @Schema(description = "제품 가로 길이(meter)", example = "1.2")
    private final double width;
    @Schema(description = "제품 깊이(meter)", example = "0.6")
    private final double depth;
    @Schema(description = "제품 높이(meter)", example = "0.72")
    private final double height;
    @Schema(description = "가격(원). 확인된 원화 가격이 없으면 null이며 0원으로 해석하지 않습니다.",
            example = "89000", nullable = true)
    private final Integer price;
    @Schema(description = "추천/스타일 계산에 사용되는 태그")
    private final List<String> styleTags;
    @Schema(description = "제품 이미지 URL. 연결된 이미지가 없으면 null", example = "/images/products/desk-white.png",
            nullable = true)
    private final String imageUrl;
    @Schema(description = "유사한 구매 가능 제품의 fallback URL. 연결된 제품이 없으면 null입니다.",
            example = "https://www.ikea.com/kr/ko/p/micke-desk-white-80354281/",
            format = "uri", nullable = true)
    private final String purchaseUrl;
    @Schema(description = "가장 최근 purchaseUrl 헬스체크 결과. 아직 한 번도 체크되지 않았으면 null",
            example = "ALIVE", nullable = true)
    private final String purchaseUrlStatus;
    @Schema(description = "purchaseUrl을 마지막으로 확인한 시각. 아직 체크되지 않았으면 null", nullable = true)
    private final LocalDateTime purchaseUrlCheckedAt;
    @Schema(description = "가구 앞/옆 권장 여유 공간")
    private final RequiredClearanceResponse requiredClearance;

    private MockProductResponse(String productId, String variantId, String type, String name, String brand,
                                double width, double depth, double height, Integer price,
                                List<String> styleTags, String imageUrl, String purchaseUrl,
                                String purchaseUrlStatus, LocalDateTime purchaseUrlCheckedAt,
                                RequiredClearanceResponse requiredClearance) {
        this.productId = productId;
        this.variantId = variantId;
        this.type = type;
        this.name = name;
        this.brand = brand;
        this.width = width;
        this.depth = depth;
        this.height = height;
        this.price = price;
        this.styleTags = styleTags;
        this.imageUrl = imageUrl;
        this.purchaseUrl = purchaseUrl;
        this.purchaseUrlStatus = purchaseUrlStatus;
        this.purchaseUrlCheckedAt = purchaseUrlCheckedAt;
        this.requiredClearance = requiredClearance;
    }

    public static MockProductResponse from(MockProduct product) {
        return from(product, Optional.empty());
    }

    public static MockProductResponse from(MockProduct product, Optional<PurchaseUrlHealthCheckService.UrlHealth> health) {
        return new MockProductResponse(
                product.getProductId(),
                product.getVariantId(),
                product.getType(),
                product.getName(),
                product.getBrand(),
                product.getWidth(),
                product.getDepth(),
                product.getHeight(),
                product.getPrice(),
                product.getStyleTags(),
                product.getImageUrl(),
                product.getPurchaseUrl(),
                health.map(h -> h.status().name()).orElse(null),
                health.map(PurchaseUrlHealthCheckService.UrlHealth::checkedAt).orElse(null),
                RequiredClearanceResponse.from(product.getRequiredClearance())
        );
    }

    public String getProductId() {
        return productId;
    }

    public String getVariantId() {
        return variantId;
    }

    public String getType() {
        return type;
    }

    public String getName() {
        return name;
    }

    public String getBrand() {
        return brand;
    }

    public double getWidth() {
        return width;
    }

    public double getDepth() {
        return depth;
    }

    public double getHeight() {
        return height;
    }

    public Integer getPrice() {
        return price;
    }

    public List<String> getStyleTags() {
        return styleTags;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public String getPurchaseUrl() {
        return purchaseUrl;
    }

    public String getPurchaseUrlStatus() {
        return purchaseUrlStatus;
    }

    public LocalDateTime getPurchaseUrlCheckedAt() {
        return purchaseUrlCheckedAt;
    }

    public RequiredClearanceResponse getRequiredClearance() {
        return requiredClearance;
    }
}
