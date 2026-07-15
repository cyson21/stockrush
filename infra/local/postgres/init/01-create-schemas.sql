-- 초기 스키마(서비스별 DB namespace)를 생성해 배포 초기 마이그레이션 기준을 맞춥니다.
create schema if not exists auth;
create schema if not exists catalog;
create schema if not exists inventory;
create schema if not exists orders;
create schema if not exists payment;
create schema if not exists promotion;
create schema if not exists fulfillment;
create schema if not exists read_model;

