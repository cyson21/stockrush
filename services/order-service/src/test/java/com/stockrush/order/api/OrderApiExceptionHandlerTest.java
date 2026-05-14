package com.stockrush.order.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.stockrush.order.application.OrderIdempotencyReplayUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

class OrderApiExceptionHandlerTest {

    @Test
    void maps_idempotency_replay_unavailable_to_retryable_conflict() {
        OrderApiExceptionHandler handler = new OrderApiExceptionHandler();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIds.HEADER_NAME, "corr-replay-pending");

        var response = handler.handleOrderIdempotencyReplayUnavailable(
            new OrderIdempotencyReplayUnavailableException("Idempotent order replay is not available yet."),
            request
        );

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("corr-replay-pending", response.getHeaders().getFirst(CorrelationIds.HEADER_NAME));
        assertEquals("1", response.getHeaders().getFirst("Retry-After"));
        assertFalse(response.getBody().success());
        assertEquals("ORDER_IDEMPOTENCY_REPLAY_PENDING", response.getBody().error().code());
        assertEquals("corr-replay-pending", response.getBody().trace().correlationId());
    }
}
