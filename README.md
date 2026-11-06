# gRPC Bidirectional Stock Trading Demo

A Java/Spring Boot stock-trading demo that shows the four gRPC communication patterns between a client and server. The server persists stock prices in MySQL; the client includes a web UI for live price updates and submitting client-streamed bulk orders.

| Pattern | RPC | Request → response |
|---|---|---|
| Unary | `getStockPrice` | One `StockRequest` → one `StockResponse` |
| Server streaming | `subscribeStockPrice` | One `StockRequest` → a stream of `StockResponse` updates |
| Client streaming | `bulkStockOrder` | A stream of `StockOrder` messages → one `OrderSummary` |
| Bidirectional streaming | `liveTrading` | A stream of `StockOrder` messages ⇄ a stream of `TradeStatus` messages |

```text
Browser ──HTTP/SSE──▶ stock-trading-client ──gRPC over HTTP/2──▶ stock-trading-server ──JPA──▶ MySQL
                             :8080                                  :9090
```

## Technology stack

| Component | Technology |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.5.16 |
| gRPC integration | Spring gRPC 0.12.0 |
| RPC runtime | gRPC Java 1.76.0, Protobuf 3.25.8 |
| Database | MySQL 8.0 with Spring Data JPA / Hibernate |
| Build | Maven |

## Requirements

