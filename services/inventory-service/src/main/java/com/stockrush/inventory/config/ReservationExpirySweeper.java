// ReservationExpirySweeper: 만료된 재고 예약을 주기적으로 해제해 점유 재고 누수를 방지합니다.

package com.stockrush.inventory.config;

import com.stockrush.inventory.application.InventoryReservationHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    prefix = "stockrush.inventory.reservation-sweeper",
    name = "enabled",
    havingValue = "true"
)
public class ReservationExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(ReservationExpirySweeper.class);

    private final InventoryReservationHandler reservationHandler;

    public ReservationExpirySweeper(InventoryReservationHandler reservationHandler) {
        this.reservationHandler = reservationHandler;
    }

    @Scheduled(
        fixedDelayString = "${stockrush.inventory.reservation-sweeper.fixed-delay-ms:30000}",
        initialDelayString = "${stockrush.inventory.reservation-sweeper.initial-delay-ms:5000}"
    )
    void run() {
        try {
            int released = reservationHandler.releaseExpiredReservations();
            if (released > 0) {
                log.info("Released {} expired inventory reservations", released);
            }
        } catch (RuntimeException e) {
            log.error("Inventory reservation expiry sweep failed", e);
        }
    }
}
