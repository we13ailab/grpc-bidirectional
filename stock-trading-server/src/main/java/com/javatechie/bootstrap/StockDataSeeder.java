package com.javatechie.bootstrap;

import com.javatechie.entity.Stock;
import com.javatechie.repository.StockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Seeds the {@code stocks} table with a handful of well-known symbols on
 * startup so the demo works against a fresh database. Idempotent: existing
 * rows are never overwritten.
 */
@Component
public class StockDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(StockDataSeeder.class);

    private static final Map<String, Double> DEFAULT_STOCKS = new LinkedHashMap<>();

    static {
        DEFAULT_STOCKS.put("AAPL", 232.54);
        DEFAULT_STOCKS.put("MSFT", 415.20);
        DEFAULT_STOCKS.put("GOOG", 175.80);
        DEFAULT_STOCKS.put("AMZN", 205.10);
        DEFAULT_STOCKS.put("META", 585.30);
        DEFAULT_STOCKS.put("NVDA", 138.25);
        DEFAULT_STOCKS.put("AMD", 148.60);
        DEFAULT_STOCKS.put("INTC", 24.35);
        DEFAULT_STOCKS.put("NFLX", 905.40);
        DEFAULT_STOCKS.put("TSLA", 352.90);
    }

    private final StockRepository stockRepository;

    public StockDataSeeder(StockRepository stockRepository) {
        this.stockRepository = stockRepository;
    }

    @Override
    public void run(String... args) {
        int seeded = 0;
        for (Map.Entry<String, Double> entry : DEFAULT_STOCKS.entrySet()) {
            if (!stockRepository.existsByStockSymbol(entry.getKey())) {
                stockRepository.save(new Stock(entry.getKey(), entry.getValue()));
                seeded++;
            }
        }
        if (seeded > 0) {
            log.info("Seeded {} stock(s) into the database", seeded);
        } else {
            log.info("Database already contains stock data, skipping seeding");
        }
    }
}
