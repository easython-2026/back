package com.easython.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 저장 기록 삭제 요청(처리 이력). 처리는 비동기로 즉시 수행한다. */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class DataDeletionRequest {
	@Id
	@Column(length = 36)
	private String id = UUID.randomUUID().toString();
	@Column(nullable = false, length = 36)
	private String userId;
	/** CONTRACTS, ATTENDANCE, PAYROLL, COMPLAINTS, ALL (CSV) */
	@Column(nullable = false)
	private String targets;
	/** RECEIVED / DONE / FAILED */
	@Column(nullable = false, length = 10)
	private String status = "RECEIVED";
	@Column(nullable = false)
	private Instant createdAt = Instant.now();
	private Instant completedAt;
}
