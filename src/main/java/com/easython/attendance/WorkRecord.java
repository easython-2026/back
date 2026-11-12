package com.easython.attendance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 하루 근무기록. 하루에 한 건(사용자+근무일 유일).
 * original* 은 최초 기록값으로 수정해도 바뀌지 않고, 수정은 WorkRecordHistory 에 쌓는다.
 */
@Entity
@Table(name = "work_records", uniqueConstraints = @UniqueConstraint(columnNames = { "userId", "workDate" }))
@Getter
@Setter
@NoArgsConstructor
public class WorkRecord {
	public enum Source { DEVICE, MANUAL }

	/** 퇴근 기록이 없어도 출근 후 이 시간 안이면 아직 근무 중으로 본다(야간 근무 허용). 넘으면 퇴근 누락. */
	public static final Duration MAX_SHIFT = Duration.ofHours(24);

	@Id
	@Column(length = 36)
	private String id = UUID.randomUUID().toString();

	@Column(nullable = false, length = 36)
	private String userId;

	@Column(nullable = false)
	private LocalDate workDate;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private Source source;

	@Column(nullable = false)
	private Instant clockInAt;
	private Instant clockOutAt;
	private Integer breakMinutes;

	@Column(nullable = false)
	private Instant originalClockInAt;
	private Instant originalClockOutAt;
	private Integer originalBreakMinutes;

	@Column(length = 500)
	private String manualReason;

	@Column(nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	public boolean isWorking(Instant now) {
		return clockOutAt == null && clockInAt.isAfter(now.minus(MAX_SHIFT));
	}

	public boolean isMissingClockOut(Instant now) {
		return clockOutAt == null && !isWorking(now);
	}

	/** 실근무시간(분) = 퇴근-출근-휴게. 근무 중이면 현재까지, 퇴근 누락이면 0. */
	public int workedMinutes(Instant now) {
		Instant end = clockOutAt != null ? clockOutAt : isWorking(now) ? now : null;
		if (end == null) return 0;
		return (int) Math.max(0, Duration.between(clockInAt, end).toMinutes() - (breakMinutes == null ? 0 : breakMinutes));
	}
}
