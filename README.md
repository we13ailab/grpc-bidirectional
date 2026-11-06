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
