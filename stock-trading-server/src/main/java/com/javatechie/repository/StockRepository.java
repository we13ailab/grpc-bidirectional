package com.javatechie.repository;

import com.javatechie.entity.Stock;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StockRepository extends JpaRepository<Stock, Long> {

    Optional<Stock> findByStockSymbol(String stockSymbol);

    boolean existsByStockSymbol(String stockSymbol);
}
