package com.easython.payroll;

import com.easython.attendance.WorkRecord;
import com.easython.attendance.WorkRecordRepository;
import com.easython.common.ApiException;
import com.easython.common.ErrorCode;
import com.easython.common.Times;
import com.easython.contract.Contract;
import com.easython.contract.ContractRepository;
import com.easython.contract.ReviewService;
import com.easython.payroll.PayrollDtos.*;
import com.easython.user.User;
import com.easython.user.UserRepository;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** 확정 계약서 + 월 근무기록 + 확정 명세서(또는 수령액) → 예상 임금과 실제 지급액 비교. */
@Service
@RequiredArgsConstructor
public class ComparisonService {
	/** 차이가 이 금액(원) 미만이면 근사 오차로 보고 '확인 필요'로 표시(체불 확정 안 함). */
	static final int TOLERANCE = 10_000;

	/** DB에 저장하는 언어 중립 결과. 라벨·설명은 조회 시 사용자 언어로 만든다. */
	record StoredItem(String type, int expected, int actual, int difference, String basis) {}

	record Stored(String wageType, Integer wageAmount, int workedMinutes, int workDays, Integer breakApplied, List<String> applied,
			List<String> unsupported, List<String> uncertainties, List<StoredItem> items) {}

	private final PayComparisonRepository comparisons;
	private final PayStatementRepository statements;
	private final PayStatementItemRepository statementItems;
	private final ContractRepository contracts;
	private final WorkRecordRepository records;
	private final UserRepository users;
	private final ReviewService reviews;
	private final MessageSource messages;
	private final JsonMapper json;
	private final com.easython.notification.NotificationService notifications;

