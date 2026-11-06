package com.javatechie.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "stocks")
public class Stock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stock_symbol", unique = true, nullable = false)
    private String stockSymbol;

    @Column(nullable = false)
    private double price;

    @Column(name = "last_updated", nullable = false)
    private LocalDateTime lastUpdated;

    protected Stock() {
        // required by JPA
    }

    public Stock(String stockSymbol, double price) {
        this.stockSymbol = stockSymbol;
        this.price = price;
    }

    /**
     * Keeps {@code last_updated} fresh without relying on callers to set it.
     */
    @PrePersist
    @PreUpdate
    void touch() {
        this.lastUpdated = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getStockSymbol() {
        return stockSymbol;
    }

    public double getPrice() {
        return price;
    }

    public void setPrice(double price) {
        this.price = price;
    }

    public LocalDateTime getLastUpdated() {
        return lastUpdated;
    }

    @Override
    public String toString() {
        return "Stock{symbol='%s', price=%.2f, lastUpdated=%s}".formatted(stockSymbol, price, lastUpdated);
    }
}
