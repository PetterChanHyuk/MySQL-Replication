package replication.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public class Order {

	public enum Status {
		PENDING, PAID, CANCELLED
	}

	private Long id;
	private Long userId;
	private Status status;
	private BigDecimal totalAmount;
	private LocalDateTime createdAt; // RBR: 복제 시 실제 값 그대로 전파
	private List<OrderItem> items;

	protected Order() {
	}

	public Order(Long userId, BigDecimal totalAmount, List<OrderItem> items) {
		this.userId = userId;
		this.totalAmount = totalAmount;
		this.status = Status.PENDING;
		this.items = items;
	}

	public Long getId() {
		return id;
	}

	public Long getUserId() {
		return userId;
	}

	public Status getStatus() {
		return status;
	}

	public BigDecimal getTotalAmount() {
		return totalAmount;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

	public List<OrderItem> getItems() {
		return items;
	}

	public void setId(Long id) {
		this.id = id;
	}
}