package com.javatechie.controller;

import com.google.protobuf.util.JsonFormat;
import com.javatechie.grpc.StockRequest;
import com.javatechie.grpc.StockResponse;
import com.javatechie.grpc.StockTradingServiceGrpc;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

@RestController
@RequestMapping("/stocks")
public class StockStreamingController {

    private static final long STREAM_TIMEOUT_MILLIS = 15_000;

    private final StockTradingServiceGrpc.StockTradingServiceStub stockServiceStub;

    public StockStreamingController(StockTradingServiceGrpc.StockTradingServiceStub stockServiceStub) {
        this.stockServiceStub = stockServiceStub;
    }


    @GetMapping(value = "/subscribe/{symbol}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribeStockPrice(@PathVariable String symbol) {
        String normalizedSymbol = symbol.trim().toUpperCase(Locale.ROOT);
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        AtomicReference<ClientCallStreamObserver<StockRequest>> call = new AtomicReference<>();

        emitter.onTimeout(() -> cancel(call.get(), "SSE client timed out"));
        emitter.onError(error -> cancel(call.get(), "SSE client disconnected"));

        StockRequest request = StockRequest.newBuilder()
                .setStockSymbol(normalizedSymbol)
                .build();
        stockServiceStub.subscribeStockPrice(request, new ClientResponseObserver<StockRequest, StockResponse>() {
            @Override
            public void beforeStart(ClientCallStreamObserver<StockRequest> requestStream) {
                call.set(requestStream);
            }

            @Override
            public void onNext(StockResponse response) {
                try {
                    String jsonResponse = JsonFormat.printer().print(response);
                    emitter.send(SseEmitter.event()
                            .data(jsonResponse, MediaType.APPLICATION_JSON));
                } catch (IOException | IllegalStateException e) {
                    cancel(call.get(), "Unable to deliver stock update");
                    emitter.completeWithError(e);
                }
            }

            @Override
            public void onError(Throwable throwable) {
                emitter.completeWithError(throwable);
            }

            @Override
            public void onCompleted() {
                emitter.complete();
            }
        });
        return emitter;
    }

    private void cancel(ClientCallStreamObserver<StockRequest> call, String reason) {
        if (call != null) {
            call.cancel(reason, null);
        }
    }
}