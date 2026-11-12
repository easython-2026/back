package com.easython.contract;

import com.easython.contract.ContractDtos.ReviewView;
import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

/**
 * 계약 조건 → 확인 필요 항목(하이라이트). 규칙 기반이며 최종 위법 판정이 아니다.
 * ponytail: 월급제 환산은 소정근로시간 단순 계산(주휴 포함, 월 4.345주). 변형근로·포괄임금 등은 미반영.
 */
@Component
public class ReviewService {
	private static final Pattern MEAL_CLAUSE = Pattern.compile("숙식|식비|숙소|기숙사|급식|공제");
	static final double WEEKS_PER_MONTH = 52.14 / 12;

	private final MessageSource messages;
	private final int minWage;
	private final String minWageYear;

	public ReviewService(MessageSource messages, @Value("${app.payroll.min-wage-hourly}") int minWage,
			@Value("${app.payroll.min-wage-year}") String minWageYear) {
		this.messages = messages;
		this.minWage = minWage;
		this.minWageYear = minWageYear;
	}

	public int minWage() {
		return minWage;
	}

	public List<ReviewView> reviews(Contract c, List<ContractClause> clauses, Locale locale) {
		List<ReviewView> out = new ArrayList<>();
		// 최저임금
		Integer hourly = hourlyWage(c);
		if (hourly != null && hourly < minWage) {
			String key = "HOURLY".equals(c.getWageType()) ? "review.min_wage" : "review.min_wage.monthly";
			out.add(new ReviewView("MIN_WAGE", "CHECK", null, msg(key, locale, won(c.getWageAmount()), won(hourly), won(minWage), minWageYear)));
		}
		// 숙식비 공제
		if (c.getMealDeduction() != null && c.getMealDeduction() > 0) {
			String clauseId = clauses.stream().filter(x -> x.getOriginalText() != null && MEAL_CLAUSE.matcher(x.getOriginalText()).find())
					.map(ContractClause::getId).findFirst().orElse(null);
			out.add(new ReviewView("MEAL_LODGING_DEDUCTION", "CHECK", clauseId, msg("review.meal", locale, won(c.getMealDeduction()))));
		}
		// 휴게시간
		if (c.getBreakMinutesPerDay() == null) {
			out.add(new ReviewView("BREAK_TIME_MISSING", "CHECK", null, msg("review.break_missing", locale)));
		} else {
			Double span = dailySpanHours(c);
			if (span != null && ((span >= 8 && c.getBreakMinutesPerDay() < 60) || (span >= 4 && c.getBreakMinutesPerDay() < 30))) {
				out.add(new ReviewView("BREAK_TIME_MISSING", "CHECK", null,
						msg("review.break_short", locale, String.format("%.1f", span), String.valueOf(c.getBreakMinutesPerDay()))));
			}
		}
		return out;
	}

	/** 시급이면 그 값, 월급이면 소정근로시간(주휴 포함)으로 환산한 통상시급. 계산 불가면 null. */
	public Integer hourlyWage(Contract c) {
		if (c.getWageAmount() == null || c.getWageType() == null) return null;
		if ("HOURLY".equals(c.getWageType())) return c.getWageAmount();
		Double span = dailySpanHours(c);
		int days = c.workDayList().size();
		if (span == null || days == 0) return null;
		double daily = span - (c.getBreakMinutesPerDay() == null ? 0 : c.getBreakMinutesPerDay()) / 60.0;
		if (daily <= 0) return null;
		double weekly = daily * days;
		double paidWeekly = weekly + (weekly >= 15 ? Math.min(weekly / 5, 8) : 0); // 주휴시간
		return (int) Math.round(c.getWageAmount() / (paidWeekly * WEEKS_PER_MONTH));
	}

	private static Double dailySpanHours(Contract c) {
		try {
			if (c.getWorkStartTime() == null || c.getWorkEndTime() == null) return null;
			Duration d = Duration.between(LocalTime.parse(c.getWorkStartTime()), LocalTime.parse(c.getWorkEndTime()));
			if (d.isNegative()) d = d.plusHours(24); // 야간(자정 넘김)
			return d.toMinutes() / 60.0;
		} catch (Exception e) {
			return null;
		}
	}

	private String msg(String key, Locale locale, String... args) {
		return messages.getMessage(key, args, locale);
	}

	private static String won(Integer v) {
		return String.format("%,d", v);
	}
}
