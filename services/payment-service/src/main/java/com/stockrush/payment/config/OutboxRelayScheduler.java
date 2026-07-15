package com.stockrush.payment.config;

import com.stockrush.payment.infra.outbox.OutboxRelayService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
/**
 * 아웃박스 릴레이 스케줄러 동작을 제어하는 빈 설정으로 재시도·지연 처리를 운영합니다.
 */


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
            log.error("Payment outbox relay failed", e);
        }
    }
}
