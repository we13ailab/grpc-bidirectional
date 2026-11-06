package com.javatechie.service;

import com.javatechie.entity.Stock;
import com.javatechie.grpc.OrderSummary;
import com.javatechie.grpc.StockOrder;
import com.javatechie.grpc.StockRequest;
import com.javatechie.grpc.StockResponse;
import com.javatechie.grpc.StockTradingServiceGrpc;
import com.javatechie.grpc.TradeStatus;
import com.javatechie.repository.StockRepository;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.grpc.server.service.GrpcService;

import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Implements the four gRPC communication patterns exposed by
 * {@code stocktrading.StockTradingService}:
 *
 * <ol>
 *   <li>{@code getStockPrice} - unary</li>
 *   <li>{@code subscribeStockPrice} - server streaming</li>
 *   <li>{@code bulkStockOrder} - client streaming</li>
 *   <li>{@code liveTrading} - bidirectional streaming</li>
 * </ol>
 */
@GrpcService
public class StockTradingServiceImpl extends StockTradingServiceGrpc.StockTradingServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(StockTradingServiceImpl.class);

    /** Number of price updates pushed by the server-streaming RPC. */
    private static final int PRICE_UPDATES = 10;
    /** Delay between two consecutive price updates. */
    private static final long UPDATE_INTERVAL_MILLIS = 1_000;
    /** How far a simulated price may drift from the stored base price. */
    private static final double PRICE_DRIFT = 5.0;

    private final StockRepository stockRepository;

    public StockTradingServiceImpl(StockRepository stockRepository) {
        this.stockRepository = stockRepository;
    }

    // ======================== 1. UNARY ========================

    @Override
    public void getStockPrice(StockRequest request, StreamObserver<StockResponse> responseObserver) {
        String symbol = request.getStockSymbol();

        var stock = stockRepository.findByStockSymbol(symbol);
        if (stock.isEmpty()) {
            // a proper gRPC NOT_FOUND instead of an NPE-turned-INTERNAL
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription("Stock not found: " + symbol)
                    .asRuntimeException());
            return;
        }

        Stock entity = stock.get();
        StockResponse response = StockResponse.newBuilder()
                .setStockSymbol(entity.getStockSymbol())
                .setPrice(entity.getPrice())
                .setTimestamp(entity.getLastUpdated().toString())
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    // ==================== 2. SERVER STREAMING ====================

    @Override
    public void subscribeStockPrice(StockRequest request, StreamObserver<StockResponse> responseObserver) {
        String symbol = request.getStockSymbol();
        // simulate the live market around the last known price (fall back if unknown)
        double basePrice = stockRepository.findByStockSymbol(symbol)
                .map(Stock::getPrice)
                .orElse(100.0);

        try {
            for (int i = 1; i <= PRICE_UPDATES; i++) {
                if (responseObserver instanceof ServerCallStreamObserver<?> serverObserver
                        && serverObserver.isCancelled()) {
                    // stop burning a server thread when the client is gone
                    log.info("Client cancelled the price stream for {}", symbol);
                    return;
                }
                responseObserver.onNext(nextPrice(symbol, basePrice));
                if (i < PRICE_UPDATES) {
                    TimeUnit.MILLISECONDS.sleep(UPDATE_INTERVAL_MILLIS);
                }
            }
            responseObserver.onCompleted();
        } catch (InterruptedException ex) {
            // shutting down: nothing sensible left to send
            Thread.currentThread().interrupt();
            log.warn("Price stream for {} interrupted, aborting", symbol);
        }
    }

    private StockResponse nextPrice(String symbol, double basePrice) {
        double drift = ThreadLocalRandom.current().nextDouble(-PRICE_DRIFT, PRICE_DRIFT);
        double price = Math.round((basePrice + drift) * 100.0) / 100.0;
        return StockResponse.newBuilder()
                .setStockSymbol(symbol)
                .setPrice(price)
                .setTimestamp(Instant.now().toString())
                .build();
    }

    // ==================== 3. CLIENT STREAMING ====================

    @Override
    public StreamObserver<StockOrder> bulkStockOrder(StreamObserver<OrderSummary> responseObserver) {
        return new StreamObserver<>() {

            private int totalOrders;
            private int successCount;
            private double totalAmount;

            @Override
            public void onNext(StockOrder order) {
                totalOrders++;
                if (isValid(order)) {
                    successCount++;
                    totalAmount += order.getPrice() * order.getQuantity();
                    log.info("Bulk order accepted: {}", order.getOrderId());
                } else {
                    log.warn("Bulk order rejected (invalid symbol/quantity/price): {}", order.getOrderId());
                }
            }

            @Override
            public void onError(Throwable throwable) {
                log.error("Bulk order stream failed: {}", throwable.getMessage(), throwable);
            }

            @Override
            public void onCompleted() {
                OrderSummary summary = OrderSummary.newBuilder()
                        .setTotalOrders(totalOrders)
                        .setSuccessCount(successCount)
                        .setTotalAmount(totalAmount)
                        .build();
                log.info("Bulk order summary: {} received, {} executed, total ${}",
                        totalOrders, successCount, totalAmount);
                responseObserver.onNext(summary);
                responseObserver.onCompleted();
            }
        };
    }

    // ================= 4. BIDIRECTIONAL STREAMING =================

    @Override
    public StreamObserver<StockOrder> liveTrading(StreamObserver<TradeStatus> responseObserver) {
        return new StreamObserver<>() {

            @Override
            public void onNext(StockOrder order) {
                log.info("Live order received: {}", order);
                responseObserver.onNext(execute(order));
            }

            @Override
            public void onError(Throwable throwable) {
                log.error("Live trading stream failed: {}", throwable.getMessage(), throwable);
            }

            @Override
            public void onCompleted() {
                log.info("Client finished sending orders, closing trading stream");
                responseObserver.onCompleted();
            }
        };
    }

    // ======================== helpers ========================

    private boolean isValid(StockOrder order) {
        return !order.getStockSymbol().isBlank()
                && order.getQuantity() > 0
                && order.getPrice() > 0;
    }

    private TradeStatus execute(StockOrder order) {
        TradeStatus.Builder status = TradeStatus.newBuilder()
                .setOrderId(order.getOrderId())
                .setTimestamp(Instant.now().toString());

        if (order.getStockSymbol().isBlank()) {
            return status.setStatus("FAILED").setMessage("Missing stock symbol").build();
        }
        if (order.getQuantity() <= 0) {
            return status.setStatus("FAILED").setMessage("Invalid quantity: " + order.getQuantity()).build();
        }
        if (order.getPrice() <= 0) {
            return status.setStatus("FAILED").setMessage("Invalid price: " + order.getPrice()).build();
        }
        return status.setStatus("EXECUTED")
                .setMessage("%s %d x %s @ $%.2f processed successfully"
                        .formatted(order.getOrderType(), order.getQuantity(),
                                order.getStockSymbol(), order.getPrice()))
                .build();
    }
}
