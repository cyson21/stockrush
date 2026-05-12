package com.stockrush.order.api;

import com.stockrush.order.application.CreateOrderCommand;
import com.stockrush.order.application.CreateOrderItemCommand;
import com.stockrush.order.application.CreateOrderResult;
import com.stockrush.order.application.PersistentCreateOrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
class CreateOrderController {

    private final PersistentCreateOrderService createOrderService;

    CreateOrderController(PersistentCreateOrderService createOrderService) {
        this.createOrderService = createOrderService;
    }

    @PostMapping
    ResponseEntity<ApiResponse<CreateOrderResponse>> create(
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @RequestHeader(value = CorrelationIds.HEADER_NAME, required = false) String correlationId,
        @Valid @RequestBody CreateOrderRequest request
    ) {
        String resolvedCorrelationId = CorrelationIds.resolve(correlationId);
        CreateOrderResult result = createOrderService.create(request.toCommand(idempotencyKey, resolvedCorrelationId));
        CreateOrderResponse response = CreateOrderResponse.from(result);

        return ResponseEntity.created(URI.create("/api/orders/" + response.orderId()))
            .header(CorrelationIds.HEADER_NAME, resolvedCorrelationId)
            .body(ApiResponse.success(response, resolvedCorrelationId));
    }
}

record CreateOrderRequest(
    @NotBlank String memberId,
    @NotEmpty List<@Valid CreateOrderItemRequest> items
) {

    CreateOrderCommand toCommand(String idempotencyKey, String correlationId) {
        return new CreateOrderCommand(
            memberId,
            idempotencyKey,
            correlationId,
            items.stream().map(CreateOrderItemRequest::toCommand).toList()
        );
    }
}

record CreateOrderItemRequest(
    @NotBlank String productCode,
    @NotBlank String skuId,
    @Min(1) int quantity,
    @NotNull @DecimalMin(value = "0.01") BigDecimal unitPrice
) {

    CreateOrderItemCommand toCommand() {
        return new CreateOrderItemCommand(productCode, skuId, quantity, unitPrice);
    }
}

record CreateOrderResponse(
    String orderId,
    String status,
    String sagaStatus,
    BigDecimal totalAmount
) {

    static CreateOrderResponse from(CreateOrderResult result) {
        return new CreateOrderResponse(
            result.order().orderId(),
            result.order().status().name(),
            result.order().sagaStatus().name(),
            result.order().totalAmount()
        );
    }
}
