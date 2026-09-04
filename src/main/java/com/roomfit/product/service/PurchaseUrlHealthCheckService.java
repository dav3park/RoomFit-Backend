package com.roomfit.product.service;

import com.roomfit.product.domain.MockProduct;
import com.roomfit.product.repository.MockProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Periodically probes every {@code MockProduct.purchaseUrl} so a stale/broken
 * outbound link (the source product page was removed, renamed, or moved) can
 * be surfaced to the client instead of silently sending users to a dead page.
 * Catalog products themselves stay immutable static data (see
 * {@link MockProductRepository}) — this service only tracks liveness
 * *alongside* that data in memory, keyed by productId.
 */
@Service
public class PurchaseUrlHealthCheckService {

    private static final Logger log = LoggerFactory.getLogger(PurchaseUrlHealthCheckService.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final MockProductRepository mockProductRepository;
    private final HttpClient httpClient;
    private final ExecutorService checkExecutor = Executors.newFixedThreadPool(8);
    private final Map<String, UrlHealth> healthByProductId = new ConcurrentHashMap<>();

    @Autowired
    public PurchaseUrlHealthCheckService(MockProductRepository mockProductRepository) {
        this(mockProductRepository, HttpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    // Package-private: lets tests inject a client pointed at a local test server.
    PurchaseUrlHealthCheckService(MockProductRepository mockProductRepository, HttpClient httpClient) {
        this.mockProductRepository = mockProductRepository;
        this.httpClient = httpClient;
    }

    @Scheduled(cron = "${roomfit.product-url-health-check.cron:0 0 4 * * *}")
    public void checkAllProductUrls() {
        List<MockProduct> products = mockProductRepository.findAll();
        List<CompletableFuture<Void>> checks = products.stream()
                .filter(product -> product.getPurchaseUrl() != null)
                .map(product -> CompletableFuture.runAsync(() -> checkAndRecord(product), checkExecutor))
                .toList();

        CompletableFuture.allOf(checks.toArray(new CompletableFuture[0])).join();
        log.info("[RoomFit] purchase URL health check finished: {} product(s) checked", checks.size());
    }

    /** Checks a single product's purchaseUrl synchronously and records the result. Public for tests/manual triggers. */
    public UrlHealth checkAndRecord(MockProduct product) {
        UrlHealth health = check(product.getPurchaseUrl());
        healthByProductId.put(product.getProductId(), health);
        return health;
    }

    public Optional<UrlHealth> getHealth(String productId) {
        return Optional.ofNullable(healthByProductId.get(productId));
    }

    private UrlHealth check(String purchaseUrl) {
        try {
            HttpRequest headRequest = HttpRequest.newBuilder(URI.create(purchaseUrl))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .timeout(REQUEST_TIMEOUT)
                    .build();
            HttpResponse<Void> response = httpClient.send(headRequest, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            // Some product pages don't support HEAD (405/501) — retry with GET
            // before concluding the link itself is broken.
            if (status == 405 || status == 501) {
                HttpRequest getRequest = HttpRequest.newBuilder(URI.create(purchaseUrl))
                        .GET()
                        .timeout(REQUEST_TIMEOUT)
                        .build();
                status = httpClient.send(getRequest, HttpResponse.BodyHandlers.discarding()).statusCode();
            }
            return new UrlHealth(status < 400 ? UrlStatus.ALIVE : UrlStatus.BROKEN, LocalDateTime.now());
        } catch (Exception e) {
            log.warn("[RoomFit] purchase URL check failed for {}: {}", purchaseUrl, e.toString());
            return new UrlHealth(UrlStatus.UNKNOWN, LocalDateTime.now());
        }
    }

    public enum UrlStatus {
        ALIVE, BROKEN, UNKNOWN
    }

    public record UrlHealth(UrlStatus status, LocalDateTime checkedAt) {
    }
}
