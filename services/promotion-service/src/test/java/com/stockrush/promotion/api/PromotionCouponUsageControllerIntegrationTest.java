
package com.stockrush.promotion.api;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
class PromotionCouponUsageControllerIntegrationTest {

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
        insertCoupon("WELCOME10");
        insertCoupon("FIXED3000");
        insertUsage(
            "ord_coupon_usage_001",
            "member-a",
            "WELCOME10",
            "CONSUMED",
            new BigDecimal("80000.00"),
            new BigDecimal("5000.00"),
            new BigDecimal("75000.00"),
            "2026-05-13T04:30:00Z",
            "2026-05-13T04:31:00Z",
            null,
            null
        );
        insertUsage(
            "ord_coupon_usage_002",
            "member-a",
            "WELCOME10",
            "RELEASED",
            new BigDecimal("40000.00"),
            new BigDecimal("4000.00"),
            new BigDecimal("36000.00"),
            "2026-05-13T04:32:00Z",
            null,
            "2026-05-13T04:33:00Z",
            "PAYMENT_DECLINED"
        );
        insertUsage(
            "ord_coupon_usage_003",
            "member-b",
            "FIXED3000",
            "RESERVED",
            new BigDecimal("30000.00"),
            new BigDecimal("3000.00"),
            new BigDecimal("27000.00"),
            "2026-05-13T04:34:00Z",
            null,
            null,
            null
        );
    }

    @Test
    void lists_coupon_usages_for_admin_with_filters() throws Exception {
        mockMvc.perform(get("/api/admin/coupon-usages")
                .param("couponCode", "WELCOME10")
                .param("memberId", "member-a")
                .param("status", "RELEASED")
                .param("page", "0")
                .param("size", "20")
                .header("X-Correlation-Id", "corr-promotion-usage-list"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Correlation-Id", "corr-promotion-usage-list"))
            .andExpect(jsonPath("$.success", is(true)))
            .andExpect(jsonPath("$.data.page", is(0)))
            .andExpect(jsonPath("$.data.size", is(20)))
            .andExpect(jsonPath("$.data.items", hasSize(1)))
            .andExpect(jsonPath("$.data.items[0].orderId", is("ord_coupon_usage_002")))
            .andExpect(jsonPath("$.data.items[0].memberId", is("member-a")))
            .andExpect(jsonPath("$.data.items[0].couponCode", is("WELCOME10")))
            .andExpect(jsonPath("$.data.items[0].status", is("RELEASED")))
            .andExpect(jsonPath("$.data.items[0].orderAmount", is(40000.00)))
            .andExpect(jsonPath("$.data.items[0].discountAmount", is(4000.00)))
            .andExpect(jsonPath("$.data.items[0].payableAmount", is(36000.00)))
            .andExpect(jsonPath("$.data.items[0].reservedAt", is("2026-05-13T04:32:00Z")))
            .andExpect(jsonPath("$.data.items[0].releasedAt", is("2026-05-13T04:33:00Z")))
            .andExpect(jsonPath("$.data.items[0].releaseReason", is("PAYMENT_DECLINED")))
            .andExpect(jsonPath("$.trace.correlationId", is("corr-promotion-usage-list")));
    }

    @Test
    void normalizes_coupon_usage_page_parameters() throws Exception {
        mockMvc.perform(get("/api/admin/coupon-usages")
                .param("page", "-1")
                .param("size", "999"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.page", is(0)))
            .andExpect(jsonPath("$.data.size", is(100)))
            .andExpect(jsonPath("$.data.items", hasSize(3)))
            .andExpect(jsonPath("$.data.items[0].orderId", is("ord_coupon_usage_003")));
    }

    @Test
    void accepts_trailing_slash_for_coupon_usage_history() throws Exception {
        mockMvc.perform(get("/api/admin/coupon-usages/")
                .param("status", "CONSUMED")
                .header("X-Correlation-Id", "corr-promotion-usage-slash"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Correlation-Id", "corr-promotion-usage-slash"))
            .andExpect(jsonPath("$.data.items", hasSize(1)))
            .andExpect(jsonPath("$.data.items[0].orderId", is("ord_coupon_usage_001")));
    }

    private void insertCoupon(String couponCode) {
        jdbcClient.sql("""
                insert into coupons (
                  coupon_code, name, discount_type, discount_value, min_order_amount,
                  max_discount_amount, status, starts_at, ends_at, created_at, updated_at
                )
                values (
                  :couponCode, :couponCode, 'FIXED_AMOUNT', 3000.00, 10000.00,
                  null, 'ACTIVE', :startsAt, :endsAt, now(), now()
                )
                """)
            .param("couponCode", couponCode)
            .param("startsAt", OffsetDateTime.parse("2026-01-01T00:00:00Z"))
            .param("endsAt", OffsetDateTime.parse("2099-12-31T23:59:59Z"))
            .update();
    }

    private void insertUsage(
        String orderId,
        String memberId,
        String couponCode,
        String status,
        BigDecimal orderAmount,
        BigDecimal discountAmount,
        BigDecimal payableAmount,
        String reservedAt,
        String consumedAt,
        String releasedAt,
        String releaseReason
    ) {
        jdbcClient.sql("""
                insert into coupon_usages (
                  order_id, member_id, coupon_code, status, order_amount, discount_amount,
                  payable_amount, reserved_at, consumed_at, released_at, release_reason,
                  created_at, updated_at
                )
                values (
                  :orderId, :memberId, :couponCode, :status, :orderAmount, :discountAmount,
                  :payableAmount, :reservedAt::timestamptz, :consumedAt::timestamptz,
                  :releasedAt::timestamptz, :releaseReason, :reservedAt::timestamptz,
                  coalesce(:consumedAt::timestamptz, :releasedAt::timestamptz, :reservedAt::timestamptz)
                )
                """)
            .param("orderId", orderId)
            .param("memberId", memberId)
            .param("couponCode", couponCode)
            .param("status", status)
            .param("orderAmount", orderAmount)
            .param("discountAmount", discountAmount)
            .param("payableAmount", payableAmount)
            .param("reservedAt", reservedAt)
            .param("consumedAt", consumedAt)
            .param("releasedAt", releasedAt)
            .param("releaseReason", releaseReason)
            .update();
    }
}
