package com.javatechie.controller;

import com.javatechie.dto.OrderSummaryDTO;
import com.javatechie.dto.StockOrderDTO;
import com.javatechie.service.StockClientServiceWithUI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/stock")
public class ClientStreamingController {

    private final StockClientServiceWithUI stockClientService;

    public ClientStreamingController(StockClientServiceWithUI stockClientService) {
        this.stockClientService = stockClientService;
    }

    @GetMapping("/bulk-order")
    public ResponseEntity<Void> bulkOrderPage() {
        return ResponseEntity.status(HttpStatus.SEE_OTHER)
                .location(URI.create("/bulk-order.html"))
                .build();
    }

    @PostMapping("/bulk-order")
    public OrderSummaryDTO placeBulkOrder(@RequestBody List<StockOrderDTO> orders) {
        if (orders == null || orders.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one stock order is required");
        }
        try {
            return stockClientService.sendBulkOrders(orders);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, ex.getMessage(), ex);
        }
    }
}
