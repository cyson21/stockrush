package com.stockrush.inventory.api;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:postgresql://localhost:15432/stockrush?currentSchema=inventory",
    "spring.datasource.username=stockrush",
    "spring.datasource.password=stockrush",
    "stockrush.kafka.listeners.enabled=false",
    "spring.kafka.listener.auto-startup=false"
})
class InventoryStockControllerIntegrationTest {

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        jdbcClient.sql("delete from stock_reservations").update();
        jdbcClient.sql("delete from stock_items").update();
        insertStock("SKU-001", "LIMITED-001", 10, 2);
        insertStock("SKU-002", "LIMITED-001", 5, 0);
        insertStock("SKU-003", "LIMITED-002", 7, 1);
    }

    @Test
    void lists_stock_items_by_product_code_in_common_response() throws Exception {
        mockMvc.perform(get("/api/stocks")
                .param("productCode", "LIMITED-001")
                .header("X-Correlation-Id", "corr-stock-list"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Correlation-Id", "corr-stock-list"))
            .andExpect(jsonPath("$.success", is(true)))
            .andExpect(jsonPath("$.data", hasSize(2)))
            .andExpect(jsonPath("$.data[0].skuId", is("SKU-001")))
            .andExpect(jsonPath("$.data[0].productCode", is("LIMITED-001")))
            .andExpect(jsonPath("$.data[0].availableQuantity", is(10)))
            .andExpect(jsonPath("$.data[0].reservedQuantity", is(2)))
            .andExpect(jsonPath("$.data[1].skuId", is("SKU-002")))
            .andExpect(jsonPath("$.trace.correlationId", is("corr-stock-list")));
    }

    @Test
    void returns_stock_detail_by_sku_id() throws Exception {
        mockMvc.perform(get("/api/stocks/{skuId}", "SKU-003")
                .header("X-Correlation-Id", "corr-stock-detail"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Correlation-Id", "corr-stock-detail"))
            .andExpect(jsonPath("$.success", is(true)))
            .andExpect(jsonPath("$.data.skuId", is("SKU-003")))
            .andExpect(jsonPath("$.data.productCode", is("LIMITED-002")))
            .andExpect(jsonPath("$.data.availableQuantity", is(7)))
            .andExpect(jsonPath("$.data.reservedQuantity", is(1)))
            .andExpect(jsonPath("$.trace.correlationId", is("corr-stock-detail")));
    }

    @Test
    void sets_available_quantity_for_stock_item() throws Exception {
        mockMvc.perform(put("/api/stocks/{skuId}", "SKU-001")
                .header("X-Correlation-Id", "corr-stock-set")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "productCode": "LIMITED-001",
                      "availableQuantity": 12
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Correlation-Id", "corr-stock-set"))
            .andExpect(jsonPath("$.success", is(true)))
            .andExpect(jsonPath("$.data.skuId", is("SKU-001")))
            .andExpect(jsonPath("$.data.availableQuantity", is(12)))
            .andExpect(jsonPath("$.data.reservedQuantity", is(2)))
            .andExpect(jsonPath("$.trace.correlationId", is("corr-stock-set")));
    }

    @Test
    void creates_stock_item_when_sku_does_not_exist() throws Exception {
        mockMvc.perform(put("/api/stocks/{skuId}", "SKU-004")
                .header("X-Correlation-Id", "corr-stock-create")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "productCode": "LIMITED-004",
                      "availableQuantity": 20
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Correlation-Id", "corr-stock-create"))
            .andExpect(jsonPath("$.success", is(true)))
            .andExpect(jsonPath("$.data.skuId", is("SKU-004")))
            .andExpect(jsonPath("$.data.productCode", is("LIMITED-004")))
            .andExpect(jsonPath("$.data.availableQuantity", is(20)))
            .andExpect(jsonPath("$.data.reservedQuantity", is(0)))
            .andExpect(jsonPath("$.trace.correlationId", is("corr-stock-create")));
    }

    @Test
    void rejects_negative_available_quantity() throws Exception {
        mockMvc.perform(put("/api/stocks/{skuId}", "SKU-001")
                .header("X-Correlation-Id", "corr-stock-invalid")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "productCode": "LIMITED-001",
                      "availableQuantity": -1
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(header().string("X-Correlation-Id", "corr-stock-invalid"))
            .andExpect(jsonPath("$.success", is(false)))
            .andExpect(jsonPath("$.error.code", is("INVENTORY_INVALID_REQUEST")))
            .andExpect(jsonPath("$.trace.correlationId", is("corr-stock-invalid")));
    }

    @Test
    void returns_not_found_response_for_unknown_sku() throws Exception {
        mockMvc.perform(get("/api/stocks/{skuId}", "UNKNOWN-SKU")
                .header("X-Correlation-Id", "corr-stock-missing"))
            .andExpect(status().isNotFound())
            .andExpect(header().string("X-Correlation-Id", "corr-stock-missing"))
            .andExpect(jsonPath("$.success", is(false)))
            .andExpect(jsonPath("$.error.code", is("INVENTORY_STOCK_NOT_FOUND")))
            .andExpect(jsonPath("$.trace.correlationId", is("corr-stock-missing")));
    }

    private void insertStock(String skuId, String productCode, int availableQuantity, int reservedQuantity) {
        jdbcClient.sql("""
                insert into stock_items (
                  sku_id, product_code, available_quantity, reserved_quantity, created_at, updated_at
                )
                values (:skuId, :productCode, :availableQuantity, :reservedQuantity, now(), now())
                """)
            .param("skuId", skuId)
            .param("productCode", productCode)
            .param("availableQuantity", availableQuantity)
            .param("reservedQuantity", reservedQuantity)
            .update();
    }
}
