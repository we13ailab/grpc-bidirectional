package com.javatechie.dto;

public record OrderSummaryDTO(
        int totalOrders,
        int successCount,
        double totalAmount) {
}
