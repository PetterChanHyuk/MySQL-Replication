# MySQL Replication 시나리오

> **Spring Boot + MySQL + Docker** 환경에서 복제(Replication) 구조를 직접 구현하고 검증하는 프로젝트
> 발표용 데모 시나리오는 [DEMO.md](./MySQL-Replication/DEMO.md) 참고

---

## 개요

이커머스 주문 서비스의 DB 병목 문제를 해결하기 위해 MySQL 복제 구조를 도입한 시나리오입니다. <br/>
주문 조회 트래픽(70%)을 Replica로 분산하고, 장애 시 데이터 유실을 방지하는 것이 목표입니다.

### 핵심 기술

| 기술 | 역할 |
|------|------|
| **GTID** | 트랜잭션마다 고유 ID 부여 → 복제 위치 자동 추적, Replica 재시작 시 빠진 트랜잭션 자동 보충 |
| **Semi-Sync (세미 싱크)** | Source 커밋 후 Replica ACK 확인 → 데이터 유실 방지, 타임아웃 시 비동기로 자동 전환 |
| **RBR (Row Based Replication)** | SQL이 아닌 변경된 행 값 그대로 복제 → `NOW()`, `UUID()` 포함 쿼리도 정확하게 복제 |
| **@Transactional(readOnly=true)** | Spring 레벨에서 읽기/쓰기 분리 → readOnly면 Replica, 아니면 Source로 자동 라우팅 |

---

## 아키텍처
<img width="1144" height="585" alt="image" src="https://github.com/user-attachments/assets/01304a74-00c7-4f7b-87eb-28e3c320e4bf" />


```
[ Spring Boot App ]
        │
        ├── 쓰기 @Transactional ─────────────────→ [ Source DB :3308  ]
        │                                               │
        └── 읽기 @Transactional(readOnly=true) ──→ [ Replica DB :3309 ]
                                                        │
                             ← Binary Log (RBR) + GTID + Semi-Sync ──┘
```

### 읽기/쓰기 라우팅 흐름
| 구분 | 🟢 Read-Only (읽기 전용) | 🔴 Read-Write (읽기/쓰기) |
| :--- | :--- | :--- |
| **분기 조건** | `isCurrentTransactionReadOnly() = true` ⭐ | `isCurrentTransactionReadOnly() = false` ⭐ |
| **대상 DB** | Replica DataSource (3309) | Source DataSource (3308) |
| **커넥션 풀** | HikariCP Replica 풀에서 커넥션 반환 | HikariCP Source 풀에서 커넥션 반환 |
| **쿼리 실행** | JdbcTemplate 쿼리 실행 (SELECT) | JdbcTemplate 쿼리 실행 (INSERT/UPDATE/DELETE) |
| **DB 응답** | MySQL Replica Server (3309) 응답 | MySQL Source Server (3308) 응답 |
| **종료 및 반납**| 트랜잭션 종료 → Replica 커넥션 풀 반납 | 트랜잭션 종료 → Source 커넥션 풀 반납 |


### 세미싱크 동작 흐름

```
Replica 살아있음  → 세미싱크 ON  → ACK 확인 후 응답  → yes_tx 증가
Replica 죽음      → 1초 타임아웃 → 비동기로 전환     → no_tx 증가, status=OFF
Replica 복구      → 세미싱크 자동 ON 복귀
```

### GTID 자동 동기화 흐름

```
Replica 재시작
      ↓
Source에 재연결
      ↓
"나는 GTID :1~102 까지 받았어, 그 이후 것 줘"
      ↓
Source가 빠진 트랜잭션만 골라서 전송
      ↓
Replica 자동 동기화 완료 (중복/누락 없음)
```

---

## 프로젝트 구조

