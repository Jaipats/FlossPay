# FlossPay

**Free/Libre Open-Source Payments Infrastructure**

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Java Version](https://img.shields.io/badge/Java-21%20LTS-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-15+-blue.svg)](https://www.postgresql.org/)
[![Redis](https://img.shields.io/badge/Redis-7+-red.svg)](https://redis.io/)

---

## Table of Contents

1. [Overview](#overview)
2. [System Architecture](#system-architecture)
3. [Repository Structure](#repository-structure)
4. [Prerequisites](#prerequisites)
5. [Building from Source](#building-from-source)
6. [Configuration](#configuration)
7. [API Specification](#api-specification)
8. [Database Schema](#database-schema)
9. [Testing](#testing)
10. [Security](#security)
11. [Contributing](#contributing)
12. [License](#license)

---

## Overview

FlossPay is an enterprise-grade payment processing infrastructure implementing UPI (Unified Payments Interface) rails with support for extensible payment methods. Built on Spring Boot 3.x and Java 21 LTS, it provides:

- **Asynchronous Transaction Processing**: Redis Streams for reliable queue-based processing
- **Idempotency Guarantees**: RFC 4122 UUIDv4 with HMAC-SHA256 replay resistance
- **Audit-First Architecture**: Immutable transaction ledger with SHA-256 checksums
- **Horizontal Scalability**: Stateless API and worker services deployable on Kubernetes

**Current Release**: v0.2-alpha (UPI rail hardened)

**Target Compliance**: PCI-DSS v4.0, SOC 2 Type II, ISO 27001

---

## System Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              CLIENT LAYER                                    │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐                          │
│  │   Merchant   │  │   Mobile     │  │   Web        │                          │
│  │   App        │  │   SDK        │  │   Portal     │                          │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘                          │
└─────────┼────────────────┼────────────────┼──────────────────────────────────┘
          │                │                │
          ▼                ▼                ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                           FLOSSPAY CORE                                      │
│                                                                              │
│  ┌─────────────────────────────────────┐    ┌─────────────────────────────┐   │
│  │        API SERVICE                  │    │      WORKER SERVICE         │   │
│  │  ┌────────────┐  ┌──────────────┐  │    │  ┌─────────────────────┐   │   │
│  │  │ REST API   │  │ Idempotency  │  │    │  │ Stream Consumer     │   │   │
│  │  │ Controller │  │ Service      │  │    │  │ (XREADGROUP)        │   │   │
│  │  └─────┬──────┘  └──────────────┘  │    │  └──────────┬──────────┘   │   │
│  │        │                           │    │             │              │   │
│  │  ┌─────▼─────────────────────┐     │    │  ┌──────────▼──────────┐   │   │
│  │  │   TransactionApiProducer  │     │    │  │  UPI/NPCI Gateway   │   │   │
│  │  │   (Business Logic Layer)  │     │    │  │  Client (Mock)      │   │   │
│  │  └─────┬─────────────────────┘     │    │  └──────────┬──────────┘   │   │
│  └────────┼───────────────────────────┘    └─────────────┼──────────────┘   │
│           │                                              │                   │
│           │         Redis Streams                        │                   │
│           └────────► transactions.main ────────────────────┘                   │
│                    transactions.dlq (DLQ)                                      │
└─────────────────────────────────────────────────────────────────────────────┘
          │
          ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         DATA LAYER                                           │
│  ┌─────────────────────────────────────────────────────────────────────────┐│
│  │  PostgreSQL 15+                                                          ││
│  │  ├── transactions (BIGSERIAL PK)                                          ││
│  │  ├── transaction_history (Audit trail)                                    ││
│  │  ├── idempotency_keys (Request deduplication)                             ││
│  │  ├── webhook_callbacks (Outbound notifications)                           ││
│  │  ├── service_circuit_breakers (Health monitoring)                         ││
│  │  └── client_rate_limits (Quota management)                                ││
│  └─────────────────────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────────────────────┘
```

### Component Responsibilities

| Component | Technology | Responsibility |
|-----------|------------|----------------|
| API Service | Spring Boot 3.2, Java 21 | Public REST API, request validation, idempotency enforcement, HMAC authentication, transaction enqueueing |
| Worker Service | Spring Boot 3.2, Java 21 | Async stream consumption, retry logic with exponential backoff, DLQ management, UPI gateway integration |
| Shared Libraries | Java Module System | DTOs, JPA entities, repositories, validation annotations, exception hierarchy |
| Database Layer | PostgreSQL 15+, Flyway | Relational ledger with audit tables, migration management |
| Message Queue | Redis 7+ Streams | Reliable async processing, consumer groups, dead letter queue |

---

## Repository Structure

```
FlossPay/
├── api-service/                 # REST API and public interface
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/openpay/api/
│   │   │   │   ├── controller/         # REST controllers (Transaction, Health)
│   │   │   │   ├── service/            # Business logic (TransactionApiProducer, Idempotency)
│   │   │   │   ├── security/           # HMAC authentication
│   │   │   │   ├── config/             # Spring configuration (Redis, Web, Logging)
│   │   │   │   └── filter/             # Rate limiting filter
│   │   │   └── resources/
│   │   │       ├── application.properties
│   │   │       └── db/migration/       # Flyway migrations
│   │   └── test/                     # Unit and integration tests
│   └── pom.xml
├── worker-service/              # Background processing
│   ├── src/
│   │   ├── main/java/com/openpay/worker/
│   │   │   ├── processor/              # Stream consumer and retry logic
│   │   │   ├── client/                 # NPCI/UPI gateway client
│   │   │   └── config/                 # Redis worker configuration
│   │   └── resources/
│   └── pom.xml
├── shared-libs/                 # Common domain model
│   ├── src/main/java/com/openpay/shared/
│   │   ├── dto/                      # Data Transfer Objects
│   │   ├── model/                    # JPA Entities
│   │   ├── repository/               # Spring Data Repositories
│   │   ├── exception/                # Exception hierarchy
│   │   └── validation/               # Custom validators
│   └── pom.xml
├── database/                    # Database utilities (reserved)
├── scripts/                     # Development and deployment scripts
│   ├── gen_hmac.py             # HMAC signature generator
│   └── scaffold.sh             # Project scaffolding
├── .github/
│   ├── workflows/              # GitHub Actions CI/CD
│   └── ISSUE_TEMPLATE/         # Issue templates
├── pom.xml                     # Parent Maven POM
├── justfile                    # Task runner commands
├── CODE_OF_CONDUCT.md
├── CONTRIBUTING.md
├── SECURITY.md
└── LICENSE
```

---

## Prerequisites

### Required

- **Java**: OpenJDK 21 LTS (tested with Temurin)
- **Maven**: 3.9.x or later
- **PostgreSQL**: 15.x or later
- **Redis**: 7.x or later (with Streams support)

### Optional

- **Docker**: 24.x or later (for containerized deployment)
- **Docker Compose**: 2.x or later (for local development stack)
- **Just**: Task runner (alternative to Make)

---

## Building from Source

### 1. Clone Repository

```bash
git clone https://github.com/davidgracemann/FlossPay.git
cd FlossPay
```

### 2. Provision Infrastructure

```bash
# PostgreSQL
psql -U postgres -c "CREATE DATABASE flosspay_db;"
psql -U postgres -c "CREATE USER flosspay_user WITH PASSWORD 'secure_random_password';"
psql -U postgres -c "GRANT ALL PRIVILEGES ON DATABASE flosspay_db TO flosspay_user;"

# Redis
redis-cli ping
# Expected: PONG
```

### 3. Build

```bash
# Full build with tests
./mvnw clean verify

# Skip tests (development only)
./mvnw clean install -DskipTests
```

### 4. Run Services

```bash
# Terminal 1: API Service
java -jar api-service/target/api-service-1.0-SNAPSHOT.jar

# Terminal 2: Worker Service
java -jar worker-service/target/worker-service-1.0-SNAPSHOT.jar
```

Or using Just task runner:

```bash
just runa    # Run API service
just runw    # Run Worker service
```

---

## Configuration

### API Service (`api-service/src/main/resources/application.properties`)

```properties
# Database
spring.datasource.url=jdbc:postgresql://localhost:5432/flosspay_db
spring.datasource.username=flosspay_user
spring.datasource.password=${DB_PASSWORD}
spring.datasource.driver-class-name=org.postgresql.Driver
spring.jpa.hibernate.ddl-auto=validate
spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect

# Flyway
spring.flyway.enabled=true
spring.flyway.locations=classpath:db/migration

# Redis
spring.redis.host=localhost
spring.redis.port=6379
spring.redis.password=${REDIS_PASSWORD}
spring.redis.timeout=2000ms
spring.redis.lettuce.pool.max-active=8
spring.redis.lettuce.pool.max-idle=8

# Server
server.port=8080
server.servlet.context-path=/api/v1

# Logging
logging.level.root=INFO
logging.level.com.openpay=DEBUG
logging.pattern.console=%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %logger{36} - %msg%n
```

### Worker Service (create: `worker-service/src/main/resources/application.properties`)

```properties
# Database (same as API)
spring.datasource.url=jdbc:postgresql://localhost:5432/flosspay_db
spring.datasource.username=flosspay_user
spring.datasource.password=${DB_PASSWORD}

# Redis
spring.redis.host=localhost
spring.redis.port=6379
spring.redis.password=${REDIS_PASSWORD}

# Server
server.port=8081
```

### Environment Variables

| Variable | Required | Description | Example |
|----------|----------|-------------|---------|
| `DB_PASSWORD` | Yes | PostgreSQL password | `secure_random_password` |
| `REDIS_PASSWORD` | Yes | Redis password | `redis_secure_pass` |
| `HMAC_SECRET` | Yes | HMAC signing key (min 32 bytes) | `base64_encoded_key` |

---

## API Specification

### Base URL

```
http://localhost:8080/api/v1
```

### Authentication

All payment endpoints require HMAC-SHA256 authentication:

1. Concatenate request body JSON + idempotency key
2. Compute HMAC-SHA256 using shared secret
3. Base64 encode result
4. Include in `X-HMAC` header

### Endpoints

#### 1. Initiate Payment

```bash
POST /pay
Content-Type: application/json
Idempotency-Key: <uuid-v4>
X-HMAC: <base64-hmac-signature>

Request:
{
  "senderUpi": "sender@upi",
  "receiverUpi": "receiver@upi",
  "amount": 100.00
}

Response (200 OK):
{
  "id": 12345,
  "status": "QUEUED",
  "message": "Transaction queued"
}

Response (400 Bad Request):
{
  "error": "Sender and receiver UPI must be different"
}

Response (409 Conflict):
{
  "error": "Duplicate request"
}
```

#### 2. Initiate Collect (Pull)

```bash
POST /collect
Content-Type: application/json
Idempotency-Key: <uuid-v4>
X-HMAC: <base64-hmac-signature>

Request:
{
  "senderUpi": "payer@upi",
  "receiverUpi": "payee@upi",
  "amount": 250.50
}

Response (202 Accepted):
{
  "id": 12346,
  "status": "REQUESTED",
  "message": "Collect request queued"
}
```

#### 3. Get Transaction Status

```bash
GET /transaction/{id}/status

Response (200 OK):
{
  "id": 12345,
  "status": "COMPLETED",
  "senderUpi": "sender@upi",
  "receiverUpi": "receiver@upi",
  "amount": 100.00,
  "createdAt": "2024-01-15T10:30:00Z",
  "updatedAt": "2024-01-15T10:30:05Z"
}

Response (404 Not Found):
{
  "error": "Transaction not found"
}
```

#### 4. Health Check

```bash
GET /health

Response (200 OK):
{
  "status": "UP",
  "timestamp": "2024-01-15T10:30:00Z",
  "version": "0.2-alpha"
}

GET /health/ready

Response (200 OK):
{
  "status": "READY",
  "checks": {
    "database": "UP",
    "redis": "UP"
  }
}

Response (503 Service Unavailable):
{
  "status": "NOT_READY",
  "checks": {
    "database": "DOWN",
    "redis": "UP"
  }
}
```

### cURL Examples

```bash
# Generate HMAC signature
python3 scripts/gen_hmac.py

# Make payment request
curl -X POST http://localhost:8080/api/v1/pay \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen)" \
  -H "X-HMAC: YOUR_HMAC_SIGNATURE" \
  -d '{
    "senderUpi": "test@upi",
    "receiverUpi": "merchant@upi",
    "amount": 100.00
  }'

# Check status
curl http://localhost:8080/api/v1/transaction/12345/status
```

---

## Database Schema

### transactions

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| id | BIGSERIAL | PRIMARY KEY | Auto-generated transaction ID |
| sender_upi | VARCHAR(100) | NOT NULL | Payer UPI ID |
| receiver_upi | VARCHAR(100) | NOT NULL | Payee UPI ID |
| amount | NUMERIC(15,2) | NOT NULL | Transaction amount |
| status | VARCHAR(20) | NOT NULL | queued, processing, completed, failed |
| created_at | TIMESTAMPTZ | DEFAULT NOW() | Creation timestamp |
| updated_at | TIMESTAMPTZ | NULL | Last update timestamp |

**Indexes:**
```sql
CREATE INDEX idx_tx_status_created ON transactions(status, created_at);
```

### transaction_history

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| history_id | BIGSERIAL | PRIMARY KEY | Audit record ID |
| transaction_id | BIGINT | FOREIGN KEY | References transactions.id |
| prev_status | VARCHAR(20) | NOT NULL | Previous status |
| new_status | VARCHAR(20) | NOT NULL | New status |
| changed_at | TIMESTAMPTZ | DEFAULT NOW() | Change timestamp |

### idempotency_keys

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| idempotency_key | VARCHAR(64) | PRIMARY KEY | Client-provided UUID |
| transaction_id | BIGINT | FOREIGN KEY | Associated transaction |
| created_at | TIMESTAMPTZ | DEFAULT NOW() | Key creation time |

### webhook_callbacks

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| callback_id | BIGSERIAL | PRIMARY KEY | Callback record ID |
| transaction_id | BIGINT | FOREIGN KEY | References transactions.id |
| url | VARCHAR(255) | NOT NULL | Webhook endpoint URL |
| status | VARCHAR(20) | NOT NULL | pending, sent, failed |
| last_attempted_at | TIMESTAMPTZ | NULL | Last attempt timestamp |
| attempts | INTEGER | DEFAULT 0 | Delivery attempts |

### service_circuit_breakers

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| service_name | VARCHAR(50) | PRIMARY KEY | External service identifier |
| state | VARCHAR(20) | NOT NULL | CLOSED, OPEN, HALF_OPEN |
| failure_count | INTEGER | DEFAULT 0 | Recent failure count |
| last_failure_at | TIMESTAMPTZ | NULL | Last failure timestamp |

### client_rate_limits

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| client_id | VARCHAR(64) | PRIMARY KEY | API client identifier |
| tokens | INTEGER | DEFAULT 100 | Available quota tokens |
| last_refill | TIMESTAMPTZ | DEFAULT NOW() | Last quota refill |

---

## Testing

### Unit Tests

```bash
./mvnw test
```

### Integration Tests

```bash
./mvnw verify -Pintegration-tests
```

### Test Coverage Requirements

- Line coverage: ≥ 85%
- Branch coverage: ≥ 80%
- Mutation coverage (PIT): ≥ 75%

### Performance Tests

```bash
# Using k6
k6 run scripts/load-test.js

# Using Gatling
gatling.sh -sf src/gatling/scala
```

---

## Security

### Vulnerability Reporting

**DO NOT** open public issues for security vulnerabilities.

Email: security@flosspay.dev

Include:
- Detailed vulnerability description
- Affected versions/branches
- Steps to reproduce or PoC
- Impact assessment

**24-hour acknowledgment SLA**

### Security Controls

| Control | Implementation | Standard |
|---------|---------------|----------|
| Authentication | HMAC-SHA256 request signing | RFC 2104 |
| Idempotency | UUIDv4 keys with TTL | RFC 4122 |
| Transport | TLS 1.3 mandatory | RFC 8446 |
| Encryption | AES-256-GCM at rest | FIPS 197 |
| Audit Trail | SHA-256 signed logs | FIPS 180-4 |
| Retry Logic | Exponential backoff with jitter | FIPS 140-2 |

### Compliance Mapping

- **PCI-DSS v4.0**: Req 3.4 (PAN protection), Req 10 (logging), Req 11 (testing)
- **SOC 2 Type II**: CC6.1 (logical access), CC7.2 (system monitoring)
- **ISO 27001**: A.12.4 (logging), A.14.2.1 (secure development)

---

## Contributing

### Development Workflow

1. **Fork** the repository
2. Create feature branch: `git checkout -b feature/description`
3. Implement changes with tests
4. Run pre-commit checks: `./scripts/pre-commit.sh`
5. Commit with signed-off-by: `git commit -s`
6. Push and open Pull Request

### Commit Message Format

```
subsystem: Brief description (50 chars)

Detailed explanation of what changed and why.
Can span multiple lines.

Signed-off-by: Developer Name <email@example.com>
```

### Code Review Criteria

- [ ] All tests pass
- [ ] Code coverage maintained
- [ ] Security scan clean
- [ ] JavaDoc updated
- [ ] No breaking changes (or documented)
- [ ] Reviewed by 2+ maintainers

### Branch Strategy

- `main`: Production-ready code
- `feature/*`: New features
- `fix/*`: Bug fixes
- `hotfix/*`: Critical production fixes

See [CONTRIBUTING.md](CONTRIBUTING.md) for complete guidelines.

---

## Performance Characteristics

| Metric | Target | Notes |
|--------|--------|-------|
| API Latency (p99) | < 100ms | Without external gateway call |
| Worker Throughput | 100+ TPS | Per instance |
| Database Connections | 20 | HikariCP pool size |
| Redis Timeout | 2s | Connection timeout |
| Retry Backoff | 2^n seconds | Exponential, max 5 attempts |

---

## Troubleshooting

### Issue: "Failed to connect to Redis"

**Solution:**
```bash
# Verify Redis is running
redis-cli ping

# Check configuration in application.properties
spring.redis.host=localhost
spring.redis.port=6379
```

### Issue: "Database connection refused"

**Solution:**
```bash
# Verify PostgreSQL is running
pg_isready -h localhost -p 5432

# Check database exists
psql -U postgres -c "\l" | grep flosspay

# Verify credentials
psql -U flosspay_user -d flosspay_db -c "SELECT 1"
```

### Issue: "HMAC validation failed"

**Solution:**
1. Verify HMAC secret matches between client and server
2. Check message format: `JSON body + idempotencyKey`
3. Use provided script: `python3 scripts/gen_hmac.py`

---

## License

MIT License - See [LICENSE](LICENSE)

Copyright (c) 2024 David Grace

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

---

## Contact

- **Security Issues**: security@flosspay.dev
- **General Questions**: GitHub Discussions
- **Bug Reports**: GitHub Issues (use templates)

---

**Maintainers:**
- David Grace (@davidgracemann) - Project Owner & Chief Architect
- Goutham Rajesh (@gouthamdev) - Product Manager
