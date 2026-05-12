package com.stockrush.order.application;

@FunctionalInterface
public interface OrderIdGenerator {

    String nextId();
}

