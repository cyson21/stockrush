package com.stockrush.order.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.stockrush.order.infra.outbox.OutboxRelayResult;
import com.stockrush.order.infra.outbox.OutboxRelayService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;

class OutboxRelaySchedulerTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(TestConfig.class, OutboxRelayScheduler.class);

    @Test
    void enables_and_invokes_relay_when_property_is_enabled() {
        contextRunner.withPropertyValues(
            "stockrush.outbox.relay.enabled=true",
            "stockrush.outbox.relay.batch-size=12",
            "stockrush.outbox.relay.fixed-delay-ms=250",
            "stockrush.outbox.relay.initial-delay-ms=50"
        ).run(context -> {
            OutboxRelayService relayService = context.getBean(OutboxRelayService.class);
            OutboxRelayScheduler scheduler = context.getBean(OutboxRelayScheduler.class);
            OutboxRelayResult expected = new OutboxRelayResult(1, 1, 0);
            when(relayService.publishPending(12)).thenReturn(expected);

            scheduler.run();

            verify(relayService).publishPending(12);
        });
    }

    @Test
    void does_not_create_scheduler_when_property_is_disabled() {
        contextRunner.withPropertyValues("stockrush.outbox.relay.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(OutboxRelayScheduler.class);
        });
    }

    @Test
    void swallows_runtime_exception_from_relay_and_continues() {
        contextRunner.withPropertyValues(
            "stockrush.outbox.relay.enabled=true",
            "stockrush.outbox.relay.batch-size=50"
        ).run(context -> {
            OutboxRelayService relayService = context.getBean(OutboxRelayService.class);
            OutboxRelayScheduler scheduler = context.getBean(OutboxRelayScheduler.class);
            when(relayService.publishPending(50)).thenThrow(new RuntimeException("publish failed"));

            assertDoesNotThrow(scheduler::run);
            verify(relayService).publishPending(50);
        });
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        OutboxRelayService outboxRelayService() {
            return mock(OutboxRelayService.class);
        }
    }
}
