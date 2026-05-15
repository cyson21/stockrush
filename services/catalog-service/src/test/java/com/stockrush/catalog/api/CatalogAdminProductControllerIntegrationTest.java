package com.stockrush.catalog.api;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
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
    "spring.datasource.url=jdbc:postgresql://localhost:15432/stockrush?currentSchema=catalog",
    "spring.datasource.username=stockrush",
    "spring.datasource.password=stockrush"
})
class CatalogAdminProductControllerIntegrationTest {

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        jdbcClient.sql("delete from admin_actions").update();
        jdbcClient.sql("delete from products").update();
        insertProduct("LIMITED-100", "Limited Product", "ON_SALE", new BigDecimal("10000.00"));
    }

    @Test
    void records_operator_id_when_header_is_present_on_create() throws Exception {
        mockMvc.perform(post("/api/admin/products")
                .header("X-Correlation-Id", "corr-admin-create-op")
                .header("X-Operator-Id", "operator-demo")
                .header("Idempotency-Key", "idem-admin-create-op")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "productCode": "LIMITED-101",
                      "name": "Limited Jacket",
                      "salesStatus": "ON_SALE",
                      "listPrice": 15000.00
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(header().string("X-Correlation-Id", "corr-admin-create-op"))
            .andExpect(jsonPath("$.success", is(true)))
            .andExpect(jsonPath("$.data.productCode", is("LIMITED-101")))
            .andExpect(jsonPath("$.trace.correlationId", is("corr-admin-create-op")));

        assertAdminAction("PRODUCT_CREATED", "LIMITED-101", "operator-demo", "corr-admin-create-op");
    }

    @Test
    void records_unknown_operator_when_header_is_missing_on_update() throws Exception {
        mockMvc.perform(put("/api/admin/products/{productCode}", "LIMITED-100")
                .header("X-Correlation-Id", "corr-admin-update-op")
                .header("Idempotency-Key", "idem-admin-update-op")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "name": "Limited Product Renamed",
                      "salesStatus": "STOPPED",
                      "listPrice": 11000.00
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Correlation-Id", "corr-admin-update-op"))
            .andExpect(jsonPath("$.success", is(true)))
            .andExpect(jsonPath("$.data.productCode", is("LIMITED-100")))
            .andExpect(jsonPath("$.trace.correlationId", is("corr-admin-update-op")));

        assertAdminAction("PRODUCT_UPDATED", "LIMITED-100", "unknown", "corr-admin-update-op");
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

    private void assertAdminAction(
        String expectedAction,
        String expectedTargetId,
        String expectedOperatorId,
        String expectedCorrelationId
    ) {
        org.junit.jupiter.api.Assertions.assertEquals(
            expectedAction,
            queryAdminActionField("action", expectedTargetId, expectedAction)
        );
        org.junit.jupiter.api.Assertions.assertEquals(
            expectedTargetId,
            queryAdminActionField("target_id", expectedTargetId, expectedAction)
        );
        org.junit.jupiter.api.Assertions.assertEquals(
            expectedOperatorId,
            queryAdminActionField("operator_id", expectedTargetId, expectedAction)
        );
        org.junit.jupiter.api.Assertions.assertEquals(
            expectedCorrelationId,
            queryAdminActionField("correlation_id", expectedTargetId, expectedAction)
        );
    }

    private String queryAdminActionField(String fieldName, String targetId, String expectedAction) {
        return jdbcClient.sql("select " + fieldName + " from admin_actions"
                + " where target_id = :targetId and action = :action order by created_at desc limit 1")
            .param("targetId", targetId)
            .param("action", expectedAction)
            .query(String.class)
            .single();
    }
}
