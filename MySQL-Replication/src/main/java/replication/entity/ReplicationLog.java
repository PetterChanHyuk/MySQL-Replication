package replication.entity;

import java.time.LocalDateTime;

public class ReplicationLog {

	private Long id;
	private String gtid; // Source에서 발급된 GTID 값 기록
	private String eventType; // INSERT / UPDATE / DELETE
	private String targetTable;
	private LocalDateTime executedAt;

	protected ReplicationLog() {
	}

	public ReplicationLog(String gtid, String eventType, String targetTable) {
		this.gtid = gtid;
		this.eventType = eventType;
		this.targetTable = targetTable;
	}

	public Long getId() {
		return id;
	}

	public String getGtid() {
		return gtid;
	}

	public String getEventType() {
		return eventType;
	}

	public String getTargetTable() {
		return targetTable;
	}

	public LocalDateTime getExecutedAt() {
		return executedAt;
	}
}
