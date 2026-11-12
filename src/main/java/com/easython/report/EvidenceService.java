package com.easython.report;

import com.easython.attendance.AttendanceEvent;
import com.easython.attendance.AttendanceEventRepository;
import com.easython.attendance.WorkRecord;
import com.easython.attendance.WorkRecordRepository;
import com.easython.common.ApiException;
import com.easython.common.ErrorCode;
import com.easython.common.PageInfo;
import com.easython.common.Times;
import com.easython.contract.ContractRepository;
import com.easython.payroll.PayStatementRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.PageImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 증거 목록. 별도 테이블 없이 계약서·근무기록(사진/GPS/서버 타임스탬프)·명세서에서 그때그때 만든다.
 * 증거 id 는 "<TYPE>-<원본id>" 라서 진정서 연결·상담 공유 때 소유자 확인에 그대로 쓴다.
 * ponytail: 사용자 전체 데이터를 읽어 메모리에서 거르고 자른다(월 수백 건 규모). 커지면 DB 쿼리로 내릴 것.
 */
@Service
@RequiredArgsConstructor
public class EvidenceService {
	public static final Set<String> TYPES = Set.of("CONTRACT", "ATTENDANCE_PHOTO", "GPS_LOG", "TIMESTAMP", "PAYSLIP", "MANUAL_RECORD");

	public record EvidenceView(String id, String type, String title, OffsetDateTime occurredAt, String refType, String refId, String hash) {}

	public record ListResponse(List<EvidenceView> evidences, PageInfo pageInfo) {}

	private record Ev(EvidenceView view, Instant at) {}

	private final ContractRepository contracts;
	private final PayStatementRepository statements;
	private final WorkRecordRepository records;
	private final AttendanceEventRepository events;
	private final MessageSource messages;
	private final com.easython.storage.FileStorage storage;

