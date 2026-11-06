package com.javatechie.service;

import com.javatechie.grpc.OrderSummary;
import com.javatechie.grpc.StockOrder;
import com.javatechie.grpc.StockRequest;
import com.javatechie.grpc.StockResponse;
import com.javatechie.grpc.StockTradingServiceGrpc;
import com.javatechie.grpc.TradeStatus;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Client-side demos for the four gRPC communication patterns exposed by the
 * stock-trading-server. Streaming calls use a latch so each demo only returns
 * once the server has finished responding.
 */
@Service
public class StockClientService {

    private static final Logger log = LoggerFactory.getLogger(StockClientService.class);

    /** Upper bound for waiting on a server stream before giving up. */
    private static final long STREAM_TIMEOUT_SECONDS = 30;
    /** Pacing between orders in the live-trading demo. */
    private static final long ORDER_PACING_MILLIS = 300;

    private final StockTradingServiceGrpc.StockTradingServiceBlockingStub blockingStub;
    private final StockTradingServiceGrpc.StockTradingServiceStub asyncStub;

    public StockClientService(StockTradingServiceGrpc.StockTradingServiceBlockingStub blockingStub,
                              StockTradingServiceGrpc.StockTradingServiceStub asyncStub) {
        this.blockingStub = blockingStub;
        this.asyncStub = asyncStub;
    }

    // ---------- 1. UNARY: one request, one response ----------

    public void getStockPrice(String symbol) {
        StockResponse response = blockingStub.getStockPrice(
                StockRequest.newBuilder().setStockSymbol(symbol).build());
        log.info("[UNARY] {} price = ${} (as of {})",
                response.getStockSymbol(), response.getPrice(), response.getTimestamp());
    }

    // ---------- 2. SERVER STREAMING: one request, stream of prices ----------

    public void subscribeStockPrice(String symbol) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        StockRequest request = StockRequest.newBuilder().setStockSymbol(symbol).build();

        asyncStub.subscribeStockPrice(request, new StreamObserver<>() {
            @Override
            public void onNext(StockResponse response) {
                log.info("[SERVER-STREAM] {} price = ${} (as of {})",
                        response.getStockSymbol(), response.getPrice(), response.getTimestamp());
            }

            @Override
            public void onError(Throwable throwable) {
                log.error("[SERVER-STREAM] failed: {}", throwable.getMessage());
                done.countDown();
            }

            @Override
            public void onCompleted() {
                log.info("[SERVER-STREAM] price stream completed");
                done.countDown();
            }
        });

        await(done, "subscribeStockPrice");
    }

    // ---------- 3. CLIENT STREAMING: stream of orders, one summary ----------

    public void placeBulkOrders() throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);

        StreamObserver<OrderSummary> responseObserver = new StreamObserver<>() {
            @Override
            public void onNext(OrderSummary summary) {
                log.info("[CLIENT-STREAM] summary: {} order(s) received, {} executed, total ${}",
                        summary.getTotalOrders(), summary.getSuccessCount(), summary.getTotalAmount());
            }

            @Override
            public void onError(Throwable throwable) {
                log.error("[CLIENT-STREAM] failed: {}", throwable.getMessage());
                done.countDown();
            }

            @Override
            public void onCompleted() {
                log.info("[CLIENT-STREAM] bulk order stream completed");
                done.countDown();
            }
        };

        StreamObserver<StockOrder> requestObserver = asyncStub.bulkStockOrder(responseObserver);

        try {
            // the last order is intentionally invalid to show server-side validation
            sampleOrders().forEach(requestObserver::onNext);
            requestObserver.onCompleted();
        } catch (RuntimeException ex) {
            requestObserver.onError(ex);
        }

        await(done, "placeBulkOrders");
    }

    private List<StockOrder> sampleOrders() {
        return List.of(
                order("ORD-1", "AAPL", "BUY", 10, 227.15),
                order("ORD-2", "GOOG", "SELL", 5, 172.30),
                order("ORD-3", "TSLA", "BUY", 8, 348.62),
                order("ORD-4", "MSFT", "BUY", 6, 412.05),
                order("ORD-5", "NVDA", "SELL", 0, 139.44));
    }

    // ---------- 4. BIDIRECTIONAL STREAMING: orders in, statuses out ----------

    public void startLiveTrading() throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);

        StreamObserver<StockOrder> requestObserver = asyncStub.liveTrading(new StreamObserver<>() {
            @Override
            public void onNext(TradeStatus status) {
                log.info("[BIDI] {} -> {} ({})",
                        status.getOrderId(), status.getStatus(), status.getMessage());
            }

            @Override
            public void onError(Throwable throwable) {
                log.error("[BIDI] failed: {}", throwable.getMessage());
                done.countDown();
            }

            @Override
            public void onCompleted() {
                log.info("[BIDI] live trading session completed");
                done.countDown();
            }
        });

        try {
            String[] symbols = {"AAPL", "MSFT", "TSLA", "NVDA", "GOOG"};
            for (int i = 1; i <= 10; i++) {
                requestObserver.onNext(order("ORDER-%02d".formatted(i),
                        symbols[i % symbols.length],
                        i % 2 == 0 ? "BUY" : "SELL",
                        i * 10,
                        150.0 + i));
                if (i < 10) {
                    TimeUnit.MILLISECONDS.sleep(ORDER_PACING_MILLIS);
                }
            }
            requestObserver.onCompleted();
        } catch (RuntimeException ex) {
            requestObserver.onError(ex);
        }

        await(done, "liveTrading");
    }

    // ---------- helpers ----------

    private StockOrder order(String id, String symbol, String type, int quantity, double price) {
        return StockOrder.newBuilder()
                .setOrderId(id)
                .setStockSymbol(symbol)
                .setOrderType(type)
                .setQuantity(quantity)
                .setPrice(price)
                .build();
    }

    private void await(CountDownLatch latch, String rpc) throws InterruptedException {
        if (!latch.await(STREAM_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            log.warn("{}: stream did not complete within {}s", rpc, STREAM_TIMEOUT_SECONDS);
        }
    }
}
