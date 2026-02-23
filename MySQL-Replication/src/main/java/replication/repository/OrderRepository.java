package replication.repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import replication.entity.Order;
import replication.entity.OrderItem;

@Repository
public class OrderRepository {

	private final JdbcTemplate jdbcTemplate;

	public OrderRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	// ── 쓰기: Source DB로 라우팅 ──────────────────────────────────
	// orders + order_items를 하나의 트랜잭션으로 → GTID 단일 ID 부여
	public Order save(Order order) {
		KeyHolder keyHolder = new GeneratedKeyHolder();

		jdbcTemplate.update(con -> {
			PreparedStatement ps = con.prepareStatement(
					"INSERT INTO orders (user_id, status, total_amount) VALUES (?, ?, ?)",
					Statement.RETURN_GENERATED_KEYS);
			ps.setLong(1, order.getUserId());
			ps.setString(2, order.getStatus().name());
			ps.setBigDecimal(3, order.getTotalAmount());
			return ps;
		}, keyHolder);

		long orderId = keyHolder.getKey().longValue();
		order.setId(orderId);

		// order_items도 같은 트랜잭션 안에서 INSERT → GTID 동일
		for (OrderItem item : order.getItems()) {
			item.setOrderId(orderId);
			jdbcTemplate.update("INSERT INTO order_items (order_id, product_name, quantity, price) VALUES (?, ?, ?, ?)",
					item.getOrderId(), item.getProductName(), item.getQuantity(), item.getPrice());
		}

		return order;
	}

	// ── 읽기: Replica DB로 라우팅 (@Transactional(readOnly=true) 적용) ──
	public List<Order> findByUserId(Long userId) {
		return jdbcTemplate.query("SELECT id, user_id, status, total_amount, created_at FROM orders WHERE user_id = ?",
				(rs, rowNum) -> {
					Order o = new Order(rs.getLong("user_id"), rs.getBigDecimal("total_amount"), List.of());
					o.setId(rs.getLong("id"));
					return o;
				}, userId);
	}
}
