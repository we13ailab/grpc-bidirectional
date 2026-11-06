package com.javatechie.service;

import com.javatechie.dto.OrderSummaryDTO;
import com.javatechie.dto.StockOrderDTO;
import com.javatechie.grpc.OrderSummary;
import com.javatechie.grpc.StockOrder;
import com.javatechie.grpc.StockTradingServiceGrpc;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class StockClientServiceWithUI {

    private static final long RESPONSE_TIMEOUT_SECONDS = 15;

    private final StockTradingServiceGrpc.StockTradingServiceStub stockServiceStub;

    public StockClientServiceWithUI(StockTradingServiceGrpc.StockTradingServiceStub stockServiceStub) {
        this.stockServiceStub = stockServiceStub;
    }

    public OrderSummaryDTO sendBulkOrders(List<StockOrderDTO> orders) {
        List<StockOrder> grpcOrders = orders.stream()
                .map(this::toGrpcOrder)
                .toList();
        CompletableFuture<OrderSummaryDTO> result = new CompletableFuture<>();

        StreamObserver<StockOrder> requestObserver = stockServiceStub
                .withDeadlineAfter(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .bulkStockOrder(new StreamObserver<>() {
                    @Override
                    public void onNext(OrderSummary summary) {
                        result.complete(new OrderSummaryDTO(
                                summary.getTotalOrders(),
                                summary.getSuccessCount(),
                                summary.getTotalAmount()));
                    }

                    @Override
                    public void onError(Throwable error) {
                        result.completeExceptionally(error);
                    }

                    @Override
                    public void onCompleted() {
                        if (!result.isDone()) {
                            result.completeExceptionally(
                                    new IllegalStateException("gRPC server completed without an order summary"));
                        }
                    }
                });

        try {
            grpcOrders.forEach(requestObserver::onNext);
            requestObserver.onCompleted();
            return result.get(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException ex) {
            throw new IllegalStateException("Timed out waiting for the gRPC bulk order summary", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the gRPC bulk order summary", ex);
        } catch (java.util.concurrent.ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof StatusRuntimeException statusError) {
                throw new IllegalStateException("gRPC bulk order failed: " + statusError.getStatus().getDescription(),
                        statusError);
            }
            if (cause instanceof IllegalStateException stateError) {
                throw stateError;
            }
            throw new IllegalStateException("gRPC bulk order failed", cause);
        } catch (RuntimeException ex) {
            requestObserver.onError(ex);
            throw new IllegalStateException("Unable to submit bulk orders to the gRPC server", ex);
        }
    }

    private StockOrder toGrpcOrder(StockOrderDTO order) {
        if (order == null) {
            throw new IllegalArgumentException("Orders cannot contain null entries");
        }
        if (order.orderId() == null || order.orderId().isBlank()) {
            throw new IllegalArgumentException("Order ID is required");
        }
        if (order.stockSymbol() == null || order.stockSymbol().isBlank()) {
            throw new IllegalArgumentException("Stock symbol is required for order " + order.orderId());
        }
        if (order.orderType() == null
                || !(order.orderType().equalsIgnoreCase("BUY") || order.orderType().equalsIgnoreCase("SELL"))) {
            throw new IllegalArgumentException("Order type must be BUY or SELL for order " + order.orderId());
        }
        if (order.quantity() <= 0) {
            throw new IllegalArgumentException("Quantity must be greater than zero for order " + order.orderId());
        }
        if (!Double.isFinite(order.price()) || order.price() <= 0) {
            throw new IllegalArgumentException("Price must be a positive finite number for order " + order.orderId());
        }

        return StockOrder.newBuilder()
                .setOrderId(order.orderId().trim())
                .setStockSymbol(order.stockSymbol().trim().toUpperCase(Locale.ROOT))
                .setOrderType(order.orderType().trim().toUpperCase(Locale.ROOT))
                .setPrice(order.price())
                .setQuantity(order.quantity())
                .build();
    }
}
