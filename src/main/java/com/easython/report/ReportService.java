package com.easython.report;

import com.easython.attendance.AttendanceDtos.MonthlyDay;
import com.easython.attendance.AttendanceQueryService;
import com.easython.attendance.WorkRecord;
import com.easython.attendance.WorkRecordHistoryRepository;
import com.easython.attendance.WorkRecordRepository;
import com.easython.common.Times;
import com.easython.payroll.ComparisonService;
import com.easython.payroll.PayComparisonRepository;
import com.easython.payroll.PayrollDtos.Uncertainty;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 리포트 탭 한 화면용 월 요약: 근무 + 수정 이력 + 임금 비교 + 증거 건수. */
@Service
@RequiredArgsConstructor
public class ReportService {
	public record Edit(LocalDate date, String field, String before, String after, String reason, OffsetDateTime editedAt) {}

	public record Work(int workDays, int totalWorkedMinutes, int totalBreakMinutes, List<MonthlyDay> days, List<Edit> edits) {}

	public record Wage(String comparisonId, String status, int expectedAmount, int actualAmount, int differenceAmount, List<Uncertainty> extraChecks) {}

	public record MonthlyReport(String month, Work work, Wage wage, int evidenceCount, String disclaimer) {}

	private final AttendanceQueryService attendance;
	private final WorkRecordRepository records;
	private final WorkRecordHistoryRepository histories;
	private final PayComparisonRepository comparisons;
	private final ComparisonService comparisonService;
	private final EvidenceService evidence;
	private final MessageSource messages;

	@Transactional
	public MonthlyReport monthly(String userId, String language, String month) {
		var m = attendance.monthly(userId, month); // month 형식 오류면 여기서 400
		YearMonth ym = YearMonth.parse(month);

		var recs = records.findByUserIdAndWorkDateBetweenOrderByWorkDateAsc(userId, ym.atDay(1), ym.atEndOfMonth());
		Map<String, LocalDate> dateById = recs.stream().collect(Collectors.toMap(WorkRecord::getId, WorkRecord::getWorkDate));
		List<Edit> edits = recs.isEmpty() ? List.of() : histories.findByRecordIdIn(dateById.keySet()).stream()
				.sorted(java.util.Comparator.comparing(com.easython.attendance.WorkRecordHistory::getEditedAt).reversed())
				.map(h -> new Edit(dateById.get(h.getRecordId()), h.getField().name(), h.getBefore(), h.getAfter(), h.getReason(), Times.kst(h.getEditedAt())))
				.toList();

		Wage wage = comparisons.findFirstByUserIdAndMonthOrderByCreatedAtDesc(userId, month).map(c -> {
			var detail = comparisonService.detail(userId, c.getId(), language);
			return new Wage(c.getId(), c.getStatus(), c.getExpectedAmount(), c.getActualAmount(), c.getDifferenceAmount(), detail.uncertainties());
		}).orElse(null);

		return new MonthlyReport(month, new Work(m.summary().workDays(), m.summary().totalWorkedMinutes(), m.summary().totalBreakMinutes(), m.days(), edits),
				wage, evidence.count(userId, ym), messages.getMessage("disclaimer.default", null, Locale.forLanguageTag(language)));
	}
}
