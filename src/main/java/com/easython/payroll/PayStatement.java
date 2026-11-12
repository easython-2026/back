package com.easython.payroll;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 급여명세서(사진 분석) 또는 수령액 직접 입력(MANUAL). 확정된 것만 비교에 사용. */
@Entity
@Table(name = "pay_statements")
@Getter
@Setter
@NoArgsConstructor
public class PayStatement {
	public enum Status { PROCESSING, DONE, FAILED }

	public enum Source { PHOTO, MANUAL }

	@Id
	@Column(length = 36)
	private String id = UUID.randomUUID().toString();

	@Column(nullable = false, length = 36)
	private String userId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 12)
	private Status status = Status.PROCESSING;
	private String failReason;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private Source source = Source.PHOTO;

	/** 귀속 월 YYYY-MM (분석 전 null 가능) */
	@Column(name = "target_month", length = 7)
	private String month;
	private boolean confirmed;

	private String fileKey;
	@Column(length = 64)
	private String fileHash;
	@Column(length = 40)
	private String fileContentType;

	private Integer grossAmount;
	private Integer deductionTotal;
	private Integer netAmount;
	/** 사용자가 직접 입력한 수령액(MANUAL) */
	private Integer receivedAmount;
	private LocalDate paidAt;

	/** 최초 AI 추출값 원본(JSON). 사용자 수정과 별도 보관. */
	@Column(columnDefinition = "TEXT")
	private String ocrJson;

	@Column(nullable = false, updatable = false)
	private Instant createdAt = Instant.now();
	@Column(nullable = false)
	private Instant analysisStartedAt = Instant.now();
}
