package replication.entity;

import java.time.LocalDateTime;

public class User {

	private Long id;
	private String email;
	private String name;
	private LocalDateTime createdAt;

	protected User() {
	}

	public User(String email, String name) {
		this.email = email;
		this.name = name;
	}

	public Long getId() {
		return id;
	}

	public String getEmail() {
		return email;
	}

	public String getName() {
		return name;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}
}
