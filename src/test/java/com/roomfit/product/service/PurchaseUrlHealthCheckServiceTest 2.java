package com.roomfit.product.service;

import com.roomfit.product.domain.MockProduct;
import com.roomfit.product.domain.RequiredClearance;
import com.roomfit.product.repository.MockProductRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PurchaseUrlHealthCheckServiceTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void checkAndRecord_aliveUrl_recordsAlive() throws IOException {
        server = startServer(200);
        MockProduct product = product(url(server));
        PurchaseUrlHealthCheckService service = new PurchaseUrlHealthCheckService(
                new MockProductRepository(List.of(product)), HttpClient.newHttpClient());

        PurchaseUrlHealthCheckService.UrlHealth health = service.checkAndRecord(product);

        assertThat(health.status()).isEqualTo(PurchaseUrlHealthCheckService.UrlStatus.ALIVE);
        Optional<PurchaseUrlHealthCheckService.UrlHealth> stored = service.getHealth(product.getProductId());
        assertThat(stored).isPresent();
        assertThat(stored.get().status()).isEqualTo(PurchaseUrlHealthCheckService.UrlStatus.ALIVE);
    }

    @Test
    void checkAndRecord_brokenUrl_recordsBroken() throws IOException {
        server = startServer(404);
        MockProduct product = product(url(server));
        PurchaseUrlHealthCheckService service = new PurchaseUrlHealthCheckService(
                new MockProductRepository(List.of(product)), HttpClient.newHttpClient());

        PurchaseUrlHealthCheckService.UrlHealth health = service.checkAndRecord(product);

        assertThat(health.status()).isEqualTo(PurchaseUrlHealthCheckService.UrlStatus.BROKEN);
    }

    @Test
    void checkAndRecord_headNotAllowed_fallsBackToGet() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            if ("HEAD".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
            } else {
                exchange.sendResponseHeaders(200, -1);
            }
            exchange.close();
        });
        server.start();
        MockProduct product = product(url(server));
        PurchaseUrlHealthCheckService service = new PurchaseUrlHealthCheckService(
                new MockProductRepository(List.of(product)), HttpClient.newHttpClient());

        PurchaseUrlHealthCheckService.UrlHealth health = service.checkAndRecord(product);

        assertThat(health.status()).isEqualTo(PurchaseUrlHealthCheckService.UrlStatus.ALIVE);
    }

    @Test
    void getHealth_beforeAnyCheck_isEmpty() {
        PurchaseUrlHealthCheckService service = new PurchaseUrlHealthCheckService(
                new MockProductRepository(), HttpClient.newHttpClient());

        assertThat(service.getHealth("desk-01")).isEmpty();
    }

    private HttpServer startServer(int statusCode) throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        httpServer.createContext("/", exchange -> {
            exchange.sendResponseHeaders(statusCode, -1);
            exchange.close();
        });
        httpServer.start();
        return httpServer;
    }

    private String url(HttpServer httpServer) {
        return "http://localhost:" + httpServer.getAddress().getPort() + "/product";
    }

    private MockProduct product(String purchaseUrl) {
        return new MockProduct("desk-01", "desk", "테스트 책상", "RoomFit Mock",
                1.2, 0.6, 0.72, 89000, List.of("minimal"), "/images/products/desk.png",
                purchaseUrl, new RequiredClearance(0.6, 0.3));
    }
}
