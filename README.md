# gRPC Bidirectional Stock Trading Demo

A small stock-trading system that demonstrates **all four gRPC communication patterns** between a Spring Boot client and server:

| # | Pattern | RPC | Direction |
|---|---------|-----|-----------|
| 1 | Unary | `getStockPrice` | one request → one response |
| 2 | Server streaming | `subscribeStockPrice` | one request → stream of price updates |
| 3 | Client streaming | `bulkStockOrder` | stream of orders → one summary |
| 4 | Bidirectional streaming | `liveTrading` | stream of orders ⇄ stream of trade statuses |

```
┌─────────────────────┐         gRPC (protobuf, HTTP/2)          ┌──────────────────────┐
│ stock-trading-client │ ─────────────────────────────────────▶  │ stock-trading-server  │
│  (Spring Boot CLI)   │           localhost:9090                │  (Spring Boot + JPA)  │
└─────────────────────┘                                         └──────────┬───────────┘
                                                                            │
                                                                       ┌────▼────┐
                                                                       │ MySQL 8 │ (docker)
                                                                       └─────────┘
```

## Tech stack

| Component | Technology |
|------------|------------|
| Language | Java 21 |
| Framework | Spring Boot 3.5.16 |
| gRPC integration | Spring gRPC 0.12.0 (official, client **and** server) |
| RPC runtime | gRPC Java 1.76.0, Protobuf 3.25.8 |
| Database | MySQL 8.0 (Spring Data JPA / Hibernate) |
| Build | Maven |

## Project structure

```
grpc-bidirectional/
├── compose.yaml                        # local MySQL for the server
├── order.txt                           # 100 sample orders for grpcurl live-trading
├── stock-trading-server/
│   └── src/main/
│       ├── proto/stock_trading.proto   # service contract (single source of truth)
│       ├── java/com/javatechie/
│       │   ├── StockTradingServerApplication.java
│       │   ├── bootstrap/StockDataSeeder.java        # seeds the stocks table
│       │   ├── entity/Stock.java
│       │   ├── repository/StockRepository.java
│       │   └── service/StockTradingServiceImpl.java  # the four RPC implementations
│       └── resources/application.yml   # all server configuration
└── stock-trading-client/
    └── src/main/
        ├── proto/stock_trading.proto   # identical copy (must stay in sync)
        ├── java/com/javatechie/
        │   ├── StockTradingClientApplication.java
        │   ├── config/GrpcClientConfig.java          # channel + stub beans
        │   ├── runner/StockTradingDemoRunner.java    # runs all four demos
        │   └── service/StockClientService.java
        └── resources/application.yml   # all client configuration
```

> Both modules carry their own copy of `stock_trading.proto`. They are identical — if you change one, change both (protobuf code is generated per module at build time).

## Prerequisites

