package com.easython.consultation;

import com.easython.attendance.AttendanceQueryService;
import com.easython.attendance.WorkRecordHistoryRepository;
import com.easython.attendance.WorkRecordRepository;
import com.easython.auth.AuthService;
import com.easython.common.ApiException;
import com.easython.common.ErrorCode;
import com.easython.common.PageInfo;
import com.easython.common.Times;
import com.easython.complaint.ComplaintRepository;
import com.easython.consultation.ConsultationDtos.*;
import com.easython.contract.ContractClauseRepository;
import com.easython.contract.ContractRepository;
import com.easython.contract.ContractService;
import com.easython.contract.ReviewService;
import com.easython.notification.Notification;
import com.easython.notification.NotificationService;
import com.easython.payroll.ComparisonService;
import com.easython.report.EvidenceService;
import com.easython.report.EvidenceService.EvidenceView;
import com.easython.user.Role;
import com.easython.user.User;
import com.easython.user.UserRepository;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상담기관 담당자(role=ADMIN) 쪽. 자기 기관에 신청되고 동의가 유지된 사건만 다루며,
 * 사건 상세는 노동자가 동의한 공유 범위의 데이터만 내려가고 열람은 감사 로그로 남는다.
 */
@Service
@RequiredArgsConstructor
public class AdminService {
	/** 허용 상태 전이(종결 CLOSED 는 result 로만) */
	private static final Map<String, Set<String>> TRANSITIONS = Map.of(
			Consultation.RECEIVED, Set.of(Consultation.REVIEWING),
			Consultation.REVIEWING, Set.of(Consultation.INFO_REQUESTED, Consultation.REFERRED),
			Consultation.INFO_REQUESTED, Set.of(Consultation.REVIEWING),
			Consultation.REFERRED, Set.of(Consultation.REVIEWING));
	private static final Map<String, Integer> URGENCY_RANK = Map.of("HIGH", 0, "MEDIUM", 1, "LOW", 2);
	private static final Map<String, Set<String>> SCOPE_TYPES = Map.of(
			"CONTRACT", Set.of("CONTRACT"),
			"ATTENDANCE", Set.of("ATTENDANCE_PHOTO", "GPS_LOG", "TIMESTAMP", "MANUAL_RECORD"),
			"PAYSLIP", Set.of("PAYSLIP"));

	private final ConsultationService service;
	private final ConsultationRepository consultations;
	private final ConsultationEventRepository events;
	private final InfoRequestRepository infoRequests;
	private final ReferralRepository referrals;
	private final ConsultationMessageRepository messages;
	private final AccessLogRepository accessLogs;
	private final AgencyRepository agencies;
	private final UserRepository users;
	private final PasswordEncoder encoder;
	private final AuthService auth;
	private final ComparisonService comparisons;
	private final AttendanceQueryService attendance;
	private final WorkRecordRepository records;
	private final WorkRecordHistoryRepository histories;
	private final ContractRepository contracts;
	private final ContractClauseRepository clauses;
	private final ReviewService reviews;
	private final ComplaintRepository complaints;
	private final EvidenceService evidence;
	private final NotificationService notifications;
	private final MessageSource messageSource;

	// ---------------------------------------------------------------- 로그인 / 담당자

	@Transactional
	public StaffLoginResponse login(StaffLoginRequest r) {
		User u = users.findByEmail(r.email().trim().toLowerCase()).filter(x -> x.getRole() == Role.ADMIN)
				.filter(x -> encoder.matches(r.password(), x.getPasswordHash())).orElseThrow(() -> new ApiException(ErrorCode.INVALID_CREDENTIALS));
		var t = auth.issue(u);
		String agencyName = u.getAgencyId() == null ? null : agencies.findById(u.getAgencyId()).map(Agency::getName).orElse(null);
		return new StaffLoginResponse(t.accessToken(), t.refreshToken(), new StaffView(u.getId(), u.getName(), u.getAgencyId(), agencyName, "ADMIN"));
	}

	@Transactional(readOnly = true)
	public StaffListResponse staffList(String staffId) {
		User me = staff(staffId);
		return new StaffListResponse(users.findAll().stream().filter(u -> u.getRole() == Role.ADMIN && me.getAgencyId().equals(u.getAgencyId()))
				.map(u -> new StaffBrief(u.getId(), u.getName(), ConsultationService.csv(u.getStaffLanguages()))).toList());
	}

