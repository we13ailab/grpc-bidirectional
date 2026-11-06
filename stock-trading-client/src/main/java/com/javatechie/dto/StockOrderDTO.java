package com.javatechie.dto;

public record StockOrderDTO(
        String orderId,
        String stockSymbol,
        String orderType,
        double price,
        int quantity) {
}
