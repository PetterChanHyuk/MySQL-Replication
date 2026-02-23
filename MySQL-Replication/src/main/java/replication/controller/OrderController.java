package replication.controller;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import replication.entity.Order;
import replication.entity.OrderItem;
import replication.service.OrderService;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

	private final OrderService orderService;

	public OrderController(OrderService orderService) {
		this.orderService = orderService;
	}

	// ══════════════════════════════════════════════════════
	// [읽기] GET /api/orders/{userId}
	// @Transactional(readOnly=true) → Replica DB 조회
	//
	// 로그 확인: [RoutingDataSource] readOnly=true → REPLICA DB
	// ══════════════════════════════════════════════════════
	@GetMapping("/{userId}")
	public ResponseEntity<?> getOrderHistory(@PathVariable Long userId) {
		System.out.println("=== [READ] 주문 조회 요청 → Replica DB로 라우팅 예정 ===");

		List<Order> orders = orderService.getOrderHistory(userId);

		return ResponseEntity.ok(Map.of("userId", userId, "orderCount", orders.size(), "orders",
				orders.stream().map(o -> Map.of("id", o.getId(), "status", o.getStatus().name(), "totalAmount",
						o.getTotalAmount(), "createdAt", o.getCreatedAt() != null ? o.getCreatedAt().toString() : ""))
						.toList(),
				"routedTo", "REPLICA DB (3309)"));
	}

	// ══════════════════════════════════════════════════════
	// [쓰기] POST /api/orders
	// @Transactional → Source DB 쓰기
	//
	// 로그 확인: [RoutingDataSource] readOnly=false → SOURCE DB
	// ══════════════════════════════════════════════════════
	@PostMapping
	public ResponseEntity<?> placeOrder(@RequestBody PlaceOrderRequest request) {
		System.out.println("=== [WRITE] 주문 생성 요청 → Source DB로 라우팅 예정 ===");

		List<OrderItem> items = request.items().stream()
				.map(i -> new OrderItem(i.productName(), i.quantity(), i.price())).toList();

		BigDecimal totalAmount = items.stream().map(i -> i.getPrice().multiply(BigDecimal.valueOf(i.getQuantity())))
				.reduce(BigDecimal.ZERO, BigDecimal::add);

		Order order = new Order(request.userId(), totalAmount, items);
		Order saved = orderService.placeOrder(order);

		return ResponseEntity.ok(Map.of("message", "주문 생성 완료 - Source DB에 저장 후 Replica로 복제됩니다", "orderId",
				saved.getId(), "totalAmount", saved.getTotalAmount(), "routedTo", "SOURCE DB (3308)"));
	}

	// ══════════════════════════════════════════════════════
	// [복제 검증] GET /api/orders/replication/check?gtid=...
	// Source에 기록된 GTID가 Replica에도 도달했는지 확인
	// ══════════════════════════════════════════════════════
	@GetMapping("/replication/check")
	public ResponseEntity<?> checkReplication(@RequestParam String gtid) {
		boolean reached = orderService.isReplicaReachedGtid(gtid);

		return ResponseEntity.ok(Map.of("gtid", gtid, "replicaReached", reached, "message",
				reached ? "✅ Replica에 복제 완료" : "⏳ 아직 Replica에 미도달 (복제 지연 중)"));
	}

	// ── Request DTO ──────────────────────────────────────
	record PlaceOrderRequest(Long userId, List<OrderItemRequest> items) {
	}

	record OrderItemRequest(String productName, int quantity, BigDecimal price) {
	}
}