	// ---------------------------------------------------------------- 목록 / 대시보드

	@Transactional(readOnly = true)
	public AdminListResponse list(String staffId, String sort, String status, Integer page, Integer size) {
		User me = staff(staffId);
		String s = sort == null || sort.isBlank() ? "recent" : sort;
		if (!s.equals("recent") && !s.equals("urgency")) throw new ApiException(ErrorCode.VALIDATION_ERROR);
		if (status != null && !status.isBlank() && !ConsultationService.STATUSES.contains(status)) throw new ApiException(ErrorCode.VALIDATION_ERROR);
		var pageable = PageInfo.request(page, size);

		Comparator<Consultation> recent = Comparator.comparing(Consultation::getCreatedAt).reversed();
		Comparator<Consultation> order = s.equals("urgency")
				? Comparator.<Consultation>comparingInt(c -> URGENCY_RANK.getOrDefault(c.effectiveUrgency(), 3)).thenComparing(recent)
				: recent;
		// ponytail: 기관 사건 전체를 읽어 메모리에서 정렬·페이지(데모 규모). 커지면 DB 정렬로.
		List<Consultation> all = consultations.findByAgencyIdAndRevokedAtIsNull(me.getAgencyId()).stream()
				.filter(c -> status == null || status.isBlank() || status.equals(c.getStatus())).sorted(order).toList();
		int from = (int) Math.min(pageable.getOffset(), all.size());
		List<Consultation> slice = all.subList(from, Math.min(from + pageable.getPageSize(), all.size()));

		Map<String, User> people = users.findAllById(slice.stream().flatMap(c -> java.util.stream.Stream.of(c.getWorkerId(), c.getAssigneeId()))
				.filter(java.util.Objects::nonNull).collect(Collectors.toSet())).stream().collect(Collectors.toMap(User::getId, u -> u));
		var items = slice.stream().map(c -> {
			User w = people.get(c.getWorkerId());
			User a = c.getAssigneeId() == null ? null : people.get(c.getAssigneeId());
			return new AdminListItem(c.getId(), w.getName(), w.getLanguage(), c.getHeadline(), c.getDifferenceAmount(), c.getStatus(), c.getUrgencyLevel(),
					c.getAiSuggestedUrgency(), a == null ? null : new PersonBrief(a.getId(), a.getName()),
					messages.countByConsultationIdAndSenderAndReadByStaffFalse(c.getId(), ConsultationService.WORKER) > 0, Times.kst(c.getCreatedAt()));
		}).toList();
		return new AdminListResponse(items, PageInfo.of(new PageImpl<>(items, pageable, all.size())));
	}

	@Transactional(readOnly = true)
	public Dashboard dashboard(String staffId) {
		List<Consultation> all = consultations.findByAgencyIdAndRevokedAtIsNull(staff(staffId).getAgencyId());
		long newCount = all.stream().filter(c -> Consultation.RECEIVED.equals(c.getStatus())).count();
		long closed = all.stream().filter(Consultation::closed).count();
		long high = all.stream().filter(c -> !c.closed() && "HIGH".equals(c.effectiveUrgency())).count();
		long unread = all.stream().filter(c -> messages.countByConsultationIdAndSenderAndReadByStaffFalse(c.getId(), ConsultationService.WORKER) > 0).count();
		return new Dashboard(newCount, all.size() - newCount - closed, closed, high, unread);
	}

	// ---------------------------------------------------------------- 상세(공유 범위만)

