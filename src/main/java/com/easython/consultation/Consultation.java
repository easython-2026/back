package com.easython.consultation;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 상담 사건. 노동자가 공유 범위(sharedScope)에 동의한 만큼만 기관 담당자에게 보인다. */
@Entity
@Table(name = "consultations")
@Getter
@Setter
@NoArgsConstructor
public class Consultation {
	public static final String RECEIVED = "RECEIVED", REVIEWING = "REVIEWING", INFO_REQUESTED = "INFO_REQUESTED", REFERRED = "REFERRED", CLOSED = "CLOSED";

	@Id
	@Column(length = 36)
	private String id = UUID.randomUUID().toString();

	@Column(nullable = false, length = 36)
	private String workerId;
	@Column(nullable = false, length = 36)
	private String agencyId;
	@Column(nullable = false, length = 36)
	private String comparisonId;
	@Column(length = 36)
	private String contractId;
	@Column(length = 36)
	private String statementId;
	@Column(length = 36)
	private String complaintId;
	/** 귀속 월 YYYY-MM (공유된 근무기록 범위) */
	@Column(name = "target_month", length = 7)
	private String month;

	@Column(nullable = false, length = 16)
	private String status = RECEIVED;
	/** PROCESSING / DONE / FAILED (상담용 요약 카드) */
	@Column(nullable = false, length = 12)
	private String summaryStatus = "PROCESSING";
	@Column(columnDefinition = "TEXT")
	private String summaryKo;
	@Column(columnDefinition = "TEXT")
	private String summaryTranslated;
	private String headline;
	private int differenceAmount;

	/** 공유 범위 CSV: SUMMARY, CONTRACT, ATTENDANCE, PAYSLIP, COMPLAINT_DRAFT */
	@Column(nullable = false)
	private String sharedScope;
	/** 개별 지정한 공유 증거 id CSV(비면 범위 전체) */
	@Column(columnDefinition = "TEXT")
	private String evidenceIds = "";
	/** 메시지 첨부로 추가 공유한 증거 id CSV(공유 범위와 별개로 항상 공유) */
	@Column(columnDefinition = "TEXT")
	private String extraEvidenceIds = "";
	@Column(nullable = false)
	private Instant agreedAt;
	private Instant revokedAt;

	@Column(columnDefinition = "TEXT")
	private String memo;
	@Column(columnDefinition = "TEXT")
	private String memoKo;

	/** 담당자가 확정한 긴급도 / 자동 제안값(참고용, 법적 긴급도 확정 아님) */
	@Column(length = 8)
	private String urgencyLevel;
	@Column(length = 8)
	private String aiSuggestedUrgency;
	private String triageNote;
	@Column(length = 36)
	private String assigneeId;

	@Column(columnDefinition = "TEXT")
	private String resultContent;
	@Column(columnDefinition = "TEXT")
	private String resultAction;
	@Column(columnDefinition = "TEXT")
	private String resultCloseReason;
	@Column(columnDefinition = "TEXT")
	private String resultSummaryTranslated;
	private boolean resultSharedWithWorker;
	private Instant closedAt;

	@Column(nullable = false, updatable = false)
	private Instant createdAt = Instant.now();
	@Column(nullable = false)
	private Instant updatedAt = Instant.now();

	public boolean revoked() {
		return revokedAt != null;
	}

	public boolean closed() {
		return CLOSED.equals(status);
	}

	public java.util.List<String> scope() {
		return java.util.Arrays.asList(sharedScope.split(","));
	}

	public java.util.List<String> evidenceIdList() {
		return evidenceIds == null || evidenceIds.isBlank() ? java.util.List.of() : java.util.Arrays.asList(evidenceIds.split(","));
	}

	public java.util.List<String> extraEvidenceIdList() {
		return extraEvidenceIds == null || extraEvidenceIds.isBlank() ? java.util.List.of() : java.util.Arrays.asList(extraEvidenceIds.split(","));
	}

	public String effectiveUrgency() {
		return urgencyLevel != null ? urgencyLevel : aiSuggestedUrgency;
	}
}
