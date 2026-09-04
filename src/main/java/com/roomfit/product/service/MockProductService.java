package com.roomfit.product.service;

import com.roomfit.common.CustomException;
import com.roomfit.common.ErrorCode;
import com.roomfit.product.domain.MockProduct;
import com.roomfit.product.dto.response.MockProductResponse;
import com.roomfit.product.repository.MockProductRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MockProductService {

    private final MockProductRepository mockProductRepository;
    private final PurchaseUrlHealthCheckService purchaseUrlHealthCheckService;

    public MockProductService(MockProductRepository mockProductRepository,
                              PurchaseUrlHealthCheckService purchaseUrlHealthCheckService) {
        this.mockProductRepository = mockProductRepository;
        this.purchaseUrlHealthCheckService = purchaseUrlHealthCheckService;
    }

    public List<MockProductResponse> getMockProducts() {
        return mockProductRepository.findAll().stream()
                .map(product -> MockProductResponse.from(product,
                        purchaseUrlHealthCheckService.getHealth(product.getProductId())))
                .toList();
    }

    public MockProduct findByProductId(String productId) {
        return mockProductRepository.findById(productId)
                .orElseThrow(() -> new CustomException(ErrorCode.PRODUCT_NOT_FOUND));
    }

    public List<MockProduct> findByProductIds(List<String> productIds) {
        return productIds.stream()
                .map(this::findByProductId)
                .toList();
    }
}