	@Transactional
	public AdminDetail detail(String staffId, String id) {
		User me = staff(staffId);
		Consultation c = target(me, id, true);
		log(c, me, "VIEW_DETAIL");
		List<String> scope = c.scope();
		User w = users.findById(c.getWorkerId()).orElseThrow();

		var d = comparisons.detail(c.getWorkerId(), c.getComparisonId(), "ko");
		var wage = new WageComparisonView(d.expectedAmount(), d.actualAmount(), d.differenceAmount(), d.items(), d.uncertainties(), d.appliedConditions());

		List<TimelineDay> timeline = scope.contains("ATTENDANCE") ? timeline(c) : null;
		ContractView contract = null;
		if (scope.contains("CONTRACT") && c.getContractId() != null) {
			contract = contracts.findById(c.getContractId()).map(k -> new ContractView(ContractService.terms(k),
					reviews.reviews(k, clauses.findByContractIdOrderByOrd(k.getId()), Locale.KOREAN))).orElse(null);
		}
		ComplaintDraftView draft = null;
		if (scope.contains("COMPLAINT_DRAFT") && c.getComplaintId() != null) {
			draft = complaints.findById(c.getComplaintId()).filter(x -> x.getTitleKo() != null)
					.map(x -> new ComplaintDraftView(x.getTitleKo(), x.getBodyKo(), x.getUnpaidAmount())).orElse(null);
		}
		User assignee = c.getAssigneeId() == null ? null : users.findById(c.getAssigneeId()).orElse(null);
		var infos = infoRequests.findByConsultationIdOrderByCreatedAtAsc(c.getId()).stream()
				.map(i -> new AdminInfoRequest(i.getId(), i.getMessageKo(), ConsultationService.csv(i.getRequestedItems()), Times.kst(i.getCreatedAt()))).toList();
		var refs = referrals.findByConsultationIdOrderByCreatedAtAsc(c.getId()).stream()
				.map(f -> new AdminReferral(f.getId(), f.getType(), f.getOrganization(), f.getContact(), f.getNote(), Times.kst(f.getCreatedAt()))).toList();
		ResultView result = c.closed() ? new ResultView(c.getResultContent(), c.getResultAction(), c.getResultCloseReason(), Times.kst(c.getClosedAt())) : null;
		return new AdminDetail(c.getId(), c.getStatus(), new WorkerBrief(w.getName(), w.getLanguage()), new SummaryOnly(c.getSummaryKo()), timeline, wage,
				contract, sharedEvidences(c), draft, c.getUrgencyLevel(), c.getAiSuggestedUrgency(),
				assignee == null ? null : new PersonBrief(assignee.getId(), assignee.getName()), infos, refs, result,
				new AdminConsent(scope, Times.kst(c.getAgreedAt())), messageSource.getMessage("consult.disclaimer", null, Locale.KOREAN));
	}

	/** 근무 타임라인(공유된 월). 날짜별 출퇴근 + 수정 이력. */
	private List<TimelineDay> timeline(Consultation c) {
		var monthly = attendance.monthly(c.getWorkerId(), c.getMonth());
		YearMonth ym = YearMonth.parse(c.getMonth());
		var recs = records.findByUserIdAndWorkDateBetweenOrderByWorkDateAsc(c.getWorkerId(), ym.atDay(1), ym.atEndOfMonth());
		Map<String, List<Edit>> edits = recs.isEmpty() ? Map.of() : histories.findByRecordIdIn(recs.stream().map(r -> r.getId()).toList()).stream()
				.collect(Collectors.groupingBy(h -> h.getRecordId(), Collectors.mapping(h -> new Edit(h.getField().name(), h.getBefore(), h.getAfter(), h.getReason()), Collectors.toList())));
		return monthly.days().stream().map(x -> new TimelineDay(x.date(), x.clockInAt(), x.clockOutAt(), x.breakMinutes(), x.workedMinutes(), x.source(), x.flags(),
				edits.getOrDefault(x.recordId(), List.of()))).toList();
	}

	/** 공유된 증거: 개별 지정이 있으면 그것만, 없으면 공유 범위의 해당 월 증거 전체 + 메시지로 첨부한 증거. */
	private List<SharedEvidence> sharedEvidences(Consultation c) {
		Set<String> allowed = c.scope().stream().flatMap(s -> SCOPE_TYPES.getOrDefault(s, Set.of()).stream()).collect(Collectors.toSet());
		List<EvidenceView> base;
		if (!c.evidenceIdList().isEmpty()) {
			base = evidence.owned(c.getWorkerId(), "ko", c.evidenceIdList());
		} else {
			YearMonth ym = YearMonth.parse(c.getMonth());
			base = new ArrayList<>(evidence.between(c.getWorkerId(), "ko", ym.atDay(1), ym.atEndOfMonth()));
			base.addAll(evidence.owned(c.getWorkerId(), "ko", List.of("CONTRACT-" + c.getContractId(), "PAYSLIP-" + c.getStatementId())));
		}
		Map<String, EvidenceView> out = new LinkedHashMap<>();
		base.stream().filter(e -> allowed.contains(e.type())).forEach(e -> out.put(e.id(), e));
		evidence.owned(c.getWorkerId(), "ko", c.extraEvidenceIdList()).forEach(e -> out.put(e.id(), e));
		return out.values().stream().map(e -> new SharedEvidence(e.id(), e.type(), e.title(), e.occurredAt(), evidence.fileUrl(c.getWorkerId(), e.id()), e.hash())).toList();
	}

