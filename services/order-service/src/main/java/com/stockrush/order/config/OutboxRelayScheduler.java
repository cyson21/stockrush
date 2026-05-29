// OutboxRelayScheduler: 런타임 설정/스케줄링/메시징 구성을 구성해 인프라 동작을 보장합니다.

package com.stockrush.order.config;

import com.stockrush.order.infra.outbox.OutboxRelayService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    prefix = "stockrush.outbox.relay",
    name = "enabled",
    havingValue = "true"
)
public class OutboxRelayScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayScheduler.class);

    private final OutboxRelayService outboxRelayService;
    private final int batchSize;

    public OutboxRelayScheduler(
        OutboxRelayService outboxRelayService,
        @Value("${stockrush.outbox.relay.batch-size:100}") int batchSize
    ) {
        this.outboxRelayService = outboxRelayService;
        this.batchSize = batchSize;
    }

    @Scheduled(
        fixedDelayString = "${stockrush.outbox.relay.fixed-delay-ms:5000}",
        initialDelayString = "${stockrush.outbox.relay.initial-delay-ms:1000}"
    )
    void run() {
        try {
            outboxRelayService.publishPending(batchSize);
        } catch (RuntimeException e) {
            log.error("Order outbox relay failed", e);
        }
    }
}