	@Transactional(readOnly = true)
	public ListResponse list(String userId, String language, String month, String from, String to, String type, Integer page, Integer size) {
		var pageable = PageInfo.request(page, size);
		if (type != null && !TYPES.contains(type)) throw new ApiException(ErrorCode.VALIDATION_ERROR);
		Predicate<Ev> filter = e -> type == null || e.view().type().equals(type);
		try {
			if (month != null && !month.isBlank()) {
				YearMonth ym = YearMonth.parse(month);
				filter = filter.and(inRange(ym.atDay(1), ym.atEndOfMonth()));
			} else if ((from != null && !from.isBlank()) || (to != null && !to.isBlank())) {
				LocalDate f = from == null || from.isBlank() ? LocalDate.MIN : LocalDate.parse(from);
				LocalDate t = to == null || to.isBlank() ? LocalDate.MAX : LocalDate.parse(to);
				filter = filter.and(inRange(f, t));
			}
		} catch (java.time.format.DateTimeParseException e) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR);
		}
		List<EvidenceView> all = all(userId, Locale.forLanguageTag(language)).stream().filter(filter).map(Ev::view).toList();
		int start = (int) Math.min(pageable.getOffset(), all.size());
		var slice = all.subList(start, Math.min(start + pageable.getPageSize(), all.size()));
		return new ListResponse(slice, PageInfo.of(new PageImpl<>(slice, pageable, all.size())));
	}

	@Transactional(readOnly = true)
	public int count(String userId, YearMonth ym) {
		return (int) all(userId, Locale.KOREAN).stream().filter(inRange(ym.atDay(1), ym.atEndOfMonth())).count();
	}

	/** 기간(KST 날짜, 양끝 포함) 안의 모든 증거. 진정서 자동 첨부용. */
	@Transactional(readOnly = true)
	public List<EvidenceView> between(String userId, String language, LocalDate from, LocalDate to) {
		return all(userId, Locale.forLanguageTag(language)).stream().filter(inRange(from, to)).map(Ev::view).toList();
	}

	/** 요청한 증거 id 중 본인 소유로 존재하는 것만(진정서 연결·상담 공유용). 모르는/남의 id 는 빠진다. */
	@Transactional(readOnly = true)
	public List<EvidenceView> owned(String userId, String language, Collection<String> ids) {
		Set<String> want = Set.copyOf(ids);
		return all(userId, Locale.forLanguageTag(language)).stream().map(Ev::view).filter(v -> want.contains(v.id())).toList();
	}

	/** 원본 파일이 있는 증거(사진·계약서·명세서)의 서명 URL. 없거나 남의 것이면 null. */
	@Transactional(readOnly = true)
	public String fileUrl(String userId, String evidenceId) {
		int dash = evidenceId.indexOf('-');
		if (dash < 0) return null;
		String type = evidenceId.substring(0, dash), id = evidenceId.substring(dash + 1);
		String key = switch (type) {
			case "CONTRACT" -> contracts.findByIdAndUserId(id, userId).map(c -> c.getFileKey()).orElse(null);
			case "PAYSLIP" -> statements.findByIdAndUserId(id, userId).map(s -> s.getFileKey()).orElse(null);
			case "ATTENDANCE_PHOTO" -> events.findById(id)
					.filter(e -> records.findByIdAndUserId(e.getRecordId(), userId).isPresent()).map(e -> e.getPhotoKey()).orElse(null);
			default -> null;
		};
		return key == null ? null : storage.signedUrl(key, java.time.Duration.ofMinutes(30)).url();
	}

	// ---------------------------------------------------------------- 수집

	private List<Ev> all(String userId, Locale locale) {
		List<Ev> out = new ArrayList<>();
		contracts.findByUserId(userId).stream().filter(c -> c.getFileKey() != null).forEach(c ->
				out.add(ev("CONTRACT", c.getId(), c.getCreatedAt(), "contract", c.getId(), c.getFileHash(), "ev.title.CONTRACT", locale, null)));
		statements.findByUserIdAndConfirmedTrue(userId).forEach(s ->
				out.add(ev("PAYSLIP", s.getId(), s.getCreatedAt(), "statement", s.getId(), s.getFileHash(), "ev.title.PAYSLIP", locale, null)));

		List<WorkRecord> recs = records.findByUserId(userId);
		for (WorkRecord r : recs) {
			if (r.getSource() == WorkRecord.Source.MANUAL) {
				out.add(ev("MANUAL_RECORD", r.getId(), r.getClockInAt(), "attendance_record", r.getId(), null, "ev.title.MANUAL_RECORD", locale, null));
			}
		}
		if (!recs.isEmpty()) {
			var byId = recs.stream().collect(java.util.stream.Collectors.toMap(WorkRecord::getId, x -> x));
			for (AttendanceEvent e : events.findByRecordIdIn(byId.keySet())) {
				String dir = e.getType() == AttendanceEvent.Type.IN ? "IN" : "OUT";
				String ref = e.getRecordId();
				if (e.getPhotoKey() != null) out.add(ev("ATTENDANCE_PHOTO", e.getId(), e.getServerAt(), "attendance_record", ref, e.getPhotoHash(), "ev.title.PHOTO_" + dir, locale, null));
				out.add(ev("GPS_LOG", e.getId(), e.getServerAt(), "attendance_record", ref, null, "ev.title.GPS_" + dir, locale, null));
				out.add(ev("TIMESTAMP", e.getId(), e.getServerAt(), "attendance_record", ref, null, "ev.title.TS_" + dir, locale, null));
			}
		}
		out.sort(Comparator.comparing(Ev::at).reversed().thenComparing(x -> x.view().id()));
		return out;
	}

	private Ev ev(String type, String sourceId, Instant at, String refType, String refId, String hash, String titleKey, Locale locale, Object unused) {
		String title = messages.getMessage(titleKey, new Object[] { Times.kst(at).toLocalDate().toString() }, titleKey, locale);
		return new Ev(new EvidenceView(type + "-" + sourceId, type, title, Times.kst(at), refType, refId, hash), at);
	}

	private static Predicate<Ev> inRange(LocalDate from, LocalDate to) {
		return e -> {
			LocalDate d = Times.kst(e.at()).toLocalDate();
			return !d.isBefore(from) && !d.isAfter(to);
		};
	}
}