- Java 21+
- Maven 3.9+
- Docker (for the included MySQL service)
- Optional: [grpcurl](https://github.com/fullstorydev/grpcurl) or Postman with gRPC support

## Project layout

```text
grpc-bidirectional/
├── compose.yaml
├── order.txt
├── stock-trading-server/
│   └── src/main/
│       ├── proto/stock_trading.proto
│       ├── java/com/javatechie/
│       │   ├── StockTradingServerApplication.java
│       │   ├── bootstrap/StockDataSeeder.java
│       │   ├── entity/Stock.java
│       │   ├── repository/StockRepository.java
│       │   └── service/StockTradingServiceImpl.java
│       └── resources/application.yml
└── stock-trading-client/
    └── src/main/
        ├── proto/stock_trading.proto
        ├── java/com/javatechie/
        │   ├── StockTradingClientApplication.java
        │   ├── config/GrpcClientConfig.java
        │   ├── controller/
        │   │   ├── ClientStreamingController.java
        │   │   └── StockStreamingController.java
        │   ├── dto/
        │   │   ├── OrderSummaryDTO.java
        │   │   └── StockOrderDTO.java
        │   ├── runner/StockTradingDemoRunner.java
        │   └── service/
        │       ├── StockClientService.java
        │       └── StockClientServiceWithUI.java
        └── resources/
            ├── application.yml
            └── static/
                ├── index.html
                └── bulk-order.html
```

Each module contains a copy of `stock_trading.proto`; keep them identical when changing the RPC contract. Protobuf and gRPC Java sources are generated during the Maven build.

## Start the applications

### 1. Start MySQL

From the repository root:

```bash
docker compose up -d
docker compose ps
```

Compose starts MySQL 8 on `localhost:3306`, creates the `javatechie` database, and stores its files in the `mysql_data` volume. Its health check is available via `docker compose ps`. The default local credentials are root / `Password`. Override them with `MYSQL_ROOT_PASSWORD`, `MYSQL_DATABASE`, and `MYSQL_PORT` when starting Compose.

### 2. Start the gRPC server

In another terminal:

```bash
cd stock-trading-server
mvn spring-boot:run
# Or build and run the packaged application:
# mvn package -DskipTests
# java -jar target/stock-trading-server-0.0.1-SNAPSHOT.jar
```

The server creates/updates the `stocks` table and listens for plaintext gRPC on port `9090`. On startup, `StockDataSeeder` inserts sample symbols if they are missing; it does not overwrite existing rows. The seeded symbols are AAPL, MSFT, GOOG, AMZN, META, NVDA, AMD, INTC, NFLX, and TSLA. gRPC reflection and health services are enabled.

### 3. Start the web client

In another terminal:

```bash
cd stock-trading-client
mvn spring-boot:run -Dspring-boot.run.arguments=--client.demo.enabled=false
# Or build and run the packaged application:
# mvn package -DskipTests
# java -jar target/stock-trading-client-0.0.1-SNAPSHOT.jar --client.demo.enabled=false
```

The client web server listens on port `8080`. Disabling the optional startup demo lets the web application start without immediately invoking all four RPCs. To run the console demo as well, start it without the `client.demo.enabled=false` argument; the demo runner performs one call of each RPC on startup.

When the startup demo is enabled, it calls `getStockPrice("AAPL")`, streams updates for `GOOG`, submits five sample bulk orders (one with quantity zero, which the server rejects), and sends ten live-trading orders. Expected bulk summary: 5 total orders, 4 successful orders, total amount `$8394.26`. Simulated price values and timestamps vary between runs.

## Web pages and HTTP endpoints

Spring Boot serves `stock-trading-client/src/main/resources/static/index.html` and `stock-trading-client/src/main/resources/static/bulk-order.html` from the standard static-resource directory, making them available at the root of the client web server:

| Page | URL | Purpose |
|---|---|---|
| Live stock prices | `http://localhost:8080/` | Subscribe to price updates, view cards, and open a price-history chart |
| Bulk order form | `http://localhost:8080/bulk-order.html` | Create and submit one or more sample stock orders |

The live price page calls `GET /stocks/subscribe/{symbol}`. `StockStreamingController` bridges the server-streaming gRPC RPC to browser Server-Sent Events (SSE), returning protobuf messages as JSON. It normalizes symbols to uppercase and cancels the gRPC call when the browser disconnects or the SSE request times out.

The bulk order form sends a JSON array to `POST /api/stock/bulk-order`. `ClientStreamingController` validates the request at the API boundary, then delegates to `StockClientServiceWithUI`, which converts the DTOs to protobuf `StockOrder` messages, sends each message to `bulkStockOrder`, and returns the `OrderSummaryDTO`. Valid orders need a non-empty ID and symbol, order type `BUY` or `SELL`, positive quantity, and positive finite price. For example:

```json
[
  {
    "orderId": "WEB-001",
    "stockSymbol": "AAPL",
    "orderType": "BUY",
    "price": 227.50,
    "quantity": 10
  },
  {
    "orderId": "WEB-002",
    "stockSymbol": "GOOG",
    "orderType": "SELL",
    "price": 175.80,
    "quantity": 5
  }
]
```

The response contains `totalOrders`, `successCount`, and `totalAmount`. The endpoint `/api/stock/bulk-order` also redirects GET requests to the bulk-order page. Bulk orders are validated and summarized by the server, but are not persisted as trades.

## Test gRPC directly

### grpcurl

The server has reflection enabled, so `grpcurl` can discover the API without a local proto file. Use `-plaintext` because the local gRPC listener does not use TLS.

```bash
# Inspect the service
grpcurl -plaintext localhost:9090 list
grpcurl -plaintext localhost:9090 describe stocktrading.StockTradingService

# Unary: one request, one price response
grpcurl -plaintext -d '{"stock_symbol":"AAPL"}' localhost:9090 \
  stocktrading.StockTradingService/getStockPrice

# Server streaming: ten simulated updates
grpcurl -plaintext -d '{"stock_symbol":"AAPL"}' localhost:9090 \
  stocktrading.StockTradingService/subscribeStockPrice

# Client streaming: send multiple orders, then receive one summary
grpcurl -plaintext -d @ localhost:9090 \
  stocktrading.StockTradingService/bulkStockOrder <<'EOF'
{"order_id":"ORD-101","stock_symbol":"AAPL","quantity":10,"price":227.50,"order_type":"BUY"}
{"order_id":"ORD-102","stock_symbol":"GOOG","quantity":5,"price":175.80,"order_type":"SELL"}
EOF

# Bidirectional streaming: each order receives a TradeStatus
grpcurl -plaintext -d @ localhost:9090 \
  stocktrading.StockTradingService/liveTrading <<'EOF'
{"order_id":"LIVE-201","stock_symbol":"MSFT","quantity":4,"price":415.00,"order_type":"BUY"}
{"order_id":"LIVE-202","stock_symbol":"NVDA","quantity":0,"price":145.00,"order_type":"SELL"}
EOF

# Health check
grpcurl -plaintext localhost:9090 grpc.health.v1.Health/Check
```

For a longer bidirectional example, the repository's `order.txt` contains 100 sample orders:

```bash
grpcurl -plaintext -d @ localhost:9090 \
  stocktrading.StockTradingService/liveTrading < order.txt
```

If reflection is unavailable, supply the server's proto file explicitly:

```bash
grpcurl -d @ -plaintext \
  -import-path stock-trading-server/src/main/proto \
  -proto stock_trading.proto \
  localhost:9090 stocktrading.StockTradingService/liveTrading < order.txt
```

An unknown symbol passed to `getStockPrice` returns gRPC `NOT_FOUND`. `subscribeStockPrice` simulates prices around the stored price (or a default base if the symbol is unknown); it does not change the database.

To see the `NOT_FOUND` response, invoke `getStockPrice` with `{"stock_symbol":"NOPE"}`.

Example response shapes (prices and timestamps are illustrative; streaming values vary on each run):

```json
{
  "stock_symbol": "AAPL",
  "price": 227.5,
  "timestamp": "2026-11-05T17:22:54"
}
```

```json
{
  "total_orders": 3,
  "total_amount": 4729.5,
  "success_count": 3
}
```

The bidirectional example returns one status per order: valid orders receive `EXECUTED`; an order with quantity `0` receives `FAILED` with an invalid-quantity message.

### Postman

1. Select **New → gRPC** in Postman and connect to `localhost:9090`.
2. Under **Service definition**, choose **Server reflection** to discover the service. Alternatively, import `stock_trading.proto` from `stock-trading-server/src/main/proto/`.
3. Choose an RPC method and enter its protobuf JSON message in the message editor. Use **Invoke** for unary and server-streaming calls.
4. For client- and bidirectional-streaming calls, send each order as a separate message and end the request stream when done.
5. Save each request to a Postman collection to reuse it.

Use the sample messages from the grpcurl examples above. For example, `getStockPrice` expects `{"stock_symbol":"AAPL"}`; order-stream methods expect `StockOrder` messages with `order_id`, `stock_symbol`, `quantity`, `price`, and `order_type`.

> The gRPC endpoint is plaintext. Do not enable TLS or use an `https://` endpoint for `localhost:9090`.

### Example grpcurl outputs

These are sample outputs from a previous run. The unary price depends on the database contents, while streamed prices and timestamps are generated at runtime, so your exact values will differ.

**Unary — `getStockPrice`:**

```json
{
  "stock_symbol": "AAPL",
  "price": 227.5,
  "timestamp": "2026-11-05T17:22:54"
}
```

**Server streaming — `subscribeStockPrice`:** ten update messages are returned; first three shown:

```json
{"stock_symbol":"AAPL","price":230.5,"timestamp":"2026-11-06T07:10:10.367062055Z"}
{"stock_symbol":"AAPL","price":230.85,"timestamp":"2026-11-06T07:10:11.367770965Z"}
{"stock_symbol":"AAPL","price":227.15,"timestamp":"2026-11-06T07:10:12.368437210Z"}
...
```

**Client streaming — `bulkStockOrder`:**

```json
{
  "total_orders": 3,
  "total_amount": 4729.5,
  "success_count": 3
}
```

**Bidirectional streaming — `liveTrading`:** one `TradeStatus` is returned for each submitted order:

```json
{
  "order_id": "LIVE-201",
  "status": "EXECUTED",
  "message": "BUY 4 x MSFT @ $415.00 processed successfully",
  "timestamp": "2026-11-06T07:10:20.674860121Z"
}
{
  "order_id": "LIVE-202",
  "status": "FAILED",
  "message": "Invalid quantity: 0",
  "timestamp": "2026-11-06T07:10:20.677226653Z"
}
```

## Configuration

Server settings live in `stock-trading-server/src/main/resources/application.yml`:

| Setting | Default | Override |
|---|---|---|
| gRPC server port | `9090` | `GRPC_SERVER_PORT` |
| gRPC reflection | `true` | Configure `spring.grpc.server.reflection.enabled` |
| Database host / port | `localhost:3306` | `MYSQL_HOST`, `MYSQL_PORT` |
| Database name | `javatechie` | `MYSQL_DATABASE` |
| Database username / password | `root` / `Password` | `MYSQL_USERNAME`, `MYSQL_PASSWORD` |

The resulting default JDBC URL is `jdbc:mysql://localhost:3306/javatechie`.

Client gRPC channel settings are in `stock-trading-client/src/main/resources/application.yml`:

| Setting | Default | Override |
|---|---|---|
| gRPC server address | `static://localhost:9090` | `GRPC_SERVER_ADDRESS`, e.g. `static://grpc-host:9090` |
| Run console demo on startup | `true` | `--client.demo.enabled=false` disables it |

Compose settings in `compose.yaml`:

| Variable | Default |
|---|---|
| `MYSQL_ROOT_PASSWORD` | `Password` |
| `MYSQL_DATABASE` | `javatechie` |
| `MYSQL_PORT` | `3306` |

## Build and tests

```bash
cd stock-trading-server && mvn verify
cd stock-trading-client && mvn verify
```

The server requires a reachable MySQL instance for its application context; start Compose first. The client tests run without a gRPC server because the startup demo is disabled in the test configuration.

## Troubleshooting

| Symptom | What to check |
|---|---|
| Client reports gRPC `UNAVAILABLE` / connection refused | Start the server and confirm it is listening on port `9090`; check `GRPC_SERVER_ADDRESS`. |
| Server reports a MySQL connection error | Start `docker compose up -d`, wait for `docker compose ps` to show the database as running, and check the `MYSQL_*` settings. |
| `GET /` or `/bulk-order.html` returns 404 | Use the client on port `8080` and ensure the HTML files are under `src/main/resources/static/`; restart after rebuilding. |
| gRPC logs `HTTP/2 client preface string missing or corrupt` | A TLS/HTTPS client connected to the plaintext gRPC port. Disable TLS for the local Postman/gRPC connection. |
| `getStockPrice` returns `NOT_FOUND` | Use one of the seeded symbols, such as `AAPL` or `GOOG`, or add a row to `stocks`. |
| Port already in use | Change `GRPC_SERVER_PORT` and set the matching `GRPC_SERVER_ADDRESS` (for example, `GRPC_SERVER_PORT=9091` and `GRPC_SERVER_ADDRESS=static://localhost:9091`), or configure Spring's `server.port` for the HTTP client. |

## Further reading

- [Spring gRPC reference](https://docs.spring.io/spring-grpc/reference/)
- [gRPC concepts](https://grpc.io/docs/what-is-grpc/core-concepts/)
- [Protocol Buffers language guide](https://protobuf.dev/programming-guides/proto3/)
- [grpcurl usage and examples](https://github.com/fullstorydev/grpcurl)
- [Postman gRPC requests](https://learning.postman.com/docs/sending-requests/grpc/grpc-request/)
- [Spring Boot static content](https://docs.spring.io/spring-boot/reference/web/servlet.html#web.servlet.spring-mvc.static-content)
- [MySQL Docker image documentation](https://hub.docker.com/_/mysql)

## Production considerations

This repository is a local demo:

- The server uses Hibernate schema auto-update; use Flyway or Liquibase migrations for production.
- Local gRPC transport is plaintext; configure TLS with the Spring gRPC client/server SSL settings for remote or production traffic.
- Prices from `subscribeStockPrice` are simulated around the last stored base price.
- Bulk orders are validated and summarized; `liveTrading` returns statuses, but neither RPC persists trades.
- No authentication or authorization is configured. For production, consider Spring gRPC interceptors (for example, a global client interceptor or a basic-authentication interceptor) for authentication and authorization.