- Java 21+
- Maven 3.9+
- Docker (for MySQL)
- Optional: [grpcurl](https://github.com/fullstorydev/grpcurl) to poke the API without the client

## Quick start

### 1. Start MySQL

```bash
docker compose up -d
```

Creates a healthy MySQL 8 container on `localhost:3306` with an empty `javatechie` database (data persists in the `mysql_data` volume).

### 2. Start the server

```bash
cd stock-trading-server
mvn spring-boot:run
# or: mvn package -DskipTests && java -jar target/stock-trading-server-0.0.1-SNAPSHOT.jar
```

The server creates the `stocks` table (via `ddl-auto: update`), seeds ten well-known symbols (AAPL, MSFT, GOOG, AMZN, META, NVDA, AMD, INTC, NFLX, TSLA) if missing, and serves gRPC on port **9090**. Reflection and health services are enabled.

### 3. Run the client

```bash
cd stock-trading-client
mvn spring-boot:run
# or: mvn package -DskipTests && java -jar target/stock-trading-client-0.0.1-SNAPSHOT.jar
```

The client runs one round of each pattern and exits. Expected output (abridged):

```
=== 1/4 UNARY: getStockPrice ===
[UNARY] AAPL price = $227.5 (as of 2026-11-05T17:22:54)
=== 2/4 SERVER STREAMING: subscribeStockPrice ===
[SERVER-STREAM] GOOG price = $180.04 ...
[SERVER-STREAM] GOOG price = $176.44 ...        (10 updates, ~1/sec)
=== 3/4 CLIENT STREAMING: bulkStockOrder ===
[CLIENT-STREAM] summary: 5 order(s) received, 4 executed, total $8394.26
=== 4/4 BIDIRECTIONAL STREAMING: liveTrading ===
[BIDI] ORDER-01 -> EXECUTED (SELL 10 x MSFT @ $151.00 processed successfully)
...
All four gRPC patterns completed successfully
```

Note the bulk-order summary: the last sample order is intentionally invalid (quantity 0), so the server rejects it — 4 of 5 executed.

## Testing with grpcurl

Reflection is enabled, so no `.proto` files are needed.

```bash
# discover the API
grpcurl -plaintext localhost:9090 list
grpcurl -plaintext localhost:9090 describe stocktrading.StockTradingService

# unary
grpcurl -plaintext -d '{"stock_symbol": "AAPL"}' \
  localhost:9090 stocktrading.StockTradingService/getStockPrice

# server streaming (10 updates, ~1/sec)
grpcurl -plaintext -d '{"stock_symbol": "GOOG"}' \
  localhost:9090 stocktrading.StockTradingService/subscribeStockPrice

# bidirectional streaming: feed 100 orders from order.txt, watch statuses stream back
grpcurl -d @ -plaintext localhost:9090 \
  stocktrading.StockTradingService/liveTrading < order.txt

# health check
grpcurl -plaintext localhost:9090 grpc.health.v1.Health/Check
```

Without reflection (e.g. against a hardened server), pass the proto explicitly:

```bash
grpcurl -d @ -plaintext \
  -import-path stock-trading-server/src/main/proto -proto stock_trading.proto \
  localhost:9090 stocktrading.StockTradingService/liveTrading < order.txt
```

Unknown symbols return a proper gRPC error:

```
$ grpcurl -plaintext -d '{"stock_symbol": "NOPE"}' localhost:9090 .../getStockPrice
Code: NotFound
Message: Stock not found: NOPE
```

## Configuration

### Server (`stock-trading-server/src/main/resources/application.yml`)

| Property | Default | Environment override |
|----------|---------|----------------------|
| `spring.grpc.server.port` | `9090` | `GRPC_SERVER_PORT` |
| `spring.grpc.server.reflection.enabled` | `true` | — |
| `spring.datasource.url` | `jdbc:mysql://localhost:3306/javatechie` | `MYSQL_HOST`, `MYSQL_PORT`, `MYSQL_DATABASE` |
| `spring.datasource.username` | `root` | `MYSQL_USERNAME` |
| `spring.datasource.password` | `Password` | `MYSQL_PASSWORD` |

### Client (`stock-trading-client/src/main/resources/application.yml`)

| Property | Default | Environment override |
|----------|---------|----------------------|
| `spring.grpc.client.channels.stockService.address` | `static://localhost:9090` | `GRPC_SERVER_ADDRESS` |
| `client.demo.enabled` | `true` | — (set `false` to boot without running the demo) |

### Docker compose (`compose.yaml`)

| Variable | Default |
|----------|---------|
| `MYSQL_ROOT_PASSWORD` | `Password` |
| `MYSQL_DATABASE` | `javatechie` |
| `MYSQL_PORT` | `3306` |

## Building and testing

```bash
cd stock-trading-server && mvn verify     # context test needs MySQL: docker compose up -d
cd stock-trading-client && mvn verify     # runs standalone (demo disabled in test)
```

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| Client: `UNAVAILABLE: Connection refused ...:9090` | Server not up yet — start it first |
| Server: `Communications link failure` / datasource error | `docker compose up -d` and wait for `healthy` (`docker compose ps`) |
| `Port 9090 already in use` | Stop the other process, or run the server with `GRPC_SERVER_PORT=9091` and the client with `GRPC_SERVER_ADDRESS=static://localhost:9091` |
| Fresh database with no rows | The seeder runs on server startup; just restart the server |

## Production notes

This is a demo, so a few shortcuts were taken deliberately:

- `spring.jpa.hibernate.ddl-auto: update` — use Flyway/Liquibase migrations in real systems.
- Plaintext transport — configure TLS (`spring.grpc.client.channels.<name>.ssl.*`, `spring.grpc.server.ssl.*`) for anything beyond localhost.
- Prices in `subscribeStockPrice` are simulated around the stored base price; `liveTrading` validates but does not persist trades.
- No auth — Spring gRPC supports interceptor-based security (e.g. `@GlobalClientInterceptor` / `BasicAuthenticationInterceptor`).

## Testing in Postman

## Option 1: Create the gRPC requests in Postman (recommended)

1. Click **New → gRPC**.
2. Enter the server URL `localhost:9090`.
3. Under **Service definition**, choose **Server reflection**. Your collection notes say reflection is enabled. Otherwise, choose **Import a .proto file** and upload `stock_trading.proto`.
4. Pick the method from the dropdown. Postman detects the type (unary, server streaming, etc.) from the proto.
5. Paste the message into the **Message** tab, then click **Invoke**. For streaming methods, use **Send** for each message and **End streaming** when done.
6. Save each request into a collection. You can share it through a Postman workspace or fork instead of exporting JSON.

Messages to use:

| Method | Messages |
|---|---|
| `getStockPrice` | `{"stock_symbol": "AAPL"}` |
| `subscribeStockPrice` | `{"stock_symbol": "AAPL"}` |
| `bulkStockOrder` | `{"order_id":"ORD-101","stock_symbol":"AAPL","quantity":10,"price":227.50,"order_type":"BUY"}`, then `{"order_id":"ORD-102","stock_symbol":"GOOGL","quantity":5,"price":172.50,"order_type":"SELL"}`, then `{"order_id":"ORD-103","stock_symbol":"AMZN","quantity":8,"price":199.00,"order_type":"BUY"}` |
| `liveTrading` | `{"order_id":"LIVE-201","stock_symbol":"MSFT","quantity":4,"price":415.00,"order_type":"BUY"}`, then `{"order_id":"LIVE-202","stock_symbol":"NVDA","quantity":0,"price":145.00,"order_type":"SELL"}` |

## Option 2: Script it with grpcurl

If you want something you can commit and re-run, `grpcurl` works with your reflection-enabled server:

```bash
# Unary
grpcurl -plaintext -d '{"stock_symbol":"AAPL"}' localhost:9090 \
  stocktrading.StockTradingService/getStockPrice

# Server streaming
grpcurl -plaintext -d '{"stock_symbol":"AAPL"}' localhost:9090 \
  stocktrading.StockTradingService/subscribeStockPrice

# Client streaming (multiple JSON objects on stdin)
grpcurl -plaintext -d @ localhost:9090 \
  stocktrading.StockTradingService/bulkStockOrder <<'EOF'
{"order_id":"ORD-101","stock_symbol":"AAPL","quantity":10,"price":227.50,"order_type":"BUY"}
{"order_id":"ORD-102","stock_symbol":"GOOGL","quantity":5,"price":172.50,"order_type":"SELL"}
{"order_id":"ORD-103","stock_symbol":"AMZN","quantity":8,"price":199.00,"order_type":"BUY"}
EOF

# Bidirectional streaming
grpcurl -plaintext -d @ localhost:9090 \
  stocktrading.StockTradingService/liveTrading <<'EOF'
{"order_id":"LIVE-201","stock_symbol":"MSFT","quantity":4,"price":415.00,"order_type":"BUY"}
{"order_id":"LIVE-202","stock_symbol":"NVDA","quantity":0,"price":145.00,"order_type":"SELL"}
EOF
```

Your original notes still apply: `getStockPrice` needs the symbols seeded in the `stocks` table, and the invalid-quantity order should come back as `FAILED`.

## Above grpCurl Commands and their output

```
➜  grpc-bidirectional # Unary
➜  grpc-bidirectional grpcurl -plaintext -d '{"stock_symbol":"AAPL"}' localhost:9090 \
>   stocktrading.StockTradingService/getStockPrice
{
  "stock_symbol": "AAPL",
  "price": 227.5,
  "timestamp": "2026-11-05T17:22:54"
}


➜  grpc-bidirectional # Server streaming
➜  grpc-bidirectional grpcurl -plaintext -d '{"stock_symbol":"AAPL"}' localhost:9090 \
>   stocktrading.StockTradingService/subscribeStockPrice
{
  "stock_symbol": "AAPL",
  "price": 230.5,
  "timestamp": "2026-11-06T07:10:10.367062055Z"
}
{
  "stock_symbol": "AAPL",
  "price": 230.85,
  "timestamp": "2026-11-06T07:10:11.367770965Z"
}
{
  "stock_symbol": "AAPL",
  "price": 227.15,
  "timestamp": "2026-11-06T07:10:12.368437210Z"
}
{
  "stock_symbol": "AAPL",
  "price": 225.34,
  "timestamp": "2026-11-06T07:10:13.369066718Z"
}
{
  "stock_symbol": "AAPL",
  "price": 226.11,
  "timestamp": "2026-11-06T07:10:14.369725762Z"
}
{
  "stock_symbol": "AAPL",
  "price": 226.57,
  "timestamp": "2026-11-06T07:10:15.370418684Z"
}
{
  "stock_symbol": "AAPL",
  "price": 226.88,
  "timestamp": "2026-11-06T07:10:16.371088092Z"
}
{
  "stock_symbol": "AAPL",
  "price": 223.27,
  "timestamp": "2026-11-06T07:10:17.371716808Z"
}
{
  "stock_symbol": "AAPL",
  "price": 223.69,
  "timestamp": "2026-11-06T07:10:18.372446883Z"
}
{
  "stock_symbol": "AAPL",
  "price": 226.1,
  "timestamp": "2026-11-06T07:10:19.373069699Z"
}



➜  grpc-bidirectional # Client streaming (multiple JSON objects on stdin)
➜  grpc-bidirectional grpcurl -plaintext -d @ localhost:9090 \
>   stocktrading.StockTradingService/bulkStockOrder <<'EOF'
heredoc> {"order_id":"ORD-101","stock_symbol":"AAPL","quantity":10,"price":227.50,"order_type":"BUY"}
heredoc> {"order_id":"ORD-102","stock_symbol":"GOOGL","quantity":5,"price":172.50,"order_type":"SELL"}
heredoc> {"order_id":"ORD-103","stock_symbol":"AMZN","quantity":8,"price":199.00,"order_type":"BUY"}
heredoc> EOF
{
  "total_orders": 3,
  "total_amount": 4729.5,
  "success_count": 3
}



➜  grpc-bidirectional # Bidirectional streaming
➜  grpc-bidirectional grpcurl -plaintext -d @ localhost:9090 \
>   stocktrading.StockTradingService/liveTrading <<'EOF'
heredoc> {"order_id":"LIVE-201","stock_symbol":"MSFT","quantity":4,"price":415.00,"order_type":"BUY"}
heredoc> {"order_id":"LIVE-202","stock_symbol":"NVDA","quantity":0,"price":145.00,"order_type":"SELL"}
heredoc> EOF
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