```
MySQL-Replication/
├── docker-compose.yml
├── docker/
│   ├── source.cnf              # RBR + GTID + 세미싱크 플러그인 설정
│   ├── replica.cnf             # read-only + 세미싱크 플러그인 설정
│   └── setup-replication.sh   # 복제 연결 초기화 스크립트
├── sql/
│   └── init.sql                # DDL + 더미데이터 (각 테이블 100건)
├── README.md
├── DEMO.md                     # 발표용 시나리오 명령어 모음
└── src/main/java/com/example/replication/
    ├── ReplicationApplication.java
    ├── config/
    │   ├── DataSourceConfig.java    # Source/Replica DataSource 빈 등록
    │   └── RoutingDataSource.java   # readOnly 여부로 DB 분기
    ├── controller/
    │   └── OrderController.java     # REST API (읽기/쓰기 라우팅 확인용)
    ├── service/
    │   └── OrderService.java        # @Transactional 읽기/쓰기 분리
    ├── repository/
    │   ├── OrderRepository.java
    │   └── ReplicationLogRepository.java
    └── entity/
        ├── User.java
        ├── Order.java               # NOW() 포함 → RBR 필요성 부각
        ├── OrderItem.java           # orders와 묶여 GTID 단일 ID 부여
        └── ReplicationLog.java      # GTID 값 기록 및 복제 도달 검증
```

---

## DB 테이블 설계

```
users ──────────────── orders ──────────────── order_items
  id                     id                       id
  email                  user_id (FK)              order_id (FK)
  name                   status                    product_name
  created_at             total_amount              quantity
                         created_at ← RBR 포인트   price

replication_log
  id
  gtid         ← GTID 값 직접 기록
  event_type   ← INSERT / UPDATE / DELETE
  target_table
  executed_at
```

### 기술-테이블 매핑

| 기술 | 연관 테이블 | 확인 포인트 |
|------|------------|------------|
| **RBR** | `orders` | `NOW()` 포함 INSERT → Source/Replica 값 일치 확인 |
| **세미싱크** | `orders`, `order_items` | 주문 생성 트랜잭션 커밋 시 ACK 대기 동작 |
| **GTID** | `order_items`, `replication_log` | 트랜잭션 단위 ID 추적, Replica 재시작 후 자동 동기화 |
| **readOnly 라우팅** | `users`, `orders` | SELECT → Replica(3309), INSERT/UPDATE → Source(3308) |

---

## 실행 방법

### 1. 컨테이너 실행

```bash
docker-compose up -d

# 상태 확인 (둘 다 healthy 될 때까지 대기)
docker ps
```

### 2. 복제 연결 초기화 (최초 1회)

```bash
# Source 세미싱크 활성화
docker exec -it mysql-source2 mysql -uroot -proot \
  -e "SET GLOBAL rpl_semi_sync_source_enabled = 1;
      SET GLOBAL rpl_semi_sync_source_timeout = 1000;"

# 복제 전용 계정 생성 (mysql_native_password로 SSL 문제 해결)
docker exec -it mysql-source2 mysql -uroot -proot \
  -e "CREATE USER IF NOT EXISTS 'replicator'@'%'
        IDENTIFIED WITH mysql_native_password BY 'replicator123';
      GRANT REPLICATION SLAVE ON *.* TO 'replicator'@'%';
      FLUSH PRIVILEGES;"

# Replica 세미싱크 활성화
docker exec -it mysql-replica2 mysql -uroot -proot \
  -e "SET GLOBAL rpl_semi_sync_replica_enabled = 1;"

# Replica → Source 연결 (GTID 기반 자동 위치 추적)
docker exec -it mysql-replica2 mysql -uroot -proot \
  -e "CHANGE REPLICATION SOURCE TO
        SOURCE_HOST='source',
        SOURCE_PORT=3306,
        SOURCE_USER='replicator',
        SOURCE_PASSWORD='replicator123',
        SOURCE_AUTO_POSITION=1;
      START REPLICA;"
```

### 3. 복제 상태 확인

```bash
docker exec -it mysql-replica2 mysql -uroot -proot \
  -e "SHOW REPLICA STATUS\G" | grep -E "Replica_IO_Running|Replica_SQL_Running|Last_IO_Error"
```

```
Replica_IO_Running  : Yes  ✅
Replica_SQL_Running : Yes  ✅
Last_IO_Error       :      ✅ (비어있으면 정상)
```

### 4. 세미싱크 재활성화 (컨테이너 재시작 후마다)

> `SET GLOBAL`은 메모리에만 저장되어 재시작 시 초기화됨

```bash
docker exec -it mysql-source2 mysql -uroot -proot \
  -e "SET GLOBAL rpl_semi_sync_source_enabled = 1;
      SET GLOBAL rpl_semi_sync_source_timeout = 1000;"

docker exec -it mysql-replica2 mysql -uroot -proot \
  -e "SET GLOBAL rpl_semi_sync_replica_enabled = 1;"
```

### 5. Spring Boot 실행

