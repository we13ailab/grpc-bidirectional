package com.javatechie.config;

import com.javatechie.grpc.StockTradingServiceGrpc;
import io.grpc.ManagedChannel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;

/**
 * Builds the gRPC channel and stubs from the
 * {@code spring.grpc.client.channels.stockService.*} properties
 * (see application.yml). The channel is shut down with the Spring context.
 */
@Configuration
public class GrpcClientConfig {

    @Bean(destroyMethod = "shutdown")
    public ManagedChannel stockTradingChannel(GrpcChannelFactory channelFactory) {
        return channelFactory.createChannel("stockService");
    }

    @Bean
    public StockTradingServiceGrpc.StockTradingServiceBlockingStub stockTradingBlockingStub(
            ManagedChannel stockTradingChannel) {
        return StockTradingServiceGrpc.newBlockingStub(stockTradingChannel);
    }

    @Bean
    public StockTradingServiceGrpc.StockTradingServiceStub stockTradingAsyncStub(
            ManagedChannel stockTradingChannel) {
        return StockTradingServiceGrpc.newStub(stockTradingChannel);
    }
}
