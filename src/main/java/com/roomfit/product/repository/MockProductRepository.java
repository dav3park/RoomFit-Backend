package com.roomfit.product.repository;

import com.roomfit.product.domain.MockProduct;
import com.roomfit.product.domain.RequiredClearance;
import com.roomfit.product.catalog.GeneratedFurnitureCatalog;
import org.springframework.stereotype.Repository;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

@Repository
public class MockProductRepository {

    private final List<MockProduct> products;

    public MockProductRepository() {
        this(defaultProducts());
    }

    public MockProductRepository(List<MockProduct> products) {
        this.products = List.copyOf(products);
        validateUniqueProductIds(this.products);
    }

    private static List<MockProduct> defaultProducts() {
        List<MockProduct> legacyProducts = List.of(
            new MockProduct(
                    "bed-01",
                    "bed",
                    "화이트 싱글 침대",
                    "RoomFit Mock",
                    1.1,
                    2.0,
                    0.45,
                    129000,
                    List.of("minimal", "white_tone", "relax"),
                    "/images/products/bed-white.png",
                    new RequiredClearance(0.5, 0.2)
            ),
            new MockProduct(
                    "desk-01",
                    "desk",
                    "화이트 미니멀 책상",
                    "RoomFit Mock",
                    1.2,
                    0.6,
                    0.72,
                    89000,
                    List.of("minimal", "white_tone", "study"),
                    "/images/products/desk-white.png",
                    "https://www.ikea.com/kr/ko/p/micke-desk-white-80354281/",
                    new RequiredClearance(0.6, 0.2)
            ),
            new MockProduct(
                    "chair-01",
                    "chair",
                    "화이트 기본 의자",
                    "RoomFit Mock",
                    0.45,
                    0.45,
                    0.8,
                    39000,
                    List.of("minimal", "white_tone", "study"),
                    "/images/products/chair-white.png",
                    "https://www.ikea.com/kr/ko/p/hauga-chair-white-50579215/",
                    new RequiredClearance(0.4, 0.1)
            ),
            new MockProduct(
                    "storage-01",
                    "storage",
                    "우드 수납장",
                    "RoomFit Mock",
                    0.8,
                    0.4,
                    1.2,
                    79000,
                    List.of("natural", "wood_tone", "storage"),
                    "/images/products/storage-wood.png",
                    new RequiredClearance(0.5, 0.2)
            ),
            new MockProduct(
                    "rug-01",
                    "rug",
                    "코지 원형 러그",
                    "RoomFit Mock",
                    1.2,
                    1.2,
                    0.02,
                    29000,
                    List.of("cozy", "natural", "open_space"),
                    "/images/products/rug-cozy.png",
                    new RequiredClearance(0.1, 0.1)
            ),
            new MockProduct(
                    "lamp-01",
                    "lamp",
                    "미니멀 스탠드 조명",
                    "RoomFit Mock",
                    0.25,
                    0.25,
                    1.2,
                    29000,
                    List.of("minimal", "cozy", "study"),
                    "/images/products/lamp-minimal.png",
                    new RequiredClearance(0.2, 0.1)
            )
        );
        return Stream.concat(legacyProducts.stream(), GeneratedFurnitureCatalog.get().products().stream()).toList();
    }

    private static void validateUniqueProductIds(List<MockProduct> products) {
        Set<String> productIds = new HashSet<>();
        for (MockProduct product : products) {
            if (!productIds.add(product.getProductId())) {
                throw new IllegalArgumentException("Duplicate productId: " + product.getProductId());
            }
        }
    }

    public List<MockProduct> findAll() {
        return products;
    }

    public Optional<MockProduct> findById(String productId) {
        return products.stream()
                .filter(product -> product.getProductId().equals(productId))
                .findFirst();
    }
}