이클립스에서 `ReplicationApplication.java` Run 또는

```bash
mvn spring-boot:run
```

---

## API

| Method | URL | 설명 | 라우팅 |
|--------|-----|------|--------|
| `GET` | `/api/orders/{userId}` | 주문 조회 | Replica (3309) |
| `POST` | `/api/orders` | 주문 생성 | Source (3308) |
| `GET` | `/api/orders/replication/check?gtid=...` | GTID 복제 도달 확인 | Replica (3309) |

### 읽기 — Replica DB 라우팅

```bash
curl http://localhost:8080/api/orders/1
```

```json
{
  "userId": 1,
  "orderCount": 2,
  "orders": [...],
  "routedTo": "REPLICA DB (3309)"
}
```

### 쓰기 — Source DB 라우팅

```bash
curl -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{
    "userId": 1,
    "items": [{"productName": "테스트 상품", "quantity": 2, "price": 15000}]
  }'
```

```json
{
  "orderId": 102,
  "totalAmount": 30000,
  "message": "주문 생성 완료 - Source DB에 저장 후 Replica로 복제됩니다",
  "routedTo": "SOURCE DB (3308)"
}
```

---

## 핵심 코드 설명

### LazyConnectionDataSourceProxy가 필요한 이유

```java
@Bean
@Primary
public DataSource dataSource(@Qualifier("routingDataSource") DataSource routing) {
    return new LazyConnectionDataSourceProxy(routing);
}
```

Spring은 트랜잭션 시작 시 **즉시** 커넥션을 가져오려 한다.
이 시점에는 아직 `readOnly` 여부가 결정되기 전이라 항상 Source로 가게 된다.
`LazyConnectionDataSourceProxy`로 감싸면 **실제 쿼리 직전**에 커넥션을 획득하므로
`RoutingDataSource`가 `readOnly` 값을 올바르게 읽어 Replica로 분기할 수 있다.

### RBR이 필요한 이유

```sql
-- SBR (Statement Based): SQL 문장 자체를 복제
-- Source  → NOW() 실행: '2024-01-10 10:00:00'
-- Replica → NOW() 재실행: '2024-01-10 10:00:01' ← 불일치 발생!

-- RBR (Row Based): 변경된 행 값 그대로 복제
-- Source  → created_at = '2024-01-10 10:00:00'
-- Replica → created_at = '2024-01-10 10:00:00' ← 항상 동일 보장
```

### 세미싱크 vs 비동기 복제

```
비동기:   Source 커밋 → 클라이언트 응답 → (나중에) Replica 전달
                                          ↑ 장애 시 유실 가능

세미싱크: Source 커밋 → Replica ACK 대기(최대 1초) → 클라이언트 응답
                        ↑ 최소 1개 Replica 수신 확인 후 응답 → 유실 방지
                        ↑ 타임아웃 시 자동으로 비동기 전환 → 가용성 유지
```

---

## 트러블슈팅

### rpl_semi_sync_source_enabled unknown variable
MySQL 초기화(`--initialize`) 단계에서는 플러그인이 로드되지 않아 관련 변수를 읽지 못함.
`cnf`에는 `plugin-load-add`만 넣고, 세미싱크 활성화는 컨테이너 기동 후 SQL로 처리.

```ini
# source.cnf
plugin-load-add=semisync_source.so
# rpl_semi_sync_source_enabled=1  ← 여기 넣으면 컨테이너 시작 실패
```

### super-read-only로 인한 컨테이너 시작 실패
Docker entrypoint가 초기화 중 내부적으로 쿼리를 실행하는데 `super-read-only`가 이를 차단함.
`replica.cnf`에서 `super-read-only=ON` 제거, `read-only=ON`만 유지.

### Authentication requires secure connection
MySQL 8.0 기본 인증 방식 `caching_sha2_password`는 SSL 연결을 요구함.
replicator 계정 생성 시 `mysql_native_password`로 지정하여 해결.

```sql
CREATE USER 'replicator'@'%'
  IDENTIFIED WITH mysql_native_password BY 'replicator123';
```

---

## 기술 스택

| 분류 | 기술 |
|------|------|
| Language | Java 17 |
| Framework | Spring Boot 3.2.3 |
| DB | MySQL 8.0.45 |
| Connection | JDBC (JdbcTemplate) |
| Connection Pool | HikariCP |
| Container | Docker, Docker Compose |
| Build | Maven |
