package com.easython.attendance;

import com.easython.attendance.AttendanceDtos.*;
import com.easython.common.ApiException;
import com.easython.common.ErrorCode;
import com.easython.common.Times;
import com.easython.storage.FileStorage;
import com.easython.user.User;
import com.easython.user.UserRepository;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 출퇴근 읽기 쪽: 홈, 주간, 월간, 상세, 확인 필요 항목. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AttendanceQueryService {
	private static final Duration PHOTO_URL_TTL = Duration.ofMinutes(30);

	private final UserRepository users;
	private final WorkRecordRepository records;
	private final AttendanceEventRepository events;
	private final WorkRecordHistoryRepository histories;
	private final FileStorage storage;
	private final AttendanceService attendance;
	private final MessageSource messages;
	private final com.easython.contract.ContractRepository contracts;
	private final Clock clock;

	public TodayResponse today(String userId) {
		Instant now = clock.instant();
		LocalDate today = LocalDate.now(clock);
		User u = users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
		var open = records.findFirstByUserIdAndClockOutAtIsNullAndClockInAtAfterOrderByClockInAtDesc(userId, now.minus(WorkRecord.MAX_SHIFT));
		WorkRecord rec = open.orElseGet(() -> records.findByUserIdAndWorkDate(userId, today).orElse(null));

		String status = open.isPresent() ? "WORKING" : rec != null && rec.getClockOutAt() != null ? "FINISHED" : "NOT_STARTED";
		String next = switch (status) {
			case "WORKING" -> "CLOCK_OUT";
			case "FINISHED" -> null;
			default -> "CLOCK_IN";
		};
		TodayRecord tr = rec == null ? null : new TodayRecord(rec.getId(), Times.kst(rec.getClockInAt()), Times.kst(rec.getClockOutAt()),
				rec.getBreakMinutes(), rec.workedMinutes(now));
		return new TodayResponse(today, status, next, u.hasWorkplace(), tr);
	}

	public WeeklyResponse weekly(String userId, String weekStartParam) {
		Instant now = clock.instant();
		LocalDate start;
		if (weekStartParam == null || weekStartParam.isBlank()) {
			start = LocalDate.now(clock).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
		} else {
			try {
				start = LocalDate.parse(weekStartParam);
			} catch (DateTimeParseException e) {
				throw new ApiException(ErrorCode.VALIDATION_ERROR);
			}
			if (start.getDayOfWeek() != DayOfWeek.MONDAY) throw new ApiException(ErrorCode.VALIDATION_ERROR);
		}
		LocalDate end = start.plusDays(6);
		var list = records.findByUserIdAndWorkDateBetweenOrderByWorkDateAsc(userId, start, end);
		Map<LocalDate, WorkRecord> byDate = list.stream().collect(Collectors.toMap(WorkRecord::getWorkDate, Function.identity()));
		Map<String, Boolean> edited = editedFlags(list);

		List<WeeklyDay> days = new ArrayList<>();
		int total = 0;
		for (int i = 0; i < 7; i++) {
			LocalDate d = start.plusDays(i);
			WorkRecord r = byDate.get(d);
			int worked = r == null ? 0 : r.workedMinutes(now);
			total += worked;
			days.add(new WeeklyDay(d, worked, r == null ? null : r.getId(), r == null ? List.of() : flags(r, edited, now)));
		}
		return new WeeklyResponse(start, end, total, days);
	}

	public MonthlyResponse monthly(String userId, String monthParam) {
		Instant now = clock.instant();
		YearMonth ym = parseMonth(monthParam);
		var list = records.findByUserIdAndWorkDateBetweenOrderByWorkDateAsc(userId, ym.atDay(1), ym.atEndOfMonth());
		Map<String, Boolean> edited = editedFlags(list);
		int worked = 0;
		int breaks = 0;
		List<MonthlyDay> days = new ArrayList<>();
		for (WorkRecord r : list) {
			int w = r.workedMinutes(now);
			worked += w;
			breaks += r.getBreakMinutes() == null ? 0 : r.getBreakMinutes();
			days.add(new MonthlyDay(r.getWorkDate(), r.getId(), Times.kst(r.getClockInAt()), Times.kst(r.getClockOutAt()),
					r.getBreakMinutes(), w, r.getSource().name(), flags(r, edited, now)));
		}
		return new MonthlyResponse(ym.toString(), new MonthlySummary(list.size(), worked, breaks), days);
	}

	public DetailResponse detail(String userId, String recordId) {
		Instant now = clock.instant();
		WorkRecord r = records.findByIdAndUserId(recordId, userId)
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "error.record.notfound"));
		Map<AttendanceEvent.Type, AttendanceEvent> ev = events.findByRecordIdIn(List.of(r.getId())).stream()
				.collect(Collectors.toMap(AttendanceEvent::getType, Function.identity()));
		return new DetailResponse(r.getId(), r.getWorkDate(), r.getSource().name(),
				eventView(ev.get(AttendanceEvent.Type.IN)), eventView(ev.get(AttendanceEvent.Type.OUT)),
				new Current(Times.kst(r.getClockInAt()), Times.kst(r.getClockOutAt()), r.getBreakMinutes(), r.workedMinutes(now)),
				new Original(Times.kst(r.getOriginalClockInAt()), Times.kst(r.getOriginalClockOutAt()), r.getOriginalBreakMinutes()),
				attendance.historyViews(r.getId()));
	}

	public IssuesResponse issues(String userId, String language, String monthParam) {
		Instant now = clock.instant();
		YearMonth ym = monthParam == null || monthParam.isBlank() ? YearMonth.now(clock) : parseMonth(monthParam);
		Locale locale = Locale.forLanguageTag(language);
		List<IssueView> out = new ArrayList<>();
		for (WorkRecord r : records.findByUserIdAndWorkDateBetweenOrderByWorkDateAsc(userId, ym.atDay(1), ym.atEndOfMonth())) {
			if (r.isMissingClockOut(now)) out.add(issue("MISSING_CLOCK_OUT", "issue.missing_clockout", r, locale));
			if (r.getClockOutAt() != null && !r.getClockOutAt().isAfter(r.getClockInAt())) out.add(issue("TIME_ORDER_ERROR", "issue.time_order", r, locale));
			if (r.getClockOutAt() != null && r.getBreakMinutes() == null) out.add(issue("NO_BREAK_INPUT", "issue.no_break", r, locale));
		}
		out.addAll(noRecordIssues(userId, ym, locale));
		out.sort(java.util.Comparator.comparing(IssueView::date));
		return new IssuesResponse(out.size(), out);
	}

	/** 확정 계약서의 근무요일·계약기간 안인데 기록이 없는 지난 날짜(오늘 제외). 계약서가 없으면 판단하지 않는다. */
	private List<IssueView> noRecordIssues(String userId, YearMonth ym, Locale locale) {
		var contract = contracts.findFirstByUserIdAndConfirmedTrueOrderByConfirmedAtDesc(userId).orElse(null);
		if (contract == null || contract.workDayList().isEmpty()) return List.of();
		java.util.Set<LocalDate> recorded = records.findByUserIdAndWorkDateBetweenOrderByWorkDateAsc(userId, ym.atDay(1), ym.atEndOfMonth()).stream()
				.map(WorkRecord::getWorkDate).collect(Collectors.toSet());
		LocalDate last = LocalDate.now(clock).minusDays(1);
		List<IssueView> out = new ArrayList<>();
		for (LocalDate d = ym.atDay(1); !d.isAfter(ym.atEndOfMonth()) && !d.isAfter(last); d = d.plusDays(1)) {
			if (contract.getContractStart() != null && d.isBefore(contract.getContractStart())) continue;
			if (contract.getContractEnd() != null && d.isAfter(contract.getContractEnd())) continue;
			if (!contract.workDayList().contains(d.getDayOfWeek().name().substring(0, 3)) || recorded.contains(d)) continue;
			out.add(new IssueView("NO_RECORD", d, null, messages.getMessage("issue.no_record", new Object[] { d }, locale)));
		}
		return out;
	}

	/**
	 * 참고용 이상 패턴(법적 판단 아님, 규칙 기반): 퇴근 누락 반복(2일 이상), 8시간 이상 근무에 휴게 없음,
	 * 출퇴근 시각을 2시간 이상 크게 고친 날.
	 */
	public AnomaliesResponse anomalies(String userId, String language, String monthParam) {
		Instant now = clock.instant();
		YearMonth ym = monthParam == null || monthParam.isBlank() ? YearMonth.now(clock) : parseMonth(monthParam);
		Locale locale = Locale.forLanguageTag(language);
		var recs = records.findByUserIdAndWorkDateBetweenOrderByWorkDateAsc(userId, ym.atDay(1), ym.atEndOfMonth());
		List<AnomalyPattern> out = new ArrayList<>();

		List<LocalDate> missing = recs.stream().filter(r -> r.isMissingClockOut(now)).map(WorkRecord::getWorkDate).toList();
		if (missing.size() >= 2) out.add(pattern("REPEATED_MISSING", missing, locale));

		List<LocalDate> longNoBreak = recs.stream().filter(r -> r.getClockOutAt() != null
				&& Duration.between(r.getClockInAt(), r.getClockOutAt()).toMinutes() >= 8 * 60
				&& (r.getBreakMinutes() == null || r.getBreakMinutes() == 0)).map(WorkRecord::getWorkDate).toList();
		if (!longNoBreak.isEmpty()) out.add(pattern("LONG_HOURS_NO_BREAK", longNoBreak, locale));

		if (!recs.isEmpty()) {
			Map<String, LocalDate> dateById = recs.stream().collect(Collectors.toMap(WorkRecord::getId, WorkRecord::getWorkDate));
			List<LocalDate> jumps = histories.findByRecordIdIn(dateById.keySet()).stream()
					.filter(h -> h.getField() != WorkRecordHistory.Field.BREAK && h.getBefore() != null && h.getAfter() != null)
					.filter(h -> Math.abs(Duration.between(java.time.OffsetDateTime.parse(h.getBefore()), java.time.OffsetDateTime.parse(h.getAfter())).toMinutes()) >= 120)
					.map(h -> dateById.get(h.getRecordId())).distinct().sorted().toList();
			if (!jumps.isEmpty()) out.add(pattern("TIME_JUMP", jumps, locale));
		}
		return new AnomaliesResponse(out, messages.getMessage("anomaly.disclaimer", null, locale));
	}

	private AnomalyPattern pattern(String type, List<LocalDate> dates, Locale locale) {
		return new AnomalyPattern(type, dates, messages.getMessage("anomaly." + type, new Object[] { String.valueOf(dates.size()) }, locale));
	}

	// ---------------------------------------------------------------- helpers

	private IssueView issue(String type, String key, WorkRecord r, Locale locale) {
		return new IssueView(type, r.getWorkDate(), r.getId(), messages.getMessage(key, new Object[] { r.getWorkDate() }, locale));
	}

	private EventView eventView(AttendanceEvent e) {
		if (e == null) return null;
		PhotoView photo = null;
		if (e.getPhotoKey() != null) {
			var url = storage.signedUrl(e.getPhotoKey(), PHOTO_URL_TTL);
			photo = new PhotoView(url.url(), Times.kst(url.expiresAt()), e.getPhotoHash());
		}
		return new EventView(Times.kst(e.getServerAt()), photo,
				new GpsView(e.getLatitude(), e.getLongitude(), e.getAccuracyMeters(), e.getDistanceMeters(), e.isVerified()));
	}

	/** 수정 이력이 있는 기록(휴게시간 최초 입력은 '수정'으로 치지 않음) */
	private Map<String, Boolean> editedFlags(Collection<WorkRecord> list) {
		if (list.isEmpty()) return Map.of();
		return histories.findByRecordIdIn(list.stream().map(WorkRecord::getId).toList()).stream()
				.filter(WorkRecordHistory::isModification)
				.collect(Collectors.toMap(WorkRecordHistory::getRecordId, h -> true, (a, b) -> true));
	}

	private static List<String> flags(WorkRecord r, Map<String, Boolean> edited, Instant now) {
		List<String> f = new ArrayList<>();
		if (r.isMissingClockOut(now)) f.add("MISSING_CLOCK_OUT");
		if (edited.containsKey(r.getId())) f.add("EDITED");
		if (r.getBreakMinutes() == null) f.add("NO_BREAK");
		return f;
	}

	private static YearMonth parseMonth(String s) {
		try {
			return YearMonth.parse(s);
		} catch (Exception e) { // null 포함
			throw new ApiException(ErrorCode.VALIDATION_ERROR);
		}
	}
}
