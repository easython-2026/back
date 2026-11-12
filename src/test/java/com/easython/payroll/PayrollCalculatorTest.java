package com.easython.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.easython.attendance.WorkRecord;
import com.easython.contract.Contract;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 정답값이 있는 계산 검증(손계산). DB/Spring 없음. */
class PayrollCalculatorTest {
	static final ZoneId KST = ZoneId.of("Asia/Seoul");

	static WorkRecord rec(String date, String in, String out, Integer breakMin) {
		WorkRecord r = new WorkRecord();
		LocalDate d = LocalDate.parse(date);
		r.setWorkDate(d);
		r.setClockInAt(d.atTime(LocalTime.parse(in)).atZone(KST).toInstant());
		if (out != null) r.setClockOutAt(d.atTime(LocalTime.parse(out)).atZone(KST).toInstant().plusSeconds(out.compareTo(in) < 0 ? 86400 : 0));
		r.setBreakMinutes(breakMin);
		return r;
	}

	static Contract hourly(int wage, Integer contractBreak) {
		Contract c = new Contract();
		c.setWageType("HOURLY");
		c.setWageAmount(wage);
		c.setBreakMinutesPerDay(contractBreak);
		c.setWorkDays("MON,TUE,WED,THU,FRI,SAT");
		return c;
	}

	@Test
	void hourlyWithOvertime() {
		// 2026-11-02(월) 08~17 휴게60 = 480분 / 11-03(화) 08~19 휴게60 = 600분(연장120) / 11-04(수) 08~17 휴게 미입력 = 540분(연장60)
		var r = PayrollCalculator.calculate(hourly(10_000, null), List.of(
				rec("2026-11-02", "08:00", "17:00", 60), rec("2026-11-03", "08:00", "19:00", 60), rec("2026-11-04", "08:00", "17:00", null)), null);
		assertEquals(1620, r.workedMinutes());          // 27시간
		assertEquals(180, r.overtimeMinutes());         // 3시간
		assertEquals(270_000, r.basePay());             // 10,000 x 27h
		assertEquals(15_000, r.overtimePay());          // 10,000 x 0.5 x 3h
		assertEquals(285_000, r.expected());
		assertEquals(List.of("BASE", "OVERTIME_1_5"), r.appliedRules());
		assertTrue(r.uncertainties().contains(PayrollCalculator.MISSING_BREAK)); // 마지막 날 휴게 정보 없음
		assertTrue(r.unsupportedRules().contains(PayrollCalculator.WEEKLY_HOLIDAY_PAY));
		assertFalse(r.unsupportedRules().contains(PayrollCalculator.HOLIDAY_WORK));
	}

	@Test
	void contractBreakIsUsedWhenRecordHasNone() {
		var r = PayrollCalculator.calculate(hourly(10_000, 60), List.of(rec("2026-11-02", "08:00", "18:00", null)), null);
		assertEquals(540, r.workedMinutes());
		assertFalse(r.uncertainties().contains(PayrollCalculator.MISSING_BREAK));
		assertEquals(60, r.breakApplied());
	}

	@Test
	void monthlyWageIsFixedPlusOvertimePremiumFromEquivalentHourly() {
		Contract c = hourly(0, 60);
		c.setWageType("MONTHLY");
		c.setWageAmount(2_000_000);
		// 환산 통상시급 10,000원 가정: 일 10시간(휴게60→9시간, 연장 60분) → 가산 10,000 x 0.5 x 1h = 5,000
		var r = PayrollCalculator.calculate(c, List.of(rec("2026-11-02", "08:00", "19:00", 60)), 10_000);
		assertEquals(2_000_000, r.basePay());
		assertEquals(120, r.overtimeMinutes());
		assertEquals(10_000, r.overtimePay());
		assertFalse(r.unsupportedRules().contains(PayrollCalculator.WEEKLY_HOLIDAY_PAY)); // 월급에 포함
	}

	@Test
	void flagsNightHolidayAndMissingClockOut() {
		var night = PayrollCalculator.calculate(hourly(10_000, 0), List.of(rec("2026-11-02", "20:00", "23:30", 0)), null);
		assertTrue(night.unsupportedRules().contains(PayrollCalculator.NIGHT_WORK));

		var sunday = PayrollCalculator.calculate(hourly(10_000, 0), List.of(rec("2026-11-01", "08:00", "12:00", 0)), null); // 2026-11-01은 일요일
		assertTrue(sunday.unsupportedRules().contains(PayrollCalculator.HOLIDAY_WORK));

		var missing = PayrollCalculator.calculate(hourly(10_000, 0), List.of(rec("2026-11-02", "08:00", null, null), rec("2026-11-03", "08:00", "17:00", 60)), null);
		assertEquals(1, missing.missingDays());
		assertEquals(480, missing.workedMinutes());
		assertTrue(missing.uncertainties().contains(PayrollCalculator.MISSING_DAYS));
	}

	@Test
	void statusThresholds() {
		assertEquals("MATCH", ComparisonService.status(0));
		assertEquals("MATCH", ComparisonService.status(-50_000));   // 더 받음
		assertEquals("UNCERTAIN", ComparisonService.status(9_999)); // 근사 오차 구간
		assertEquals("MISMATCH", ComparisonService.status(10_000));
		assertEquals("MISMATCH", ComparisonService.status(920_000));
	}
}
