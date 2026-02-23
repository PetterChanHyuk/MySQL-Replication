# MySQL Replication 발표 시나리오

> **순서대로 실행하면서 각 기술이 어떻게 동작하는지 확인**

---

## 사전 준비 확인

```bash
# 컨테이너 상태 확인
docker ps

# 예상 결과
# mysql-source2  → Up (healthy)
# mysql-replica2 → Up (healthy)
```

---

## STEP 1. 복제 연결 확인

> Replica가 Source를 바라보고 있는지 확인

```bash
docker exec -it mysql-replica2 mysql -uroot -proot \
  -e "SHOW REPLICA STATUS\G" | grep -E "Replica_IO_Running|Replica_SQL_Running|Source_Host|Source_Port"
```

**예상 결과**
```
Source_Host          : source
Source_Port          : 3306
Replica_IO_Running   : Yes
Replica_SQL_Running  : Yes
```

---

## STEP 2. 세미싱크 활성화

> 컨테이너 재시작 시 SET GLOBAL 값이 초기화되므로 매번 수동 활성화 필요

```bash
# Source 세미싱크 활성화
docker exec -it mysql-source2 mysql -uroot -proot \
  -e "SET GLOBAL rpl_semi_sync_source_enabled = 1;
      SET GLOBAL rpl_semi_sync_source_timeout = 1000;"

# Replica 세미싱크 활성화
docker exec -it mysql-replica2 mysql -uroot -proot \
  -e "SET GLOBAL rpl_semi_sync_replica_enabled = 1;"

# 활성화 상태 확인
docker exec -it mysql-source2 mysql -uroot -proot \
  -e "SHOW STATUS LIKE 'Rpl_semi_sync%';"
```

**예상 결과**
```
Rpl_semi_sync_source_status  : ON
Rpl_semi_sync_source_clients : 1   ← Replica 1개 연결됨
Rpl_semi_sync_source_yes_tx  : 0
Rpl_semi_sync_source_no_tx   : 0
```

---

## STEP 3. RBR 확인 — Source에 주문 생성

> `NOW()`가 포함된 INSERT → RBR이 행 값 그대로 복제

```bash
curl -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{
    "userId": 1,
    "items": [{"productName": "RBR 테스트 상품", "quantity": 1, "price": 10000}]
  }'
```

**예상 결과**
```json
{
  "orderId": 102,
  "totalAmount": 10000,
  "message": "주문 생성 완료 - Source DB에 저장 후 Replica로 복제됩니다",
  "routedTo": "SOURCE DB (3308)"
}
```

---

## STEP 4. 세미싱크 ACK 확인

> Replica가 살아있을 때 yes_tx 카운트 증가 확인

```bash
docker exec -it mysql-source2 mysql -uroot -proot \
  -e "SHOW STATUS LIKE 'Rpl_semi_sync_source_yes_tx';"
```

**예상 결과**
```
Rpl_semi_sync_source_yes_tx : 1  ← Replica가 ACK 보낸 트랜잭션 수
```

---

## STEP 5. readOnly 라우팅 확인 — Replica 조회

> `@Transactional(readOnly=true)` → Replica DB로 자동 분기

```bash
curl http://localhost:8080/api/orders/1
```

**예상 결과**
```json
{
  "userId": 1,
  "orderCount": 2,
  "orders": [...],
  "routedTo": "REPLICA DB (3309)"
}
```

---

## STEP 6. 세미싱크 비동기 전환 테스트

### 6-1. Replica 강제 중단

```bash
docker stop mysql-replica2
```

### 6-2. Replica 없는 상태에서 쓰기 요청

> 타임아웃(1초) 후 비동기로 전환되어 응답

```bash
curl -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{
    "userId": 2,
    "items": [{"productName": "비동기 전환 테스트", "quantity": 1, "price": 20000}]
  }'
```

**포인트** : 응답이 약 1초 늦게 오는 것 확인 → 세미싱크 타임아웃 동작

### 6-3. 비동기 전환 상태 확인

```bash
docker exec -it mysql-source2 mysql -uroot -proot \
  -e "SHOW STATUS LIKE 'Rpl_semi_sync%';"
```

**예상 결과**
```
Rpl_semi_sync_source_status : OFF  ← 비동기로 전환됨
Rpl_semi_sync_source_no_tx  : 1    ← 비동기로 처리된 트랜잭션 수
```

---

## STEP 7. GTID 자동 동기화 확인

> Replica 복구 시 빠진 트랜잭션을 GTID 기반으로 자동 보충

### 7-1. Replica 재시작

```bash
docker start mysql-replica2
```

### 7-2. 복제 상태 및 GTID 동기화 확인

```bash
docker exec -it mysql-replica2 mysql -uroot -proot \
  -e "SHOW REPLICA STATUS\G" | grep -E "Executed_Gtid_Set|Retrieved_Gtid_Set|Replica_IO_Running|Replica_SQL_Running"
```

**예상 결과**
```
Replica_IO_Running  : Yes
Replica_SQL_Running : Yes
Retrieved_Gtid_Set  : xxxxxxxx:1-103   ← Source로부터 받은 GTID 범위
Executed_Gtid_Set   : xxxxxxxx:1-103   ← 실제 적용된 GTID 범위 (죽어있던 동안 것도 포함)
```

### 7-3. 데이터 동기화 확인

```bash
# Source 주문 수
docker exec -it mysql-source2 mysql -uroot -proot \
  -e "USE orders_db; SELECT COUNT(*) AS source_orders FROM orders;"

# Replica 주문 수 (동일해야 함)
docker exec -it mysql-replica2 mysql -uroot -proot \
  -e "USE orders_db; SELECT COUNT(*) AS replica_orders FROM orders;"
```

**예상 결과**
```
source_orders  : 102  ✅
replica_orders : 102  ✅  ← GTID로 자동 동기화 완료
```

---

## 전체 흐름 요약

```
STEP 1  복제 연결 확인        → Replica_IO/SQL_Running: Yes
STEP 2  세미싱크 활성화       → status: ON, clients: 1
STEP 3  주문 생성 (RBR)       → Source DB 라우팅, NOW() 행 값 복제
STEP 4  세미싱크 ACK 확인     → yes_tx 증가
STEP 5  주문 조회             → Replica DB 라우팅
STEP 6  Replica 중단 후 쓰기  → 1초 지연 응답, status: OFF, no_tx 증가
STEP 7  Replica 복구          → GTID로 빠진 트랜잭션 자동 보충
```