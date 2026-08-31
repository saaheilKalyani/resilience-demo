# Resilience Demo

A small Spring Boot microservices project demonstrating, end to end and with a real UI:
service-to-service communication, logging, Retry, Timeout, Fallback, Circuit Breaker, and
observability (Actuator + Micrometer + Prometheus + Grafana).

A full technical write-up of every concept and every line of implementation lives in
[`RESILIENCE_DEMO_COMPLETE_GUIDE.pdf`](./RESILIENCE_DEMO_COMPLETE_GUIDE.pdf). This README is the
practical "how do I run it" guide.

## 1. Architecture

```
Browser UI (ui/)
     |
     |  POST /orders
     v
Order Service :8082  --- Retry -> Circuit Breaker -> Timeout -> RestClient --->  Payment Service :8081
     |                                                                                  |
     |  GET /circuit-breaker                                                            |
     v                                                                                  v
 Actuator + Micrometer  <---------------------------------------------------- Actuator + Micrometer
     |                                                                                  |
     +--------------------------------- scraped by ----------------------------------- +
                                            |
                                            v
                                   Prometheus :9092 (Docker)
                                            |
                                            v
                                    Grafana :3030 (Docker)
```

Logging in both services is plain SLF4J → Logback → console (no file, no external log
aggregator). Distributed tracing is **not** implemented.

## 2. Prerequisites

- Java 21 (both services target `java.version=21`)
- No local Maven install needed — each service has its own `mvnw`/`mvnw.cmd` wrapper
- Docker Desktop, only needed for the Prometheus/Grafana part

## 3. Project structure

```
resilience-demo/
├── order-service/           Spring Boot app, port 8082 - the caller
│   ├── src/main/java/com/resilience/demo/order/
│   │   ├── OrderServiceApplication.java
│   │   ├── controller/       OrderController, OrderRequest, OrderResponse, CircuitBreakerStatus
│   │   └── service/          OrderService (Retry/CircuitBreaker/Timeout live here), PaymentResponse
│   └── src/main/resources/application.properties
├── payment-service/         Spring Boot app, port 8081 - the callee, simulates 3 behaviors
│   ├── src/main/java/com/resilience/demo/payment/
│   │   ├── PaymentServiceApplication.java
│   │   └── controller/       PaymentController, PaymentResponse
│   └── src/main/resources/application.properties
├── ui/                      Static HTML/CSS/JS, no build step - open index.html directly
│   ├── index.html
│   ├── script.js
│   └── style.css
├── prometheus/
│   └── prometheus.yml       Scrape config for both services
├── grafana/provisioning/
│   ├── datasources/datasource.yml   Auto-provisions the Prometheus datasource
│   └── dashboards/dashboard.yml, resilience-demo.json   Auto-provisions the dashboard
├── docker-compose.yml       Prometheus + Grafana only (Spring apps are NOT containerized)
└── README.md
```

## 4. Ports

| Service | Port | Notes |
|---|---|---|
| Order Service | 8082 | run via `mvnw.cmd spring-boot:run` |
| Payment Service | 8081 | run via `mvnw.cmd spring-boot:run` |
| Prometheus | **9092** | Docker container, host port only — container listens on 9090 internally |
| Grafana | **3030** | Docker container, host port only — container listens on 3000 internally |

