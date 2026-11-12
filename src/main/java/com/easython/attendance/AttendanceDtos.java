package com.easython.attendance;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public final class AttendanceDtos {
	private AttendanceDtos() {}

	public enum CheckType { CLOCK_IN, CLOCK_OUT }

	// ---- 위치 인증 ----
	public record LocationCheckRequest(
			@NotNull CheckType type,
			@NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
			@NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
			@NotNull @PositiveOrZero Double accuracyMeters) {}

	public record LocationCheckResponse(boolean verified, String failReason, Integer distanceMeters, Integer radiusMeters,
			String verificationToken, OffsetDateTime expiresAt) {}

	// ---- 출퇴근 기록 ----
	public record ClockPhoto(String url, String hash) {}

	public record ClockInResponse(String recordId, OffsetDateTime serverTimestamp, String workStatus, ClockPhoto photo) {}

	public record ClockOutResponse(String recordId, OffsetDateTime serverTimestamp, String workStatus, int workedMinutes,
			boolean needsBreakInput) {}

	// ---- 홈 / 주간 / 월간 ----
	public record TodayRecord(String id, OffsetDateTime clockInAt, OffsetDateTime clockOutAt, Integer breakMinutes, int workedMinutes) {}

	public record TodayResponse(LocalDate workDate, String workStatus, String nextAction, boolean hasWorkplace, TodayRecord record) {}

	public record WeeklyDay(LocalDate date, int workedMinutes, String recordId, List<String> flags) {}

	public record WeeklyResponse(LocalDate weekStart, LocalDate weekEnd, int totalWorkedMinutes, List<WeeklyDay> days) {}

	public record MonthlySummary(int workDays, int totalWorkedMinutes, int totalBreakMinutes) {}

	public record MonthlyDay(LocalDate date, String recordId, OffsetDateTime clockInAt, OffsetDateTime clockOutAt,
			Integer breakMinutes, int workedMinutes, String source, List<String> flags) {}

	public record MonthlyResponse(String month, MonthlySummary summary, List<MonthlyDay> days) {}

	// ---- 상세 ----
	public record PhotoView(String url, OffsetDateTime expiresAt, String hash) {}

	public record GpsView(double latitude, double longitude, double accuracyMeters, int distanceMeters, boolean verified) {}

	public record EventView(OffsetDateTime at, PhotoView photo, GpsView gps) {}

	public record Current(OffsetDateTime clockInAt, OffsetDateTime clockOutAt, Integer breakMinutes, int workedMinutes) {}

	public record Original(OffsetDateTime clockInAt, OffsetDateTime clockOutAt, Integer breakMinutes) {}

	public record HistoryView(String id, OffsetDateTime editedAt, String field, String before, String after, String reason) {}

	public record DetailResponse(String id, LocalDate workDate, String source, EventView clockIn, EventView clockOut,
			Current current, Original original, List<HistoryView> history) {}

	// ---- 휴게 / 수정 / 수동 추가 ----
	public record BreakRequest(@NotNull @Min(0) Integer breakMinutes) {}

	public record BreakResponse(String recordId, int breakMinutes, int workedMinutes) {}

	public record PatchRequest(OffsetDateTime clockInAt, OffsetDateTime clockOutAt, @Min(0) Integer breakMinutes,
			@NotBlank @Size(max = 500) String reason) {}

	public record PatchResponse(String recordId, int workedMinutes, List<HistoryView> history) {}

	public record ManualRequest(@NotNull LocalDate workDate, @NotNull OffsetDateTime clockInAt, @NotNull OffsetDateTime clockOutAt,
			@Min(0) Integer breakMinutes, @NotBlank @Size(max = 500) String reason) {}

	public record ManualResponse(String recordId, String source, int workedMinutes, OffsetDateTime createdAt) {}

	// ---- 확인 필요 항목 ----
	public record IssueView(String type, LocalDate date, String recordId, String message) {}

	public record IssuesResponse(int count, List<IssueView> issues) {}

	// ---- 이상 패턴(참고용) ----
	public record AnomalyPattern(String type, List<LocalDate> dates, String message) {}

	public record AnomaliesResponse(List<AnomalyPattern> patterns, String disclaimer) {}

	// ---- 데모 시드 ----
	public record SeedRequest(String month, Boolean withContract, Boolean withStatement) {}

	public record SeedResponse(String month, int attendanceRecords, String contractId, String statementId) {}
}
