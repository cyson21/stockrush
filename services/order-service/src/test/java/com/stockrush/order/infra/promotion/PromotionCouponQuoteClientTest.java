package com.stockrush.order.infra.promotion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.stockrush.order.application.CouponNotApplicableException;
import com.stockrush.order.application.CouponQuoteUnavailableException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PromotionCouponQuoteClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void maps_promotion_coupon_error_to_business_exception() throws IOException {
        startServer(404, """
            {
              "success": false,
              "data": null,
              "error": {
                "code": "PROMOTION_COUPON_NOT_FOUND",
                "message": "Coupon not found."
              },
              "trace": { "correlationId": "corr-quote-001" }
            }
            """);
        PromotionCouponQuoteClient client = newClient(Duration.ofSeconds(2));

        CouponNotApplicableException error = assertThrows(
            CouponNotApplicableException.class,
            () -> client.quote("BAD10", new BigDecimal("10000.00"), "corr-quote-001")
        );

        assertEquals("PROMOTION_COUPON_NOT_FOUND", error.reason());
        assertEquals("Coupon could not be applied: PROMOTION_COUPON_NOT_FOUND", error.getMessage());
    }

    @Test
    void fails_fast_when_promotion_quote_timeout_expires() throws IOException {
        startSlowServer();
        PromotionCouponQuoteClient client = newClient(Duration.ofMillis(50));

        CouponQuoteUnavailableException error = assertThrows(
            CouponQuoteUnavailableException.class,
            () -> client.quote("SLOW10", new BigDecimal("10000.00"), "corr-quote-timeout")
        );

        assertEquals("Coupon quote request timed out.", error.getMessage());
    }

    private PromotionCouponQuoteClient newClient(Duration timeout) {
        return new PromotionCouponQuoteClient(
            HttpClient.newHttpClient(),
            JsonMapper.builder().findAndAddModules().build(),
            "http://localhost:" + server.getAddress().getPort(),
            timeout
        );
    }

    private void startServer(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/coupons/quote", exchange -> {
            byte[] bytes = body.getBytes();
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    private void startSlowServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/coupons/quote", exchange -> {
            try {
                Thread.sleep(300);
                byte[] bytes = "{}".getBytes();
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
    }
}