	// ---------------------------------------------------------------- 처리

	@Transactional
	public TriageResponse triage(String staffId, String id, TriageRequest r) {
		Consultation c = target(staff(staffId), id, false);
		c.setUrgencyLevel(r.urgencyLevel());
		c.setTriageNote(r.note());
		ConsultationService.touch(c);
		return new TriageResponse(c.getId(), c.getUrgencyLevel(), Times.kst(c.getUpdatedAt()));
	}

	@Transactional
	public AssigneeResponse assign(String staffId, String id, AssigneeRequest r) {
		User me = staff(staffId);
		Consultation c = target(me, id, false);
		User a = users.findById(r.staffId()).filter(u -> u.getRole() == Role.ADMIN)
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "error.consultation.staffnotfound"));
		if (!me.getAgencyId().equals(a.getAgencyId())) throw new ApiException(ErrorCode.FORBIDDEN);
		c.setAssigneeId(a.getId());
		ConsultationService.touch(c);
		return new AssigneeResponse(c.getId(), new PersonBrief(a.getId(), a.getName()));
	}

	/** 추가 자료 요청: 한국어 → 노동자 언어 번역(실패 시 502), 상태 INFO_REQUESTED, 노동자 알림. */
	@Transactional
	public CreatedResponse requestInfo(String staffId, String id, InfoRequestBody r) {
		Consultation c = target(staff(staffId), id, true);
		if (c.closed()) throw new ApiException(ErrorCode.CONFLICT, "error.consultation.closed");
		String lang = service.workerLanguage(c);
		InfoRequest i = new InfoRequest();
		i.setConsultationId(c.getId());
		i.setMessageKo(r.message().trim());
		i.setMessageTranslated(translateOrFail(i.getMessageKo(), lang));
		i.setRequestedItems(r.requestedItems() == null ? null : String.join(",", r.requestedItems()));
		infoRequests.save(i);
		changeStatus(c, Consultation.INFO_REQUESTED, null);
		notifications.add(c.getWorkerId(), Notification.INFO_REQUEST, Map.of("consultationId", c.getId()), "INFO_REQUEST:" + i.getId());
		return new CreatedResponse(i.getId(), c.getStatus(), Times.kst(i.getCreatedAt()));
	}

	@Transactional
	public CreatedResponse refer(String staffId, String id, ReferralRequest r) {
		Consultation c = target(staff(staffId), id, false);
		if (c.closed()) throw new ApiException(ErrorCode.CONFLICT, "error.consultation.closed");
		Referral f = new Referral();
		f.setConsultationId(c.getId());
		f.setType(r.type());
		f.setOrganization(r.organization().trim());
		f.setContact(r.contact());
		f.setNote(r.note());
		referrals.save(f);
		changeStatus(c, Consultation.REFERRED, null);
		return new CreatedResponse(f.getId(), c.getStatus(), Times.kst(f.getCreatedAt()));
	}

	@Transactional
	public StatusResponse status(String staffId, String id, StatusRequest r) {
		if (!Set.of(Consultation.REVIEWING, Consultation.INFO_REQUESTED, Consultation.REFERRED).contains(r.status())) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR); // 알 수 없는 값, CLOSED(=result 로만)
		}
		Consultation c = target(staff(staffId), id, false);
		if (!TRANSITIONS.getOrDefault(c.getStatus(), Set.of()).contains(r.status())) throw new ApiException(ErrorCode.INVALID_TRANSITION);
		changeStatus(c, r.status(), r.note());
		return new StatusResponse(c.getId(), c.getStatus(), Times.kst(c.getUpdatedAt()));
	}

	/** 결과 기록·종결. 노동자에게 공유하면 요약을 노동자 언어로 번역(실패 시 502). */
	@Transactional
	public ResultResponse close(String staffId, String id, ResultRequest r) {
		Consultation c = target(staff(staffId), id, false);
		if (c.closed()) throw new ApiException(ErrorCode.CONFLICT, "error.consultation.alreadyclosed");
		boolean share = r.shareWithWorker() == null || r.shareWithWorker();
		c.setResultContent(r.content().trim());
		c.setResultAction(r.actionTaken().trim());
		c.setResultCloseReason(r.closeReason().trim());
		c.setResultSharedWithWorker(share);
		if (share) {
			String ko = "상담 내용: " + c.getResultContent() + "\n조치 결과: " + c.getResultAction() + "\n종결 사유: " + c.getResultCloseReason();
			c.setResultSummaryTranslated(translateOrFail(ko, service.workerLanguage(c)));
		}
		c.setClosedAt(Instant.now());
		changeStatus(c, Consultation.CLOSED, null);
		return new ResultResponse(c.getId(), c.getStatus(), Times.kst(c.getClosedAt()));
	}

	// ---------------------------------------------------------------- 메시지

	@Transactional
	public MessagesResponse messages(String staffId, String id, Integer page, Integer size) {
		User me = staff(staffId);
		Consultation c = target(me, id, true);
		log(c, me, "VIEW_MESSAGES");
		messages.findByConsultationIdAndSenderAndReadByStaffFalse(c.getId(), ConsultationService.WORKER).forEach(m -> m.setReadByStaff(true));
		return service.messageList(c, "ko", page, size);
	}

	/** 담당자 답변: 한국어 → 노동자 언어 번역(실패해도 저장, FAILED), 노동자에게 알림. */
	@Transactional
	public SendMessageResponse sendMessage(String staffId, String id, StaffMessageRequest r) {
		Consultation c = target(staff(staffId), id, true);
		if (c.closed()) throw new ApiException(ErrorCode.CONFLICT, "error.consultation.closed");
		String lang = service.workerLanguage(c);
		ConsultationMessage m = new ConsultationMessage();
		m.setConsultationId(c.getId());
		m.setSender(ConsultationService.STAFF);
		m.setKoText(r.text().trim());
		String tr = "ko".equals(lang) ? m.getKoText() : service.translateOrNull(m.getKoText(), lang);
		m.setWorkerText(tr);
		m.setTranslationStatus(tr == null ? "FAILED" : "DONE");
		m.setReadByStaff(true);
		messages.save(m);
		ConsultationService.touch(c);
		notifications.add(c.getWorkerId(), Notification.CONSULTATION_UPDATE, Map.of("consultationId", c.getId(), "status", c.getStatus()), "CONSULT_MSG:" + m.getId());
		return new SendMessageResponse(m.getId(), ConsultationService.STAFF, tr != null ? tr : m.getKoText(), m.getKoText(), m.getTranslationStatus(), Times.kst(m.getCreatedAt()));
	}

	// ---------------------------------------------------------------- 보조

	private void changeStatus(Consultation c, String status, String note) {
		c.setStatus(status);
		ConsultationService.touch(c);
		ConsultationEvent e = service.addEvent(c.getId(), status, note);
		notifications.add(c.getWorkerId(), Notification.CONSULTATION_UPDATE, Map.of("consultationId", c.getId(), "status", status), "CONSULT_STATUS:" + e.getId());
	}

	private String translateOrFail(String ko, String lang) {
		if ("ko".equals(lang)) return ko;
		String t = service.translateOrNull(ko, lang);
		if (t == null) throw new ApiException(ErrorCode.AI_SERVICE_ERROR);
		return t;
	}

	private void log(Consultation c, User staff, String action) {
		AccessLog l = new AccessLog();
		l.setConsultationId(c.getId());
		l.setStaffId(staff.getId());
		l.setAction(action);
		accessLogs.save(l);
	}

	/** 사건 접근 확인: 없음 404 / 다른 기관 403 / (필요 시) 동의 철회 403 CONSENT_NOT_GRANTED */
	private Consultation target(User me, String id, boolean requireConsent) {
		Consultation c = consultations.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "error.consultation.notfound"));
		if (!c.getAgencyId().equals(me.getAgencyId())) throw new ApiException(ErrorCode.FORBIDDEN);
		if (requireConsent && c.revoked()) throw new ApiException(ErrorCode.CONSENT_NOT_GRANTED);
		return c;
	}

	private User staff(String staffId) {
		return users.findById(staffId).filter(u -> u.getRole() == Role.ADMIN && u.getAgencyId() != null)
				.orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN));
	}
}
