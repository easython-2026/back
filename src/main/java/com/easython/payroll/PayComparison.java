package com.easython.payroll;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 예상 임금 vs 실제 지급액 비교 결과. 같은 월에 다시 비교하면 새 건이 생기고 최신이 현재값. */
@Entity
@Table(name = "pay_comparisons")
@Getter
@Setter
@NoArgsConstructor
public class PayComparison {
	@Id
	@Column(length = 36)
	private String id = UUID.randomUUID().toString();

	@Column(nullable = false, length = 36)
	private String userId;
	@Column(name = "target_month", nullable = false, length = 7)
	private String month;
	@Column(nullable = false, length = 36)
	private String statementId;
	@Column(nullable = false, length = 36)
	private String contractId;

	/** MATCH / MISMATCH / UNCERTAIN */
	@Column(nullable = false, length = 12)
	private String status;
	private int expectedAmount;
	private int actualAmount;
	private int differenceAmount;

	/** 언어와 무관한 계산 결과(JSON): 적용 조건, 항목별 비교, 불확실 코드. 라벨·설명은 조회 시 사용자 언어로 만든다. */
	@Column(columnDefinition = "TEXT", nullable = false)
	private String resultJson;

	@Column(nullable = false, updatable = false)
	private Instant createdAt = Instant.now();
}
