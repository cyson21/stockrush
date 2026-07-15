-- K8s 초기화 컨테이너에서 실행할 스키마 정의를 담은 SQL 템플릿입니다.
create schema if not exists auth;
create schema if not exists catalog;
create schema if not exists inventory;
create schema if not exists orders;
create schema if not exists payment;
create schema if not exists promotion;
create schema if not exists fulfillment;
create schema if not exists read_model;
