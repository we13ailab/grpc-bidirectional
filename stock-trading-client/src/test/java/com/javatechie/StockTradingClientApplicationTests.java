package com.javatechie;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Loads the client context, including the gRPC channel and stub beans.
 * The demo runner is disabled so no server needs to be running.
 */
@SpringBootTest(properties = "client.demo.enabled=false")
class StockTradingClientApplicationTests {

	@Test
	void contextLoads() {
	}
}
