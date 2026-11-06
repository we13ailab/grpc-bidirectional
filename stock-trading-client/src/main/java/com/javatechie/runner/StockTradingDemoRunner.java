package com.javatechie.runner;

import com.javatechie.service.StockClientService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Runs one round of each gRPC communication pattern against the
 * stock-trading-server, then lets the application exit.
 *
 * <p>Disable with {@code --client.demo.enabled=false} (the context test does
 * this so it can boot without a running server).
 */
@Component
@ConditionalOnProperty(name = "client.demo.enabled", havingValue = "true", matchIfMissing = true)
public class StockTradingDemoRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(StockTradingDemoRunner.class);

    private final StockClientService stockClientService;

    public StockTradingDemoRunner(StockClientService stockClientService) {
        this.stockClientService = stockClientService;
    }

    @Override
    public void run(String... args) throws Exception {
        log.info("=== 1/4 UNARY: getStockPrice ===");
        stockClientService.getStockPrice("AAPL");

        log.info("=== 2/4 SERVER STREAMING: subscribeStockPrice ===");
        stockClientService.subscribeStockPrice("GOOG");

        log.info("=== 3/4 CLIENT STREAMING: bulkStockOrder ===");
        stockClientService.placeBulkOrders();

        log.info("=== 4/4 BIDIRECTIONAL STREAMING: liveTrading ===");
        stockClientService.startLiveTrading();

        log.info("All four gRPC patterns completed successfully");
    }
}
