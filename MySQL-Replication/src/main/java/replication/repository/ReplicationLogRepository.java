package replication.repository;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import replication.entity.ReplicationLog;

@Repository
public class ReplicationLogRepository {

	private final JdbcTemplate jdbcTemplate;

	public ReplicationLogRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	// ── 쓰기: GTID 이벤트 기록 (Source DB) ──
	public void save(ReplicationLog log) {
		jdbcTemplate.update("INSERT INTO replication_log (gtid, event_type, target_table) VALUES (?, ?, ?)",
				log.getGtid(), log.getEventType(), log.getTargetTable());
	}

	// ── 읽기: Replica에서 GTID 도달 여부 확인 (@Transactional(readOnly=true)) ──
	// Source에 기록된 GTID가 Replica에도 존재하는지 검증하는 핵심 메서드
	public List<ReplicationLog> findAll() {
		return jdbcTemplate.query(
				"SELECT id, gtid, event_type, target_table, executed_at FROM replication_log ORDER BY id DESC",
				(rs, rowNum) -> {
					ReplicationLog log = new ReplicationLog(rs.getString("gtid"), rs.getString("event_type"),
							rs.getString("target_table"));
					return log;
				});
	}

	// Source GTID와 비교: Replica에 동일 GTID 존재 여부 확인
	public boolean existsByGtid(String gtid) {
		Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM replication_log WHERE gtid = ?",
				Integer.class, gtid);
		return count != null && count > 0;
	}
}