	@Transactional
	public ComparisonDetail compare(String userId, String language, ComparisonRequest r) {
		YearMonth ym = StatementService.parseMonth(r.month());
		PayStatement st = statements.findByIdAndUserId(r.statementId(), userId)
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "error.statement.notfound"));
		if (!st.isConfirmed()) throw new ApiException(ErrorCode.STATEMENT_NOT_CONFIRMED);
		Contract c = contracts.findFirstByUserIdAndConfirmedTrueOrderByConfirmedAtDesc(userId)
				.orElseThrow(() -> new ApiException(ErrorCode.CONTRACT_NOT_CONFIRMED));
		List<WorkRecord> recs = records.findByUserIdAndWorkDateBetweenOrderByWorkDateAsc(userId, ym.atDay(1), ym.atEndOfMonth());
		if (recs.isEmpty()) throw new ApiException(ErrorCode.NO_ATTENDANCE_DATA);

		var calc = PayrollCalculator.calculate(c, recs, "MONTHLY".equals(c.getWageType()) ? reviews.hourlyWage(c) : null);
		var stItems = statementItems.findByStatementIdOrderByOrd(st.getId());
		int actual = st.getNetAmount() != null ? st.getNetAmount() : st.getReceivedAmount() != null ? st.getReceivedAmount() : 0;
		int expected = calc.expected();
		int diff = expected - actual;

		List<StoredItem> rows = new ArrayList<>();
		List<String> unc = new ArrayList<>(calc.uncertainties());
		if (!stItems.isEmpty()) { // 항목별 비교는 명세서가 있을 때만
			rows.add(new StoredItem("BASE_PAY", calc.basePay(), sum(stItems, StatementItemType.BASE_PAY), calc.basePay() - sum(stItems, StatementItemType.BASE_PAY), basis(c, calc, false)));
			int actualOt = sum(stItems, StatementItemType.OVERTIME_PAY);
			if (calc.overtimePay() > 0 || actualOt > 0) {
				rows.add(new StoredItem("OVERTIME_PAY", calc.overtimePay(), actualOt, calc.overtimePay() - actualOt, basis(c, calc, true)));
			}
			int expMeal = c.getMealDeduction() == null ? 0 : c.getMealDeduction();
			int actMeal = sum(stItems, StatementItemType.MEAL_DEDUCTION);
			if (expMeal > 0 || actMeal > 0) rows.add(new StoredItem("MEAL_DEDUCTION", expMeal, actMeal, actMeal - expMeal, "")); // 공제는 실제-계약(많이 뗄수록 +)
			if (stItems.stream().anyMatch(x -> x.getType() == StatementItemType.TAX_INSURANCE || x.getType() == StatementItemType.OTHER_DEDUCTION
					|| x.getType() == StatementItemType.LODGING_DEDUCTION)) unc.add("UNCLEAR_DEDUCTION");
		}

		PayComparison pc = new PayComparison();
		pc.setUserId(userId);
		pc.setMonth(r.month());
		pc.setStatementId(st.getId());
		pc.setContractId(c.getId());
		pc.setStatus(status(diff));
		pc.setExpectedAmount(expected);
		pc.setActualAmount(actual);
		pc.setDifferenceAmount(diff);
		pc.setResultJson(json.writeValueAsString(new Stored(c.getWageType(), c.getWageAmount(), calc.workedMinutes(), calc.workDays(),
				calc.breakApplied(), calc.appliedRules(), calc.unsupportedRules(), unc, rows)));
		comparisons.save(pc);
		if ("MISMATCH".equals(pc.getStatus())) {
			notifications.add(userId, com.easython.notification.Notification.MISMATCH, java.util.Map.of("month", pc.getMonth(), "comparisonId", pc.getId()), "MISMATCH:" + pc.getId());
		}
		return view(pc, language);
	}

	@Transactional(readOnly = true)
	public ComparisonDetail detail(String userId, String id, String language) {
		return view(comparisons.findByIdAndUserId(id, userId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "error.comparison.notfound")), language);
	}

	/** 급여비교 탭 진입 시 월 상태: NOT_SUBMITTED / READY / COMPARED */
	@Transactional(readOnly = true)
	public MonthState state(String userId, String month) {
		YearMonth ym = StatementService.parseMonth(month);
		Optional<PayStatement> st = statements.findFirstByUserIdAndMonthAndConfirmedTrueOrderByCreatedAtDesc(userId, month);
		Optional<PayComparison> cp = comparisons.findFirstByUserIdAndMonthOrderByCreatedAtDesc(userId, month);
		String state = cp.isPresent() ? "COMPARED" : st.isPresent() ? "READY" : "NOT_SUBMITTED";
		ComparisonSummary summary = cp.map(x -> new ComparisonSummary(x.getId(), x.getStatus(), x.getExpectedAmount(), x.getActualAmount(), x.getDifferenceAmount())).orElse(null);
		return new MonthState(month, state, st.map(PayStatement::getId).orElse(null), payday(userId, ym), summary);
	}

	/** 귀속 월의 다음 달 지급일(말일 보정). 월급날 미설정이면 확정 계약서의 지급일, 그것도 없으면 null. */
	private LocalDate payday(String userId, YearMonth ym) {
		User u = users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
		Integer day = u.getPayday();
		if (day == null) day = contracts.findFirstByUserIdAndConfirmedTrueOrderByConfirmedAtDesc(userId).map(Contract::getPayday).orElse(null);
		if (day == null) return null;
		YearMonth next = ym.plusMonths(1);
		return next.atDay(Math.min(day, next.lengthOfMonth()));
	}

	// ---------------------------------------------------------------- 표시

	private ComparisonDetail view(PayComparison pc, String language) {
		Locale locale = Locale.forLanguageTag(language);
		Stored s = json.readValue(pc.getResultJson(), Stored.class);
		var applied = s.applied().stream().map(code -> new RuleView(code, msg("payroll.rule." + code, locale, code))).toList();
		var unsupported = s.unsupported().stream().map(code -> msg("payroll.unsupported." + code, locale, code)).toList();
		var cond = new AppliedConditions(s.wageType(), s.wageAmount(), s.workedMinutes(), s.workDays(), s.breakApplied(), applied, unsupported);
		var items = s.items().stream().map(i -> new ComparisonItem(i.type(), msg("payroll.label." + i.type(), locale, i.type()), i.expected(), i.actual(), i.difference(), i.basis())).toList();
		var unc = s.uncertainties().stream().map(code -> new Uncertainty(code, msg("payroll.unc." + code, locale, code))).toList();
		String explainKey = "payroll.explain." + pc.getStatus().toLowerCase();
		String text = messages.getMessage(explainKey, new Object[] { won(pc.getExpectedAmount()), won(pc.getActualAmount()), won(Math.max(0, pc.getDifferenceAmount())) }, locale);
		List<String> actions = switch (pc.getStatus()) {
			case "MISMATCH" -> List.of("CREATE_COMPLAINT", "REQUEST_CONSULTATION");
			case "UNCERTAIN" -> List.of("REQUEST_CONSULTATION");
			default -> List.of();
		};
		return new ComparisonDetail(pc.getId(), pc.getMonth(), pc.getStatus(), pc.getExpectedAmount(), pc.getActualAmount(), pc.getDifferenceAmount(),
				cond, items, unc, new Explanation(language, text), actions, messages.getMessage("disclaimer.default", null, locale), Times.kst(pc.getCreatedAt()));
	}

	private String msg(String key, Locale locale, String fallback) {
		return messages.getMessage(key, null, fallback, locale);
	}

	// ---------------------------------------------------------------- 계산 보조

	/** 차이(예상-실제)로 상태 결정. 0 이하면 일치(더 받았거나 같음), 만 원 미만이면 근사 오차라 확인 필요, 그 이상이면 불일치. */
	static String status(int difference) {
		return difference <= 0 ? "MATCH" : difference < TOLERANCE ? "UNCERTAIN" : "MISMATCH";
	}

	private static int sum(List<PayStatementItem> items, StatementItemType type) {
		return items.stream().filter(x -> x.getType() == type).mapToInt(PayStatementItem::getAmount).sum();
	}

	private static String basis(Contract c, PayrollCalculator.Result calc, boolean overtime) {
		if (calc.hourlyWage() == null) return "MONTHLY".equals(c.getWageType()) && !overtime ? won(c.getWageAmount() == null ? 0 : c.getWageAmount()) : "";
		return overtime ? "%s × 0.5 × %s".formatted(won(calc.hourlyWage()), hours(calc.overtimeMinutes()))
				: "HOURLY".equals(c.getWageType()) ? "%s × %s".formatted(won(calc.hourlyWage()), hours(calc.workedMinutes())) : won(c.getWageAmount() == null ? 0 : c.getWageAmount());
	}

	private static String hours(int minutes) {
		double h = minutes / 60.0;
		return (h == Math.floor(h) ? String.valueOf((long) h) : String.format("%.1f", h)) + "h";
	}

	private static String won(int v) {
		return String.format("%,d", v);
	}
}
