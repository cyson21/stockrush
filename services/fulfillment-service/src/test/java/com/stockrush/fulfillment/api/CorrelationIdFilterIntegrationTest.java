
package com.stockrush.fulfillment.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:postgresql://localhost:15432/stockrush?currentSchema=fulfillment",
    "spring.datasource.username=stockrush",
    "spring.datasource.password=stockrush"
})
class CorrelationIdFilterIntegrationTest {

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private CorrelationIdFilter correlationIdFilter;

    @BeforeEach
    void setUp() {
        MDC.clear();
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
            .addFilters(correlationIdFilter)
            .build();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void stores_incoming_correlation_id_in_mdc_during_request_and_clears_it_afterwards() throws Exception {
        mockMvc.perform(get("/test/correlation-mdc")
                .header(CorrelationIds.HEADER_NAME, "corr-fulfillment-mdc"))
            .andExpect(status().isOk())
            .andExpect(header().string(CorrelationIds.HEADER_NAME, "corr-fulfillment-mdc"))
            .andExpect(jsonPath("$.success", is(true)))
            .andExpect(jsonPath("$.data", is("corr-fulfillment-mdc")))
            .andExpect(jsonPath("$.trace.correlationId", is("corr-fulfillment-mdc")));

        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void generates_one_correlation_id_for_missing_header_and_exposes_it_to_controller() throws Exception {
        MvcResult result = mockMvc.perform(get("/test/correlation-mdc"))
            .andExpect(status().isOk())
            .andReturn();

        String correlationId = result.getResponse().getHeader(CorrelationIds.HEADER_NAME);
        assertThat(correlationId).isNotBlank();
        assertThat(result.getResponse().getContentAsString())
            .contains("\"data\":\"" + correlationId + "\"")
            .contains("\"correlationId\":\"" + correlationId + "\"");
        assertThat(MDC.get("correlationId")).isNull();
    }

    @TestConfiguration
    static class TestControllerConfig {

        @Bean
        TestCorrelationController testCorrelationController() {
            return new TestCorrelationController();
        }
    }

    @RestController
    static class TestCorrelationController {

        @GetMapping("/test/correlation-mdc")
        ResponseEntity<ApiResponse<String>> readMdc(
            @RequestHeader(value = CorrelationIds.HEADER_NAME, required = false) String correlationId
        ) {
            String resolvedCorrelationId = CorrelationIds.resolve(correlationId);
            return ResponseEntity.ok()
                .header(CorrelationIds.HEADER_NAME, resolvedCorrelationId)
                .body(ApiResponse.success(MDC.get("correlationId"), resolvedCorrelationId));
        }
    }
}
