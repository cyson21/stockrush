#!/usr/bin/env sh
set -eu

BOOTSTRAP_SERVER="${BOOTSTRAP_SERVER:-kafka:19092}"
KAFKA_TOPICS="/opt/kafka/bin/kafka-topics.sh"

create_topic() {
  name="$1"
  partitions="$2"
  retention_ms="$3"

  "$KAFKA_TOPICS" \
    --bootstrap-server "$BOOTSTRAP_SERVER" \
    --create \
    --if-not-exists \
    --topic "$name" \
    --partitions "$partitions" \
    --replication-factor 1 \
    --config "retention.ms=$retention_ms"
}

create_topic stockrush.order.events.v1 3 604800000
create_topic stockrush.inventory.commands.v1 3 604800000
create_topic stockrush.inventory.events.v1 3 604800000
create_topic stockrush.payment.commands.v1 3 604800000
create_topic stockrush.payment.events.v1 3 604800000

create_topic stockrush.order.events.v1.retry.1m 3 86400000
create_topic stockrush.order.events.v1.retry.5m 3 86400000
create_topic stockrush.order.events.v1.dlq 1 1209600000

create_topic stockrush.inventory.events.v1.retry.1m 3 86400000
create_topic stockrush.inventory.events.v1.retry.5m 3 86400000
create_topic stockrush.inventory.events.v1.dlq 1 1209600000

create_topic stockrush.payment.events.v1.retry.1m 3 86400000
create_topic stockrush.payment.events.v1.retry.5m 3 86400000
create_topic stockrush.payment.events.v1.dlq 1 1209600000

echo "StockRush Kafka topics are ready."

