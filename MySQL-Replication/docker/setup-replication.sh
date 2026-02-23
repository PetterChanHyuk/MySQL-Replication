#!/bin/bash
# =====================================================
# 복제(Replication) 초기 연결 설정 스크립트
# docker-compose up 이후 한 번만 실행
# 실행: bash docker/setup-replication.sh
# =====================================================

SOURCE_HOST="127.0.0.1"
SOURCE_PORT="3308"
REPLICA_PORT="3309"
ROOT_PASS="root"
REPL_USER="replicator"
REPL_PASS="replicator123"

echo "===== [1/5] Source 세미싱크 활성화 ====="
mysql -h $SOURCE_HOST -P $SOURCE_PORT -uroot -p$ROOT_PASS \
  -e "SET GLOBAL rpl_semi_sync_source_enabled = 1; SET GLOBAL rpl_semi_sync_source_timeout = 1000;"

echo "===== [2/5] Source 복제 계정 생성 ====="
mysql -h $SOURCE_HOST -P $SOURCE_PORT -uroot -p$ROOT_PASS \
  -e "CREATE USER IF NOT EXISTS '$REPL_USER'@'%' IDENTIFIED BY '$REPL_PASS'; GRANT REPLICATION SLAVE ON *.* TO '$REPL_USER'@'%'; FLUSH PRIVILEGES;"

echo "===== [3/5] Source GTID 상태 확인 ====="
mysql -h $SOURCE_HOST -P $SOURCE_PORT -uroot -p$ROOT_PASS \
  -e "SHOW MASTER STATUS\G"

echo "===== [4/5] Replica 세미싱크 활성화 + Source 연결 ====="
mysql -h $SOURCE_HOST -P $REPLICA_PORT -uroot -p$ROOT_PASS \
  -e "SET GLOBAL rpl_semi_sync_replica_enabled = 1;"
mysql -h $SOURCE_HOST -P $REPLICA_PORT -uroot -p$ROOT_PASS \
  -e "CHANGE REPLICATION SOURCE TO SOURCE_HOST='source', SOURCE_PORT=3306, SOURCE_USER='$REPL_USER', SOURCE_PASSWORD='$REPL_PASS', SOURCE_AUTO_POSITION=1; START REPLICA;"

echo "===== [5/5] 복제 상태 확인 ====="
mysql -h $SOURCE_HOST -P $REPLICA_PORT -uroot -p$ROOT_PASS \
  -e "SHOW REPLICA STATUS\G" | grep -E \
  "Replica_IO_Running|Replica_SQL_Running|Seconds_Behind_Source|Retrieved_Gtid_Set|Executed_Gtid_Set"

echo ""
echo "복제 설정 완료! Replica_IO_Running, Replica_SQL_Running 둘 다 Yes 이면 정상입니다."