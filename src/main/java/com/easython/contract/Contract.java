package com.easython.contract;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 근로계약서: 원본 파일 + AI 추출 조건(사용자가 수정 가능) + 확정 여부. 급여 계산은 확정된 최신 계약서를 쓴다. */
@Entity
@Table(name = "contracts")
@Getter
@Setter
@NoArgsConstructor
public class Contract {
	public enum Status { PROCESSING, DONE, FAILED }

	@Id
	@Column(length = 36)
	private String id = UUID.randomUUID().toString();

	@Column(nullable = false, length = 36)
	private String userId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 12)
	private Status status = Status.PROCESSING;
	private String failReason;
	private boolean confirmed;
	private Instant confirmedAt;

	private String fileKey; // 데모 시드 계약서는 파일 없음
	@Column(length = 64)
	private String fileHash;
	@Column(length = 40)
	private String fileContentType;
	@Column(length = 10)
	private String source;

	/** 번역 대상 언어 */
	@Column(nullable = false, length = 8)
	private String language;
	@Column(columnDefinition = "TEXT")
	private String translationSummary;

	// ---- 추출 조건(수정 가능) ----
	@Column(length = 10)
	private String wageType;
	private Integer wageAmount;
	@Column(length = 5)
	private String workStartTime;
	@Column(length = 5)
	private String workEndTime;
	private Integer breakMinutesPerDay;
	private String workDays; // "MON,TUE,..."
	private Integer mealDeduction;
	private Integer payday;
	private LocalDate contractStart;
	private LocalDate contractEnd;
	private String employerName;
	private String employerAddress;
	private String ownerName;

	/** 사용자가 직접 수정한 필드명(CSV). 재분석해도 이 값들은 덮어쓰지 않는다. */
	@Column(length = 500)
	private String editedFields = "";
	/** 최초 AI(OCR) 추출값 원본(JSON). 수정해도 보관. */
	@Column(columnDefinition = "TEXT")
	private String ocrTermsJson;

	@Column(nullable = false, updatable = false)
	private Instant createdAt = Instant.now();
	@Column(nullable = false)
	private Instant analysisStartedAt = Instant.now();

	public List<String> workDayList() {
		return workDays == null || workDays.isBlank() ? List.of() : Arrays.asList(workDays.split(","));
	}

	public Set<String> editedSet() {
		return editedFields == null || editedFields.isBlank() ? new HashSet<>() : new HashSet<>(Arrays.asList(editedFields.split(",")));
	}

	public void markEdited(Set<String> fields) {
		Set<String> all = editedSet();
		all.addAll(fields);
		this.editedFields = String.join(",", all);
	}
}
