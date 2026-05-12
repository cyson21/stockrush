# Outbox and Consumer Idempotency

## Purpose

Each Kafka-producing service stores events in its own schema before publishing to Kafka. The local database transaction writes domain state and outbox rows together. A relay publishes outbox rows after the transaction completes.

## Outbox Table

Each producing service owns `outbox_events` in its schema.

```sql
create table if not exists outbox_events (
  id bigserial primary key,
  event_id uuid unique not null,
  aggregate_type varchar(50) not null,
  aggregate_id varchar(100) not null,
  event_type varchar(100) not null,
  event_version integer not null,
  topic varchar(150) not null,
  partition_key varchar(100) not null,
  correlation_id varchar(100) not null,
  idempotency_key varchar(150) not null,
  payload jsonb not null,
  headers jsonb not null default '{}'::jsonb,
  status varchar(30) not null,
  retry_count integer not null default 0,
  max_retry_count integer not null default 5,
  next_retry_at timestamptz null,
  error_message text null,
  created_at timestamptz not null,
  published_at timestamptz null,
  updated_at timestamptz not null
);
```

Status values:

```text
PENDING
PUBLISHING
PUBLISHED
FAILED
```

## Relay Rules

1. Select `PENDING` rows whose `next_retry_at` is null or due.
2. Lock rows to avoid duplicate relay work.
3. Publish to Kafka using `topic` and `partition_key`.
4. Mark rows `PUBLISHED` with `published_at`.
5. On transient publish failure, increase `retry_count` and set `next_retry_at`.
6. On exhausted retry, mark `FAILED` and preserve `error_message`.

## Consumer Idempotency Table

Each Kafka-consuming service owns `processed_events` in its schema.

```sql
create table if not exists processed_events (
  event_id uuid not null,
  consumer_group varchar(120) not null,
  event_type varchar(100) not null,
  aggregate_id varchar(100) not null,
  idempotency_key varchar(150) not null,
  processed_at timestamptz not null,
  primary key (event_id, consumer_group)
);
```

Consumers insert a processed event row in the same transaction as their business state change. Duplicate inserts mean the event was already handled by that consumer group.

## Retry Classification

Retry:

- database timeout
- lock timeout
- temporary Kafka publish failure
- local service temporarily unavailable

Do not retry as a technical failure:

- insufficient stock
- payment declined
- invalid order state
- duplicate idempotency key with different payload

