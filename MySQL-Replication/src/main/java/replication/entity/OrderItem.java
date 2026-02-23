package replication.entity;

import java.math.BigDecimal;

public class OrderItem {

	private Long id;
	private Long orderId; // GTID 추적 단위: orders + order_items 묶음
	private String productName;
	private int quantity;
	private BigDecimal price;

	protected OrderItem() {
	}

	public OrderItem(String productName, int quantity, BigDecimal price) {
		this.productName = productName;
		this.quantity = quantity;
		this.price = price;
	}

	public Long getId() {
		return id;
	}

	public Long getOrderId() {
		return orderId;
	}

	public String getProductName() {
		return productName;
	}

	public int getQuantity() {
		return quantity;
	}

	public BigDecimal getPrice() {
		return price;
	}

	public void setOrderId(Long orderId) {
		this.orderId = orderId;
	}
}
