package com.stockrush.catalog.api;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:postgresql://localhost:15432/stockrush?currentSchema=catalog",
    "spring.datasource.username=stockrush",
    "spring.datasource.password=stockrush"
})
class CatalogProductControllerIntegrationTest {

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        jdbcClient.sql("delete from products").update();
        insertProduct("LIMITED-001", "Limited Hoodie", "ON_SALE", new BigDecimal("12000.00"));
        insertProduct("LIMITED-002", "Limited Cap", "ON_SALE", new BigDecimal("8000.00"));
        insertProduct("LIMITED-003", "Closed Jacket", "STOPPED", new BigDecimal("25000.00"));
    }

    @Test
    void lists_on_sale_products_in_common_response() throws Exception {
        mockMvc.perform(get("/api/products")
                .param("status", "ON_SALE")
                .header("X-Correlation-Id", "corr-catalog-list"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Correlation-Id", "corr-catalog-list"))
            .andExpect(jsonPath("$.success", is(true)))
            .andExpect(jsonPath("$.data", hasSize(2)))
            .andExpect(jsonPath("$.data[0].productCode", is("LIMITED-001")))
            .andExpect(jsonPath("$.data[0].name", is("Limited Hoodie")))
            .andExpect(jsonPath("$.data[0].status", is("ON_SALE")))
            .andExpect(jsonPath("$.data[0].listPrice", is(12000.00)))
            .andExpect(jsonPath("$.data[1].productCode", is("LIMITED-002")))
            .andExpect(jsonPath("$.trace.correlationId", is("corr-catalog-list")));
    }

    @Test
    void returns_product_detail_by_product_code() throws Exception {
        mockMvc.perform(get("/api/products/{productCode}", "LIMITED-001")
                .header("X-Correlation-Id", "corr-catalog-detail"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Correlation-Id", "corr-catalog-detail"))
            .andExpect(jsonPath("$.success", is(true)))
            .andExpect(jsonPath("$.data.productCode", is("LIMITED-001")))
            .andExpect(jsonPath("$.data.name", is("Limited Hoodie")))
            .andExpect(jsonPath("$.data.status", is("ON_SALE")))
            .andExpect(jsonPath("$.data.listPrice", is(12000.00)))
            .andExpect(jsonPath("$.trace.correlationId", is("corr-catalog-detail")));
    }

    @Test
    void returns_not_found_response_for_unknown_product() throws Exception {
        mockMvc.perform(get("/api/products/{productCode}", "UNKNOWN-001")
                .header("X-Correlation-Id", "corr-catalog-missing"))
            .andExpect(status().isNotFound())
            .andExpect(header().string("X-Correlation-Id", "corr-catalog-missing"))
            .andExpect(jsonPath("$.success", is(false)))
            .andExpect(jsonPath("$.error.code", is("CATALOG_PRODUCT_NOT_FOUND")))
            .andExpect(jsonPath("$.trace.correlationId", is("corr-catalog-missing")));
    }

    private void insertProduct(String productCode, String name, String status, BigDecimal listPrice) {
        jdbcClient.sql("""
                insert into products (product_code, name, sales_status, list_price, created_at, updated_at)
                values (:productCode, :name, :status, :listPrice, now(), now())
                """)
            .param("productCode", productCode)
            .param("name", name)
            .param("status", status)
            .param("listPrice", listPrice)
            .update();
    }
}