> **Why not the standard 9090/3000?** This machine already runs an unrelated project
> ("Powergrid") whose own Docker containers occupy `9090`, `9091` and `3000`. This project uses
> `9092`/`3030` instead so both projects can run side by side without conflict. Port `8080` is
> also avoided for the same reason. **Never** run `docker compose down` (without `-p`) or any
> command against those other containers — see [Section 12](#12-a-note-on-the-powergrid-project).

## 5. Start Payment Service

```cmd
cd payment-service
mvnw.cmd spring-boot:run
```
Wait for `Tomcat started on port 8081`.

## 6. Start Order Service

```cmd
cd order-service
mvnw.cmd spring-boot:run
```
Wait for `Tomcat started on port 8082`.

## 7. Start Prometheus + Grafana

Requires Docker Desktop running. From the repository root:

```cmd
docker compose -p resilience-demo up -d
```

The explicit `-p resilience-demo` names this Compose project so it can never be confused with,
or accidentally targeted instead of, any other Compose project on the machine.

## 8. Verify everything is up

```cmd
curl http://localhost:8081/actuator/health
curl http://localhost:8082/actuator/health
curl http://localhost:8082/actuator/prometheus
curl http://localhost:8081/actuator/prometheus
```
All four should return HTTP 200 (`/actuator/health` returns `{"status":"UP"}`; `/actuator/prometheus`
returns a large plain-text metrics dump).

Then open in a browser:
- **`http://localhost:9092/targets`** — Prometheus's target list. Both `order-service` and
  `payment-service` should show state **UP**.
- **`http://localhost:3030`** — Grafana. Log in with **`admin` / `admin`** (see
  `GF_SECURITY_ADMIN_USER` / `GF_SECURITY_ADMIN_PASSWORD` in `docker-compose.yml`). Open
  **Dashboards → Resilience Demo** to see the 6 provisioned panels.

## 9. Using the UI

Open `ui/index.html` directly in a browser (no server needed — plain static files, Order Service
has `@CrossOrigin(origins = "*")`).

1. Enter an **Order ID** (defaults to `1001`).
2. Pick a **Payment Behavior**: `Normal`, `Failure`, or `Slow`.
3. Click **Create Order**. The **Resilience Result** card shows the outcome: payment status,
   response time, HTTP status, which resilience `mechanism` engaged (`NONE`/`RETRY`/`TIMEOUT`/
   `CIRCUIT_BREAKER`), how many attempts were made, the circuit breaker's state at the end of the
   call, and whether Payment Service was actually called.
4. The **Circuit Breaker** card shows the live state (polled every 3s from `GET /circuit-breaker`).

## 10. Demonstration scenarios

| # | UI action | What happens |
|---|---|---|
| 1 — Normal | Select Normal, Create Order | Payment Service called once, `SUCCESS`, mechanism `NONE` |
| 2 — Failure + Retry | Select Failure, Create Order | 3 attempts (1 + 2 retries, 500ms apart), ends in `FALLBACK`, mechanism `RETRY` |
| 3 — Slow + Timeout | Select Slow, Create Order | Cut off after 2s (Payment Service is still sleeping toward 5s), `FALLBACK`, mechanism `TIMEOUT`, only 1 attempt |
| 4 — Circuit OPEN | Click Failure twice in a row | 2nd click trips the breaker mid-request once 5 calls/≥50% failures accumulate; badge turns **OPEN**; a further click gets rejected in a few ms with `paymentServiceCalled: NO`, mechanism `CIRCUIT_BREAKER` |
| 5 — HALF_OPEN → CLOSED | Wait ~5s after OPEN, then click Normal twice | Badge auto-flips to **HALF_OPEN**; 2 successful test calls close it back to **CLOSED** |
| 6 — HALF_OPEN → OPEN | Trip OPEN again, wait for HALF_OPEN, click Failure | The failing test call reopens the circuit |
| 7 — Observability | Any of the above, then check Prometheus/Grafana | Watch `orders_processed_total`, `orders_fallback_total`, `resilience4j_circuitbreaker_state`, etc. change in Grafana within ~5s |

Watch the cmd windows throughout — every step logs a clear line (`Order request received`,
`Payment attempt`, `Retrying payment`, `Payment call timed out`, `Circuit Breaker state: ...`,
`Fallback executed`, ...).

## 11. Stopping the project safely

```cmd
:: In each of the two cmd windows running mvnw:
Ctrl + C

:: From the repository root, stops ONLY this project's containers:
docker compose -p resilience-demo down
```

`down` **without** `-p resilience-demo` is never appropriate here — always include the flag.

## 12. A note on the Powergrid project

This machine also runs an unrelated Docker Compose project named **Powergrid**
(`powergrid-api-1`, `powergrid-bff-1`, `powergrid-postgres-1`, `powergrid-prometheus-1`,
`powergrid-grafana-1`, `powergrid-loki-1`, `powergrid-promtail-1`, `powergrid-redis-1`), using
ports `8080`, `9090`, `9091`, `3000`, `3001`, `3100`, `5432`, `6379`. **This project never
starts, stops, restarts, or reconfigures any of those containers, and never uses ports they
already hold.** If you see a "port already in use" error and it turns out to be one of the
Powergrid ports, change this project's port — never stop or reconfigure Powergrid.

## 13. Troubleshooting

| Problem | Cause | Fix |
|---|---|---|
| `Web server failed to start. Port 8081/8082 was already in use` | Another instance of the service (yours or a leftover) is already running | Find it: `netstat -ano \| findstr :8081` (or `:8082`), then `taskkill /PID <pid> /F` |
| `docker compose up` fails to bind 9092/3030 | Something else on the machine grabbed those ports | Check `netstat -ano \| findstr :9092`; if it's not this project's own container, edit the host-side port in `docker-compose.yml` |
| Prometheus target shows `DOWN` | Order/Payment service isn't running, or was started after Prometheus | Start/restart the Spring Boot service, then check `http://localhost:9092/targets` again (Prometheus retries every 5s) |
| Grafana panel shows "No data" | No traffic yet, or Prometheus hasn't scraped yet | Click Create Order a few times in the UI, wait ~5-10s |
| UI shows "Could not reach Order Service" | order-service isn't running or is on the wrong port | Confirm `http://localhost:8082/actuator/health` returns `UP` |
| Circuit Breaker never opens | Fewer than 5 calls made, or not enough failures in the window | Select Failure and click Create Order at least twice in a row (each click makes up to 3 calls) |
| `/actuator/prometheus` returns 404 | `management.endpoints.web.exposure.include` doesn't include `prometheus` | Already configured in both `application.properties` — restart the service if you changed it |
| Maven build fails | Usually a network issue reaching the Spring snapshot repo | Re-run `mvnw.cmd spring-boot:run`; check internet connectivity |

## 14. Implemented vs. not implemented

**Implemented**: logging (SLF4J/Logback), Retry, Timeout, Fallback, Circuit Breaker
(Resilience4j), Spring Boot Actuator (`health`, `metrics`, `prometheus`), Micrometer (custom
Counters + Resilience4j metrics binding), Prometheus, Grafana (provisioned datasource +
dashboard).

**Not implemented** (intentionally): distributed tracing, OpenTelemetry, Jaeger/Tempo/Zipkin,
a database, Spring Security, message brokers (Kafka/RabbitMQ), Redis, an API Gateway, service
discovery, Kubernetes, containerizing the Spring Boot apps themselves.
