package com.stockrush.catalog.api;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.http.MediaType;
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
    void filters_on_sale_products_with_trimmed_query_case_insensitive() throws Exception {
        mockMvc.perform(get("/api/products")
                .param("status", "ON_SALE")
                .param("q", "  HOODIE  ")
                .header("X-Correlation-Id", "corr-catalog-search-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success", is(true)))
            .andExpect(jsonPath("$.data", hasSize(1)))
            .andExpect(jsonPath("$.data[0].productCode", is("LIMITED-001")))
            .andExpect(jsonPath("$.data[0].name", is("Limited Hoodie")))
            .andExpect(jsonPath("$.trace.correlationId", is("corr-catalog-search-1")));
    }

    @Test
    void filters_on_sale_products_with_case_insensitive_code_query() throws Exception {
        mockMvc.perform(get("/api/products")
                .param("status", "ON_SALE")
                .param("q", "LIMITED-002")
                .header("X-Correlation-Id", "corr-catalog-search-2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success", is(true)))
            .andExpect(jsonPath("$.data", hasSize(1)))
            .andExpect(jsonPath("$.data[0].productCode", is("LIMITED-002")))
            .andExpect(jsonPath("$.data[0].name", is("Limited Cap")))
            .andExpect(jsonPath("$.trace.correlationId", is("corr-catalog-search-2")));
    }

    @Test
    void applies_default_order_when_query_is_blank() throws Exception {
        mockMvc.perform(get("/api/products")
                .param("status", "ON_SALE")
                .param("q", "   ")
                .header("X-Correlation-Id", "corr-catalog-search-default"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success", is(true)))
            .andExpect(jsonPath("$.data", hasSize(2)))
            .andExpect(jsonPath("$.data[0].productCode", is("LIMITED-001")))
            .andExpect(jsonPath("$.data[1].productCode", is("LIMITED-002")));
    }

    @Test
    void treats_like_wildcard_characters_as_literal_search_text() throws Exception {
        mockMvc.perform(get("/api/products")
                .param("status", "ON_SALE")
                .param("q", "%")
                .header("X-Correlation-Id", "corr-catalog-search-wildcard"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success", is(true)))
            .andExpect(jsonPath("$.data", hasSize(0)))
            .andExpect(jsonPath("$.trace.correlationId", is("corr-catalog-search-wildcard")));
    }

    @Test
    void returns_common_error_response_when_status_query_is_missing() throws Exception {
        mockMvc.perform(get("/api/products")
                .header("X-Correlation-Id", "corr-catalog-missing-status"))
            .andExpect(status().isBadRequest())
            .andExpect(header().string("X-Correlation-Id", "corr-catalog-missing-status"))
            .andExpect(jsonPath("$.success", is(false)))
            .andExpect(jsonPath("$.error.code", is("CATALOG_INVALID_REQUEST")))
            .andExpect(jsonPath("$.trace.correlationId", is("corr-catalog-missing-status")));
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

    @Test
    void creates_product_in_admin_api_with_common_response() throws Exception {
        mockMvc.perform(post("/api/admin/products")
                .header("X-Correlation-Id", "corr-admin-create")
                .header("Idempotency-Key", "idem-admin-create")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "productCode": "LIMITED-010",
                      "name": "Limited Bag",
                      "salesStatus": "ON_SALE",
                      "listPrice": 35000.00
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(header().string("X-Correlation-Id", "corr-admin-create"))
            .andExpect(jsonPath("$.success", is(true)))
            .andExpect(jsonPath("$.data.productCode", is("LIMITED-010")))
            .andExpect(jsonPath("$.data.name", is("Limited Bag")))
            .andExpect(jsonPath("$.data.status", is("ON_SALE")))
            .andExpect(jsonPath("$.data.listPrice", is(35000.00)))
            .andExpect(jsonPath("$.trace.correlationId", is("corr-admin-create")))
            .andExpect(header().string("Location", "/api/admin/products/LIMITED-010"));
    }

    @Test
    void updates_product_in_admin_api_with_common_response() throws Exception {
        mockMvc.perform(put("/api/admin/products/{productCode}", "LIMITED-001")
                .header("X-Correlation-Id", "corr-admin-update")
                .header("Idempotency-Key", "idem-admin-update")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "name": "Limited Hoodie Updated",
                      "salesStatus": "STOPPED",
                      "listPrice": 13000.00
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Correlation-Id", "corr-admin-update"))
            .andExpect(jsonPath("$.success", is(true)))
            .andExpect(jsonPath("$.data.productCode", is("LIMITED-001")))
            .andExpect(jsonPath("$.data.name", is("Limited Hoodie Updated")))
            .andExpect(jsonPath("$.data.status", is("STOPPED")))
            .andExpect(jsonPath("$.data.listPrice", is(13000.00)))
            .andExpect(jsonPath("$.trace.correlationId", is("corr-admin-update")));

        var updated = jdbcClient.sql("select name, sales_status, list_price from products where product_code = 'LIMITED-001'")
            .query((rs, row) -> {
                return rs.getString("name") + ":" + rs.getString("sales_status") + ":" + rs.getBigDecimal("list_price");
            }).single();
        org.junit.jupiter.api.Assertions.assertEquals("Limited Hoodie Updated:STOPPED:13000.00", updated);
    }

    @Test
    void returns_conflict_when_creating_duplicate_product_code() throws Exception {
        mockMvc.perform(post("/api/admin/products")
                .header("X-Correlation-Id", "corr-admin-duplicate")
                .header("Idempotency-Key", "idem-admin-duplicate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "productCode": "LIMITED-001",
                      "name": "Another Product",
                      "salesStatus": "ON_SALE",
                      "listPrice": 5000.00
                    }
                    """))
            .andExpect(status().isConflict())
            .andExpect(header().string("X-Correlation-Id", "corr-admin-duplicate"))
            .andExpect(jsonPath("$.success", is(false)))
            .andExpect(jsonPath("$.error.code", is("CATALOG_DUPLICATE_PRODUCT_CODE")))
            .andExpect(jsonPath("$.trace.correlationId", is("corr-admin-duplicate")));
    }

    @Test
    void rejects_invalid_request_for_admin_product_create() throws Exception {
        mockMvc.perform(post("/api/admin/products")
                .header("X-Correlation-Id", "corr-admin-invalid-create")
                .header("Idempotency-Key", "idem-admin-invalid-create")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "productCode": "",
                      "name": "Limited Bag",
                      "salesStatus": "ON_SALE",
                      "listPrice": 35000.00
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(header().string("X-Correlation-Id", "corr-admin-invalid-create"))
            .andExpect(jsonPath("$.success", is(false)))
            .andExpect(jsonPath("$.error.code", is("CATALOG_INVALID_REQUEST")))
            .andExpect(jsonPath("$.trace.correlationId", is("corr-admin-invalid-create")));
    }

    @Test
    void rejects_admin_product_create_without_idempotency_key() throws Exception {
        mockMvc.perform(post("/api/admin/products")
                .header("X-Correlation-Id", "corr-admin-missing-idem")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "productCode": "LIMITED-011",
                      "name": "Limited Wallet",
                      "salesStatus": "ON_SALE",
                      "listPrice": 22000.00
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(header().string("X-Correlation-Id", "corr-admin-missing-idem"))
            .andExpect(jsonPath("$.success", is(false)))
            .andExpect(jsonPath("$.error.code", is("COMMON_MISSING_IDEMPOTENCY_KEY")))
            .andExpect(jsonPath("$.trace.correlationId", is("corr-admin-missing-idem")));
    }

    @Test
    void rejects_invalid_request_for_admin_product_update_when_product_missing() throws Exception {
        mockMvc.perform(put("/api/admin/products/{productCode}", "UNKNOWN-001")
                .header("X-Correlation-Id", "corr-admin-missing")
                .header("Idempotency-Key", "idem-admin-missing")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "name": "Unknown Product",
                      "salesStatus": "ON_SALE",
                      "listPrice": 10000.00
                    }
                    """))
            .andExpect(status().isNotFound())
            .andExpect(header().string("X-Correlation-Id", "corr-admin-missing"))
            .andExpect(jsonPath("$.success", is(false)))
            .andExpect(jsonPath("$.error.code", is("CATALOG_PRODUCT_NOT_FOUND")))
            .andExpect(jsonPath("$.trace.correlationId", is("corr-admin-missing")));
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
