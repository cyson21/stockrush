-- 도메인 초기화/보조 스키마 마이그레이션입니다.

package com.stockrush.promotion.api;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
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
    "spring.datasource.url=jdbc:postgresql://localhost:15432/stockrush?currentSchema=promotion",
    "spring.datasource.username=stockrush",
    "spring.datasource.password=stockrush"
})
class PromotionCouponControllerIntegrationTest {

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        jdbcClient.sql("delete from processed_events").update();
        jdbcClient.sql("delete from coupon_usages").update();
        jdbcClient.sql("delete from admin_coupon_command_idempotency").update();
        jdbcClient.sql("delete from coupons").update();
        insertCoupon("WELCOME10", "Welcome 10%", "PERCENTAGE", new BigDecimal("10.00"), new BigDecimal("20000.00"), new BigDecimal("5000.00"), "ACTIVE");
        insertCoupon("FIXED3000", "Fixed 3000", "FIXED_AMOUNT", new BigDecimal("3000.00"), new BigDecimal("10000.00"), null, "ACTIVE");
        insertCoupon("PAUSED10", "Paused 10%", "PERCENTAGE", new BigDecimal("10.00"), new BigDecimal("10000.00"), null, "PAUSED");
        insertCoupon(
            "FUTURE10",
            "Future 10%",
            "PERCENTAGE",
            new BigDecimal("10.00"),
            new BigDecimal("10000.00"),
            null,
            "ACTIVE",
            OffsetDateTime.parse("2099-01-01T00:00:00Z"),
            OffsetDateTime.parse("2099-12-31T23:59:59Z")
        );
    }

    @Test
    void creates_coupon_in_admin_api_with_common_response() throws Exception {
        mockMvc.perform(post("/api/admin/coupons")
                .header("X-Correlation-Id", "corr-promotion-create")
                .header("Idempotency-Key", "idem-promotion-create")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "couponCode": "SPRING15",
                      "name": "Spring 15%",
                      "discountType": "PERCENTAGE",
                      "discountValue": 15.00,
                      "minOrderAmount": 30000.00,
                      "maxDiscountAmount": 7000.00,
                      "status": "ACTIVE",
                      "startsAt": "2026-05-01T00:00:00Z",
                      "endsAt": "2026-12-31T23:59:59Z"
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(header().string("X-Correlation-Id", "corr-promotion-create"))
            .andExpect(header().string("Location", "/api/admin/coupons/SPRING15"))
            .andExpect(jsonPath("$.success", is(true)))
            .andExpect(jsonPath("$.data.couponCode", is("SPRING15")))
            .andExpect(jsonPath("$.data.discountType", is("PERCENTAGE")))
            .andExpect(jsonPath("$.data.discountValue", is(15.00)))
            .andExpect(jsonPath("$.data.minOrderAmount", is(30000.00)))
            .andExpect(jsonPath("$.data.maxDiscountAmount", is(7000.00)))
            .andExpect(jsonPath("$.data.status", is("ACTIVE")))
            .andExpect(jsonPath("$.data.startsAt", is("2026-05-01T00:00:00Z")))
            .andExpect(jsonPath("$.data.endsAt", is("2026-12-31T23:59:59Z")))
            .andExpect(jsonPath("$.trace.correlationId", is("corr-promotion-create")));
    }

    @Test
    void returns_created_coupon_detail_from_location_path() throws Exception {
        mockMvc.perform(get("/api/admin/coupons/{couponCode}", "WELCOME10")
                .header("X-Correlation-Id", "corr-promotion-detail"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Correlation-Id", "corr-promotion-detail"))
            .andExpect(jsonPath("$.success", is(true)))
            .andExpect(jsonPath("$.data.couponCode", is("WELCOME10")))
            .andExpect(jsonPath("$.data.name", is("Welcome 10%")))
            .andExpect(jsonPath("$.trace.correlationId", is("corr-promotion-detail")));
    }

    @Test
    void lists_coupons_by_status_for_admin() throws Exception {
        mockMvc.perform(get("/api/admin/coupons")
                .param("status", "ACTIVE")
                .header("X-Correlation-Id", "corr-promotion-list"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Correlation-Id", "corr-promotion-list"))
            .andExpect(jsonPath("$.success", is(true)))
            .andExpect(jsonPath("$.data", hasSize(3)))
            .andExpect(jsonPath("$.data[0].couponCode", is("WELCOME10")))
            .andExpect(jsonPath("$.data[1].couponCode", is("FIXED3000")))
            .andExpect(jsonPath("$.data[2].couponCode", is("FUTURE10")))
            .andExpect(jsonPath("$.trace.correlationId", is("corr-promotion-list")));
    }

    @Test
    void quotes_percentage_coupon_discount_with_cap() throws Exception {
        mockMvc.perform(post("/api/coupons/quote")
                .header("X-Correlation-Id", "corr-promotion-quote")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "couponCode": "WELCOME10",
                      "orderAmount": 80000.00
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Correlation-Id", "corr-promotion-quote"))
            .andExpect(jsonPath("$.success", is(true)))
            .andExpect(jsonPath("$.data.couponCode", is("WELCOME10")))
            .andExpect(jsonPath("$.data.applied", is(true)))
            .andExpect(jsonPath("$.data.discountAmount", is(5000.00)))
            .andExpect(jsonPath("$.data.payAmount", is(75000.00)))
            .andExpect(jsonPath("$.data.reason", is("APPLIED")))
            .andExpect(jsonPath("$.trace.correlationId", is("corr-promotion-quote")));
    }

    @Test
    void returns_not_applied_quote_when_order_amount_is_below_minimum() throws Exception {
        mockMvc.perform(post("/api/coupons/quote")
                .header("X-Correlation-Id", "corr-promotion-minimum")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "couponCode": "WELCOME10",
                      "orderAmount": 12000.00
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Correlation-Id", "corr-promotion-minimum"))
            .andExpect(jsonPath("$.success", is(true)))
            .andExpect(jsonPath("$.data.applied", is(false)))
            .andExpect(jsonPath("$.data.discountAmount", is(0.00)))
            .andExpect(jsonPath("$.data.payAmount", is(12000.00)))
            .andExpect(jsonPath("$.data.reason", is("MIN_ORDER_AMOUNT_NOT_MET")));
    }

    @Test
    void quotes_fixed_amount_coupon_discount() throws Exception {
        mockMvc.perform(post("/api/coupons/quote")
                .header("X-Correlation-Id", "corr-promotion-fixed")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "couponCode": "FIXED3000",
                      "orderAmount": 15000.00
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Correlation-Id", "corr-promotion-fixed"))
            .andExpect(jsonPath("$.success", is(true)))
            .andExpect(jsonPath("$.data.applied", is(true)))
            .andExpect(jsonPath("$.data.discountAmount", is(3000.00)))
            .andExpect(jsonPath("$.data.payAmount", is(12000.00)))
            .andExpect(jsonPath("$.data.reason", is("APPLIED")));
    }

    @Test
    void returns_not_applied_quote_when_coupon_is_paused() throws Exception {
        mockMvc.perform(post("/api/coupons/quote")
                .header("X-Correlation-Id", "corr-promotion-paused")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "couponCode": "PAUSED10",
                      "orderAmount": 15000.00
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Correlation-Id", "corr-promotion-paused"))
            .andExpect(jsonPath("$.success", is(true)))
            .andExpect(jsonPath("$.data.applied", is(false)))
            .andExpect(jsonPath("$.data.reason", is("COUPON_NOT_ACTIVE")));
    }

    @Test
    void returns_not_applied_quote_when_coupon_period_has_not_started() throws Exception {
        mockMvc.perform(post("/api/coupons/quote")
                .header("X-Correlation-Id", "corr-promotion-period")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "couponCode": "FUTURE10",
                      "orderAmount": 15000.00
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Correlation-Id", "corr-promotion-period"))
            .andExpect(jsonPath("$.success", is(true)))
            .andExpect(jsonPath("$.data.applied", is(false)))
            .andExpect(jsonPath("$.data.reason", is("COUPON_OUT_OF_PERIOD")));
    }

    @Test
    void returns_conflict_when_creating_duplicate_coupon_code() throws Exception {
        mockMvc.perform(post("/api/admin/coupons")
                .header("X-Correlation-Id", "corr-promotion-duplicate")
                .header("Idempotency-Key", "idem-promotion-duplicate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "couponCode": "WELCOME10",
                      "name": "Duplicate",
                      "discountType": "PERCENTAGE",
                      "discountValue": 10.00,
                      "minOrderAmount": 20000.00,
                      "maxDiscountAmount": 5000.00,
                      "status": "ACTIVE",
                      "startsAt": "2026-05-01T00:00:00Z",
                      "endsAt": "2026-12-31T23:59:59Z"
                    }
                    """))
            .andExpect(status().isConflict())
            .andExpect(header().string("X-Correlation-Id", "corr-promotion-duplicate"))
            .andExpect(jsonPath("$.success", is(false)))
            .andExpect(jsonPath("$.error.code", is("PROMOTION_DUPLICATE_COUPON_CODE")));
    }

    @Test
    void replays_same_admin_create_when_idempotency_key_and_body_match() throws Exception {
        String body = """
            {
              "couponCode": "REPLAY15",
              "name": "Replay 15%",
              "discountType": "PERCENTAGE",
              "discountValue": 15.00,
              "minOrderAmount": 30000.00,
              "maxDiscountAmount": 7000.00,
              "status": "ACTIVE",
              "startsAt": "2026-05-01T00:00:00Z",
              "endsAt": "2026-12-31T23:59:59Z"
            }
            """;

        mockMvc.perform(post("/api/admin/coupons")
                .header("X-Correlation-Id", "corr-promotion-replay-first")
                .header("Idempotency-Key", "idem-promotion-replay")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated());

        mockMvc.perform(post("/api/admin/coupons")
                .header("X-Correlation-Id", "corr-promotion-replay-second")
                .header("Idempotency-Key", "idem-promotion-replay")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Correlation-Id", "corr-promotion-replay-second"))
            .andExpect(jsonPath("$.success", is(true)))
            .andExpect(jsonPath("$.data.couponCode", is("REPLAY15")))
            .andExpect(jsonPath("$.trace.correlationId", is("corr-promotion-replay-second")));

        org.junit.jupiter.api.Assertions.assertEquals(1, queryInt("select count(*) from coupons where coupon_code = 'REPLAY15'"));
    }

    @Test
    void rejects_same_idempotency_key_with_different_body() throws Exception {
        mockMvc.perform(post("/api/admin/coupons")
                .header("X-Correlation-Id", "corr-promotion-idem-first")
                .header("Idempotency-Key", "idem-promotion-conflict")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "couponCode": "IDEM15",
                      "name": "Idem 15%",
                      "discountType": "PERCENTAGE",
                      "discountValue": 15.00,
                      "minOrderAmount": 30000.00,
                      "maxDiscountAmount": 7000.00,
                      "status": "ACTIVE",
                      "startsAt": "2026-05-01T00:00:00Z",
                      "endsAt": "2026-12-31T23:59:59Z"
                    }
                    """))
            .andExpect(status().isCreated());

        mockMvc.perform(post("/api/admin/coupons")
                .header("X-Correlation-Id", "corr-promotion-idem-conflict")
                .header("Idempotency-Key", "idem-promotion-conflict")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "couponCode": "IDEM20",
                      "name": "Idem 20%",
                      "discountType": "PERCENTAGE",
                      "discountValue": 20.00,
                      "minOrderAmount": 30000.00,
                      "maxDiscountAmount": 7000.00,
                      "status": "ACTIVE",
                      "startsAt": "2026-05-01T00:00:00Z",
                      "endsAt": "2026-12-31T23:59:59Z"
                    }
                    """))
            .andExpect(status().isConflict())
            .andExpect(header().string("X-Correlation-Id", "corr-promotion-idem-conflict"))
            .andExpect(jsonPath("$.success", is(false)))
            .andExpect(jsonPath("$.error.code", is("PROMOTION_IDEMPOTENCY_KEY_CONFLICT")));
    }

    @Test
    void rejects_admin_coupon_create_without_idempotency_key() throws Exception {
        mockMvc.perform(post("/api/admin/coupons")
                .header("X-Correlation-Id", "corr-promotion-missing-idem")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "couponCode": "MISSING-IDEM",
                      "name": "Missing Idem",
                      "discountType": "FIXED_AMOUNT",
                      "discountValue": 1000.00,
                      "minOrderAmount": 5000.00,
                      "status": "ACTIVE",
                      "startsAt": "2026-05-01T00:00:00Z",
                      "endsAt": "2026-12-31T23:59:59Z"
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(header().string("X-Correlation-Id", "corr-promotion-missing-idem"))
            .andExpect(jsonPath("$.success", is(false)))
            .andExpect(jsonPath("$.error.code", is("COMMON_MISSING_IDEMPOTENCY_KEY")));
    }

    private void insertCoupon(
        String couponCode,
        String name,
        String discountType,
        BigDecimal discountValue,
        BigDecimal minOrderAmount,
        BigDecimal maxDiscountAmount,
        String status
    ) {
        insertCoupon(
            couponCode,
            name,
            discountType,
            discountValue,
            minOrderAmount,
            maxDiscountAmount,
            status,
            OffsetDateTime.parse("2026-01-01T00:00:00Z"),
            OffsetDateTime.parse("2099-12-31T23:59:59Z")
        );
    }

    private void insertCoupon(
        String couponCode,
        String name,
        String discountType,
        BigDecimal discountValue,
        BigDecimal minOrderAmount,
        BigDecimal maxDiscountAmount,
        String status,
        OffsetDateTime startsAt,
        OffsetDateTime endsAt
    ) {
        jdbcClient.sql("""
                insert into coupons (
                  coupon_code, name, discount_type, discount_value, min_order_amount,
                  max_discount_amount, status, starts_at, ends_at, created_at, updated_at
                )
                values (
                  :couponCode, :name, :discountType, :discountValue, :minOrderAmount,
                  :maxDiscountAmount, :status, :startsAt, :endsAt, now(), now()
                )
                """)
            .param("couponCode", couponCode)
            .param("name", name)
            .param("discountType", discountType)
            .param("discountValue", discountValue)
            .param("minOrderAmount", minOrderAmount)
            .param("maxDiscountAmount", maxDiscountAmount)
            .param("status", status)
            .param("startsAt", startsAt)
            .param("endsAt", endsAt)
            .update();
    }

    private int queryInt(String sql) {
        return jdbcClient.sql(sql).query(Integer.class).single();
    }
}
