package com.easython.payroll;

import com.easython.attendance.WorkRecord;
import com.easython.contract.Contract;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 예상 임금 계산(지원 범위 내). 순수 계산이라 DB·Spring 없이 테스트 가능.
 * 지원: 기본급(실근무시간×시급 또는 월급), 일 8시간 초과 연장근로 가산 0.5배.
 * 미지원(결과에 unsupported 로 표시): 야간근로, 휴일근로, 주휴수당(시급제), 공휴일.
 * ponytail: 주 40시간 초과 연장, 야간·휴일 가산, 주휴수당 계산은 미구현. 필요하면 이 클래스에 규칙 추가.
 */
public final class PayrollCalculator {
	public static final String NIGHT_WORK = "NIGHT_WORK";
	public static final String HOLIDAY_WORK = "HOLIDAY_WORK";
	public static final String WEEKLY_HOLIDAY_PAY = "WEEKLY_HOLIDAY_PAY";
	public static final String MISSING_BREAK = "MISSING_BREAK";
	public static final String MISSING_DAYS = "MISSING_DAYS";

	static final int DAILY_REGULAR_MINUTES = 8 * 60;
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	public record Result(int workedMinutes, int workDays, int overtimeMinutes, Integer breakApplied, Integer hourlyWage, int basePay,
			int overtimePay, List<String> appliedRules, List<String> unsupportedRules, List<String> uncertainties, int missingDays) {
		public int expected() {
			return basePay + overtimePay;
		}
	}

	private PayrollCalculator() {}

	/** hourlyForMonthly: 월급제일 때 환산 통상시급(알 수 없으면 null). 시급제는 계약 시급을 쓴다. */
	public static Result calculate(Contract c, List<WorkRecord> records, Integer hourlyForMonthly) {
		boolean hourly = "HOURLY".equals(c.getWageType());
		Integer hw = hourly ? c.getWageAmount() : hourlyForMonthly;
		int worked = 0, overtime = 0, days = 0, missing = 0;
		boolean breakMissing = false, night = false, holiday = false;
		Integer breakApplied = c.getBreakMinutesPerDay();
		List<String> contractDays = c.workDayList();

		for (WorkRecord r : records) {
			if (r.getClockOutAt() == null) {
				missing++;
				continue;
			}
			long total = Duration.between(r.getClockInAt(), r.getClockOutAt()).toMinutes();
			Integer br = r.getBreakMinutes() != null ? r.getBreakMinutes() : c.getBreakMinutesPerDay();
			if (br == null) breakMissing = true;
			long net = Math.max(0, total - (br == null ? 0 : br));
			worked += net;
			overtime += Math.max(0, net - DAILY_REGULAR_MINUTES);
			days++;
			if (overlapsNight(r)) night = true;
			DayOfWeek dow = r.getWorkDate().getDayOfWeek();
			if (dow == DayOfWeek.SUNDAY || (!contractDays.isEmpty() && !contractDays.contains(dow.name().substring(0, 3)))) holiday = true;
		}

		int base = hourly ? (hw == null ? 0 : (int) Math.round(hw * worked / 60.0)) : c.getWageAmount() == null ? 0 : c.getWageAmount();
		int ot = hw == null ? 0 : (int) Math.round(hw * 0.5 * overtime / 60.0);

		List<String> applied = new ArrayList<>(List.of("BASE"));
		if (overtime > 0) applied.add("OVERTIME_1_5");
		List<String> unsupported = new ArrayList<>();
		if (night) unsupported.add(NIGHT_WORK);
		if (holiday) unsupported.add(HOLIDAY_WORK);
		if (hourly) unsupported.add(WEEKLY_HOLIDAY_PAY);
		List<String> unc = new ArrayList<>();
		if (breakMissing) unc.add(MISSING_BREAK);
		if (missing > 0) unc.add(MISSING_DAYS);
		return new Result(worked, days, overtime, breakApplied, hw, base, ot, applied, unsupported, unc, missing);
	}

	/** 근무 구간이 22:00~06:00(KST)과 겹치는지 */
	static boolean overlapsNight(WorkRecord r) {
		LocalDateTime in = LocalDateTime.ofInstant(r.getClockInAt(), KST);
		LocalDateTime out = LocalDateTime.ofInstant(r.getClockOutAt(), KST);
		for (LocalDate d = in.toLocalDate().minusDays(1); !d.isAfter(out.toLocalDate()); d = d.plusDays(1)) {
			LocalDateTime nightStart = d.atTime(LocalTime.of(22, 0));
			LocalDateTime nightEnd = d.plusDays(1).atTime(LocalTime.of(6, 0));
			if (in.isBefore(nightEnd) && out.isAfter(nightStart)) return true;
		}
		return false;
	}
}
