# Deployment Guide — URL Shortener & Click Analytics

This guide explains how to run the URL Shortener & Click Analytics API in every
environment: local development, production via Docker Compose, and production
via a standalone JAR.

## Table of contents

- [Architecture recap](#architecture-recap)
- [Prerequisites](#prerequisites)
- [Configuration reference](#configuration-reference)
- [Local deployment](#local-deployment)
- [Production deployment](#production-deployment)
- [TLS and reverse proxy](#tls-and-reverse-proxy)
- [Health checks and smoke tests](#health-checks-and-smoke-tests)
- [Database and Redis provisioning](#database-and-redis-provisioning)
- [Backup and recovery](#backup-and-recovery)
- [Scaling](#scaling)
- [Troubleshooting](#troubleshooting)

## Architecture recap

| Concern | Implementation |
|---|---|
| Framework | Java 17, Spring Boot 3.5 |
| Relational store | PostgreSQL 16 (Flyway migrations, Hibernate `ddl-auto=validate`) |
| Cache / rate-limit store | Redis 7 (cache-aside + Lua scripts) |
| API | Spring REST (`/api/**` requires `X-API-Key`) |
| Redirect | Public `302 Found` with `Location` header |
| Analytics | Click events grouped by UTC day, referrer host, device |
| Cleanup | Scheduled job with PostgreSQL advisory lock + batched deletes |
| Observability | Spring Actuator (health), Micrometer + Prometheus (metrics) |

## Prerequisites

- Docker Engine with Docker Compose (for the bundled PostgreSQL, Redis and app
  container images)
- Java 17 + Maven 3.9 (only for running the app outside a container)
- A PostgreSQL 16+ database and a reachable Redis 7 instance (or use the Compose
  services)

## Configuration reference

All runtime settings are environment variables (durations use ISO-8601, e.g.
`PT1H`).

| Variable | Default | Purpose |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | none (`prod` in the Docker image) | Spring profile; `prod` enables fail-fast secret validation |
| `SERVER_PORT` | `8080` | HTTP port |
| `APP_BASE_URL` | `http://localhost:8080` | Base URL used in created short URLs |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/shortener` | JDBC URL |
| `DATABASE_USERNAME` / `DATABASE_PASSWORD` | `shortener` / `shortener` | PostgreSQL credentials |
| `DB_POOL_SIZE` / `DB_MIN_IDLE` | `10` / `2` | Hikari pool bounds |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | `localhost` / `6379` / empty | Redis connection |
| `CACHE_TTL` | `PT1H` | Short-link cache lifetime |
| `API_KEY` | `local-development-key` | Bootstrap admin API key |
| `API_KEY_PEPPER` | `local-development-pepper` | HMAC pepper for stored key hashes |
| `RATE_LIMIT_REQUESTS` / `RATE_LIMIT_WINDOW` | `100` / `PT1M` | Management API limit per API key |
| `REDIRECT_RATE_LIMIT_REQUESTS` / `REDIRECT_RATE_LIMIT_WINDOW` | `600` / `PT1M` | Redirect limit per client IP |
| `ANALYTICS_RETENTION` | `P365D` | Click event retention |
| `EXPIRED_URL_RETENTION` | `P30D` | Retention of soft-deleted URLs before hard delete |
| `CLEANUP_BATCH_SIZE` | `500` | Batched cleanup size |
| `CLEANUP_CRON` | `0 0 3 * * *` | Cleanup schedule (UTC) |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000,http://localhost:5173` | Comma-separated allowed origins; `*` allows all |

### Production secret requirements

The `prod` profile fails fast at startup when `API_KEY` or `API_KEY_PEPPER` are
missing or invalid: they must be at least 32 characters long and must not be the
built-in development defaults (`local-development-key` /
`local-development-pepper`).

Changing `API_KEY_PEPPER` invalidates every stored API key hash. Rotate it only
together with a planned key migration.

## Local deployment

### Option A — full stack with Docker Compose

```bash
cd url-shortener
cp .env.example .env
# Edit .env: set strong API_KEY, API_KEY_PEPPER and POSTGRES_PASSWORD.
docker compose up --build -d
```

PostgreSQL and Redis are bound to `127.0.0.1` only; the app is published on
`127.0.0.1:${APP_HOST_PORT:-8080}:8080`.

### Option B — infrastructure only, run the app locally

```bash
docker compose up -d postgres redis
mvn spring-boot:run
```

Development defaults apply: `API_KEY=local-development-key`,
`APP_BASE_URL=http://localhost:8080`.

## Production deployment

### Docker Compose (recommended)

```bash
cd url-shortener
cp .env.example .env
# Set: SPRING_PROFILES_ACTIVE=prod, APP_BASE_URL=https://<your-domain>,
#     API_KEY=<>=32+ random chars, API_KEY_PEPPER=<>=32+ random chars,
#     POSTGRES_PASSWORD=<strong password>, CORS_ALLOWED_ORIGINS=<allowed origins>
docker compose up --build -d
docker compose ps
docker compose logs -f app
```

The image is a multi-stage build (`maven:3.9-eclipse-temurin-17` →
`eclipse-temurin:17-jre-alpine`) running with the `prod` profile, non-root user
`app`, and JVM flags `-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError`.

### Standalone JAR

```bash
mvn -DskipTests package
java -jar target/url-shortener-1.0.0.jar \
  --server.port=8080 \
  --spring.datasource.url=jdbc:postgresql://<host>:5432/shortener \
  --spring.datasource.username=<user> \
  --spring.datasource.password=<password> \
  --spring.data.redis.host=<redis-host> \
  --spring.data.redis.port=6379
```

Set the `prod` profile or rely on the image default, and always provide
`APP_BASE_URL`, `API_KEY` and `API_KEY_PEPPER`.

## TLS and reverse proxy

Terminate TLS at a reverse proxy or load balancer in production. Configure the
forward-headers strategy so generated URLs and rate-limit client IPs are
correct:

```yaml
server:
  forward-headers-strategy: native
```

Example nginx site:

```nginx
server {
    listen 443 ssl;
    server_name links.example.com;

    ssl_certificate     /etc/letsencrypt/live/links.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/links.example.com/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

## Health checks and smoke tests

Production exposes only the `health` actuator endpoint (Prometheus scraping is
disabled in the `prod` profile).

| Endpoint | Purpose |
|---|---|
| `GET /actuator/health` | Overall health; `DOWN` when the database or Redis is unavailable |
| `GET /actuator/health/liveness` | Process liveness probe (Docker/K8s) |
| `GET /actuator/health/readiness` | Readiness probe (`db`, `ping`) |

Redis is intentionally excluded from readiness so a Redis outage degrades caching
and distributed rate limiting only.

Smoke test sequence:

```bash
# 1. Health
curl http://localhost:8080/actuator/health

# 2. Create a short URL
curl -X POST http://localhost:8080/api/urls \
  -H 'Content-Type: application/json' \
  -H "X-API-Key: $API_KEY" \
  -d '{"url":"https://example.com","customCode":"smoke1"}'

# 3. Public redirect must answer 302
curl -i http://localhost:8080/smoke1

# 4. Analytics
curl "http://localhost:8080/api/urls/smoke1/analytics?days=7" \
  -H "X-API-Key: $API_KEY"
```

## Database and Redis provisioning

- Flyway applies `V1__init.sql`, `V2__add_soft_delete_and_referrer.sql` and
  `V3__add_click_referrer_composite_index.sql` automatically at startup.
- Hibernate runs with `ddl-auto=validate` and fails fast on schema drift.
- PostgreSQL must be reachable from the app container/host network; the JDBC URL
  is supplied through `DATABASE_URL`.
- Redis must be reachable through `REDIS_HOST`/`REDIS_PORT`; cache writes are
  best-effort and the app falls back to the database when Redis is unavailable.

## Backup and recovery

- Back up the PostgreSQL database regularly, e.g.
  `pg_dump -U shortener shortener > backup_$(date +%F).sql`.
- The Compose volumes `postgres_data` and `redis_data` hold the persistent
  state; back up or restore them according to your volume strategy.
- Analytics data older than `ANALYTICS_RETENTION` is purged by the cleanup job;
  restore from backup to recover deleted click events.

## Scaling

- Run multiple app instances behind a load balancer. Cleanup is protected by a
  PostgreSQL transaction advisory lock, so only one instance performs a cleanup
  run at a time.
- Cache lives in Redis, so cache entries are shared across instances; eviction
  after commit is coordinated through the shared store.
- Redirect rate limiting uses Redis when available; the in-process fallback is
  per instance, so for strict global limits put a gateway in front or keep Redis
  as the rate-limit store.

## Troubleshooting

| Symptom | Likely cause / fix |
|---|---|
| App exits immediately in `prod` profile | Missing or weak `API_KEY` / `API_KEY_PEPPER` (need ≥ 32 chars, not dev defaults) |
| `GET /actuator/health` returns `DOWN` | Database or Redis unreachable; check datasource and Redis env values |
| Redirects fail under heavy load | Check Redis health; fallback limits apply per instance |
| Cleanup never deactivates entries | `CLEANUP_CRON` schedule, `CLEANUP_BATCH_SIZE`, advisory lock contention |
| 401 on API calls | `X-API-Key` header missing/incorrect; bootstrap key changed via `API_KEY` |
| 429 on redirects | `REDIRECT_RATE_LIMIT_REQUESTS` / window exceeded; raise or use Redis-backed limiting |

## Continuous integration

`.github/workflows/ci.yml` runs `mvn -B verify` on `ubuntu-latest` with Temurin
Java 17 and Maven cache. The integration tests use Testcontainers and require
Docker on the runner.
