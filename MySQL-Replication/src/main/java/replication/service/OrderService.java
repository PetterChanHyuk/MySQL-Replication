package replication.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import replication.entity.Order;
import replication.entity.ReplicationLog;
import replication.repository.OrderRepository;
import replication.repository.ReplicationLogRepository;

@Service
public class OrderService {

	private final OrderRepository orderRepository;
	private final ReplicationLogRepository replicationLogRepository;

	public OrderService(OrderRepository orderRepository, ReplicationLogRepository replicationLogRepository) {
		this.orderRepository = orderRepository;
		this.replicationLogRepository = replicationLogRepository;
	}

	// ══════════════════════════════════════════════════
	// 쓰기 트랜잭션 → RoutingDataSource가 Source DB 선택
	//
	// [세미싱크] 커밋 후 Replica ACK 대기 → 유실 방지
	// [GTID] orders + order_items + replication_log
	// → 단일 트랜잭션 = 단일 GTID 부여
	// [RBR] created_at(NOW()) 포함 → 행 값 그대로 복제
	// ══════════════════════════════════════════════════
	@Transactional
	public Order placeOrder(Order order) {
		Order saved = orderRepository.save(order); // orders + order_items INSERT

		// GTID 추적용 로그 기록 (같은 트랜잭션 = 같은 GTID)
		String currentGtid = fetchCurrentGtid();
		replicationLogRepository.save(new ReplicationLog(currentGtid, "INSERT", "orders"));

		return saved;
	}

	// ══════════════════════════════════════════════════
	// 읽기 트랜잭션 → RoutingDataSource가 Replica DB 선택
	//
	// [readOnly=true] TransactionSynchronizationManager
	// .isCurrentTransactionReadOnly() = true
	// → RoutingDataSource → Replica 커넥션
	// ══════════════════════════════════════════════════
	@Transactional(readOnly = true)
	public List<Order> getOrderHistory(Long userId) {
		return orderRepository.findByUserId(userId);
	}

	// ══════════════════════════════════════════════════
	// GTID 복제 도달 여부 검증
	// Source에서 기록한 GTID가 Replica에도 존재하는지 확인
	// ══════════════════════════════════════════════════
	@Transactional(readOnly = true)
	public boolean isReplicaReachedGtid(String gtid) {
		return replicationLogRepository.existsByGtid(gtid);
	}

	// Source DB에서 현재 GTID 조회
	// 실제로는 "SELECT @@gtid_executed" 쿼리로 가져옴
	private String fetchCurrentGtid() {
		// 실제 구현에서는 JdbcTemplate으로 Source에 직접 조회
		// jdbcTemplate.queryForObject("SELECT @@gtid_executed", String.class)
		return "placeholder-gtid"; // 추후 DataSourceConfig에서 주입
	}
}