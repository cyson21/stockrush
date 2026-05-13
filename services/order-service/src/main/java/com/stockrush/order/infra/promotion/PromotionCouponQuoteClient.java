package com.stockrush.order.infra.promotion;

import com.stockrush.order.application.CouponNotApplicableException;
import com.stockrush.order.application.CouponQuoteClient;
import com.stockrush.order.application.CouponQuoteResult;
import com.stockrush.order.application.CouponQuoteUnavailableException;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class PromotionCouponQuoteClient implements CouponQuoteClient {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String promotionServiceUrl;
    private final Duration requestTimeout;

    @Autowired
    public PromotionCouponQuoteClient(
        ObjectMapper objectMapper,
        @Value("${stockrush.clients.promotion-service-url:http://localhost:18085}") String promotionServiceUrl,
        @Value("${stockrush.clients.promotion-quote-timeout:2s}") Duration requestTimeout
    ) {
        this(HttpClient.newHttpClient(), objectMapper, promotionServiceUrl, requestTimeout);
    }

    PromotionCouponQuoteClient(
        HttpClient httpClient,
        ObjectMapper objectMapper,
        String promotionServiceUrl,
        Duration requestTimeout
    ) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.promotionServiceUrl = normalize(promotionServiceUrl);
        this.requestTimeout = requestTimeout == null ? Duration.ofSeconds(2) : requestTimeout;
    }

    @Override
    public CouponQuoteResult quote(String couponCode, BigDecimal orderAmount, String correlationId) {
        QuoteRequest requestBody = new QuoteRequest(couponCode, orderAmount);
        HttpRequest request = requestBuilder(correlationId)
            .POST(HttpRequest.BodyPublishers.ofString(toJson(requestBody)))
            .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return parse(response);
        } catch (HttpTimeoutException exception) {
            throw new CouponQuoteUnavailableException("Coupon quote request timed out.", exception);
        } catch (IOException exception) {
            throw new CouponQuoteUnavailableException("Failed to request coupon quote.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CouponQuoteUnavailableException("Interrupted while requesting coupon quote.", exception);
        }
    }

    private HttpRequest.Builder requestBuilder(String correlationId) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(promotionServiceUrl + "/api/coupons/quote"))
            .timeout(requestTimeout)
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        if (correlationId != null && !correlationId.isBlank()) {
            builder.header("X-Correlation-Id", correlationId);
        }
        return builder;
    }

    private String toJson(QuoteRequest requestBody) {
        try {
            return objectMapper.writeValueAsString(requestBody);
        } catch (JacksonException exception) {
            throw new CouponQuoteUnavailableException("Failed to serialize coupon quote request.", exception);
        }
    }

    private CouponQuoteResult parse(HttpResponse<String> response) {
        QuoteApiResponse body;
        try {
            body = objectMapper.readValue(response.body(), QuoteApiResponse.class);
        } catch (JacksonException exception) {
            throw new CouponQuoteUnavailableException("Failed to parse coupon quote response.", exception);
        }

        if (response.statusCode() >= 400 && response.statusCode() < 500 && body != null && body.error() != null) {
            throw new CouponNotApplicableException(body.error().code());
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300 || body == null || !body.success() || body.data() == null) {
            throw new CouponQuoteUnavailableException(errorMessage(response.statusCode(), body));
        }

        return body.data();
    }

    private String errorMessage(int statusCode, QuoteApiResponse body) {
        if (body != null && body.error() != null) {
            return "Coupon quote failed with status " + statusCode + ": " + body.error().code();
        }
        return "Coupon quote failed with status " + statusCode + ".";
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "http://localhost:18085";
        }
        return value.replaceAll("/+$", "");
    }

    private record QuoteRequest(String couponCode, BigDecimal orderAmount) {
    }

    private record QuoteApiResponse(boolean success, CouponQuoteResult data, QuoteApiError error) {
    }

    private record QuoteApiError(String code, String message) {
    }
}
