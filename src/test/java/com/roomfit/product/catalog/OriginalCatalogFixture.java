package com.roomfit.product.catalog;
import com.roomfit.product.repository.MockProductRepository;
/** Fixed 93-product snapshot for tests whose premise is a unique metadata match or the old minimum size. */
public final class OriginalCatalogFixture {
 private OriginalCatalogFixture() {}
 public static MockProductRepository repository(){return new MockProductRepository(new MockProductRepository().findAll().stream()
     .filter(product->!product.getProductId().startsWith("expansion-")).toList());}
}
