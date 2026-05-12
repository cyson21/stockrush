package com.stockrush.inventory.application;

import java.util.List;

public interface OutboxEventRepository {

    List<OutboxEventSnapshot> findOutboxEvents(int limit, int offset, List<String> statuses);
}
