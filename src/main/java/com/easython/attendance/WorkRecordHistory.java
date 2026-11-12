package com.easython.attendance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 근무기록 수정 이력(원본은 덮어쓰지 않고 이력만 추가). */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class WorkRecordHistory {
	public enum Field { CLOCK_IN, CLOCK_OUT, BREAK }

	@Id
	@Column(length = 36)
	private String id = UUID.randomUUID().toString();

	@Column(nullable = false, length = 36)
	private String recordId;

	@Column(nullable = false)
	private Instant editedAt;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 12)
	private Field field;

	@Column(name = "before_value")
	private String before;
	@Column(name = "after_value")
	private String after;

	@Column(nullable = false, length = 500)
	private String reason;

	public WorkRecordHistory(String recordId, Instant editedAt, Field field, String before, String after, String reason) {
		this.recordId = recordId;
		this.editedAt = editedAt;
		this.field = field;
		this.before = before;
		this.after = after;
		this.reason = reason;
	}

	/** 휴게 입력 외의 수정(출퇴근 시각 변경, 휴게 값 변경)인지 */
	public boolean isModification() {
		return field != Field.BREAK || before != null;
	}
}
