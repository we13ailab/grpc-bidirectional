package com.javatechie;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Loads the full application context (JPA + gRPC server). Requires MySQL to
 * be running: {@code docker compose up -d} from the repository root.
 */
@SpringBootTest
class StockTradingServerApplicationTests {

	@Test
	void contextLoads() {
	}
}
