package com.easython.complaint;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 진정서: 한국어 초안(코드 템플릿) + 모국어 번역(AI). 사용자가 검토·확정(reviewed)해야 PDF 가능. */
@Entity
@Table(name = "complaints")
@Getter
@Setter
@NoArgsConstructor
public class Complaint {
	public enum Phase { PROCESSING, DONE, FAILED }

	@Id
	@Column(length = 36)
	private String id = UUID.randomUUID().toString();

	@Column(nullable = false, length = 36)
	private String userId;
	@Column(nullable = false, length = 36)
	private String comparisonId;

	/** 생성(한국어 초안) 상태 */
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 12)
	private Phase status = Phase.PROCESSING;
	/** 모국어 번역 상태(재번역 포함) */
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 12)
	private Phase translationStatus = Phase.PROCESSING;
	private String failReason;
	private boolean reviewed;

	private LocalDate periodStart;
	private LocalDate periodEnd;

	// 피진정인(사업장)
	private String respondentName;
	private String respondentAddress;
	private String respondentOwner;

	/** 번역 대상 언어 */
	@Column(nullable = false, length = 8)
	private String language;

	@Column(columnDefinition = "TEXT")
	private String summaryKo;
	@Column(columnDefinition = "TEXT")
	private String summaryTranslated;

	private String titleKo;
	@Column(columnDefinition = "TEXT")
	private String bodyKo;
	private Integer unpaidAmount;
	/** [{label, amount}] JSON */
	@Column(columnDefinition = "TEXT")
	private String claimsJson;

	private String translatedTitle;
	@Column(columnDefinition = "TEXT")
	private String translatedBody;

	/** 첨부 증거 id(CSV). id 는 영문·숫자·하이픈만 쓰므로 쉼표 구분 가능 */
	@Column(columnDefinition = "TEXT")
	private String evidenceIds = "";

	@Column(nullable = false, updatable = false)
	private Instant createdAt = Instant.now();
	@Column(nullable = false)
	private Instant updatedAt = Instant.now();

	public List<String> evidenceIdList() {
		return evidenceIds == null || evidenceIds.isBlank() ? List.of() : Arrays.asList(evidenceIds.split(","));
	}
}
