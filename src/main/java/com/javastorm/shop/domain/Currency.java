package com.javastorm.shop.domain;

public enum Currency {
    CNY("¥"),
    USD("$");

    private final String symbol;

    Currency(String symbol) {
        this.symbol = symbol;
    }

    public String getSymbol() {
        return symbol;
    }
}
