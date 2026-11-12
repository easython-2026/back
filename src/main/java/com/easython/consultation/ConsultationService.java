package com.easython.consultation;

import com.easython.ai.AiClient;
import com.easython.common.ApiException;
import com.easython.common.ErrorCode;
import com.easython.common.Languages;
import com.easython.common.PageInfo;
import com.easython.common.Times;
import com.easython.complaint.ComplaintRepository;
import com.easython.consultation.ConsultationDtos.*;
import com.easython.contract.Contract;
import com.easython.contract.ContractRepository;
import com.easython.payroll.ComparisonService;
import com.easython.payroll.PayComparison;
import com.easython.payroll.PayComparisonRepository;
import com.easython.report.EvidenceService;
import com.easython.user.User;
import com.easython.user.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 노동자 쪽 상담: 기관 목록, 신청(공유 동의), 조회, 동의 철회, 번역 메시지. 담당자 쪽은 AdminService. */
@Service
@RequiredArgsConstructor
public class ConsultationService {
	public static final List<String> SCOPES = List.of("SUMMARY", "CONTRACT", "ATTENDANCE", "PAYSLIP", "COMPLAINT_DRAFT");
	static final Set<String> STATUSES = Set.of(Consultation.RECEIVED, Consultation.REVIEWING, Consultation.INFO_REQUESTED, Consultation.REFERRED, Consultation.CLOSED);
	static final String WORKER = "WORKER", STAFF = "STAFF";

	private final ConsultationRepository consultations;
	private final ConsultationEventRepository events;
	private final InfoRequestRepository infoRequests;
	private final ReferralRepository referrals;
	private final ConsultationMessageRepository messages;
	private final AgencyRepository agencies;
	private final PayComparisonRepository comparisons;
	private final ComparisonService comparisonService;
	private final ContractRepository contracts;
	private final ComplaintRepository complaints;
	private final UserRepository users;
	private final EvidenceService evidence;
	private final AiClient ai;

	// ---------------------------------------------------------------- 기관

	@Transactional(readOnly = true)
	public AgenciesResponse agencies(String language) {
		return new AgenciesResponse(agencies.findAll().stream()
				.filter(a -> language == null || language.isBlank() || csv(a.getLanguages()).contains(language))
				.map(a -> new AgencyView(a.getId(), a.getName(), a.getType(), csv(a.getLanguages()), a.getPhone(), a.getAddress())).toList());
	}

	// ---------------------------------------------------------------- 신청

	/** 공유 동의와 함께 상담 신청. 상담용 요약 카드는 커밋 후 ConsultationGenerator 가 비동기로 만든다. */
	@Transactional
	public String create(String workerId, CreateRequest r) {
		Agency ag = agencies.findById(r.agencyId()).orElseThrow(ConsultationService::targetNotFound);
		PayComparison cmp = comparisons.findByIdAndUserId(r.comparisonId(), workerId).orElseThrow(ConsultationService::targetNotFound);

		Set<String> requested = new LinkedHashSet<>(r.consent().sharedScope());
		if (!SCOPES.containsAll(requested)) throw new ApiException(ErrorCode.VALIDATION_ERROR);
		requested.add("SUMMARY"); // 요약은 항상 포함
		List<String> scope = SCOPES.stream().filter(requested::contains).toList();

		String complaintId = null;
		if (scope.contains("COMPLAINT_DRAFT")) {
			if (r.complaintId() == null || r.complaintId().isBlank()) throw new ApiException(ErrorCode.VALIDATION_ERROR);
			complaintId = complaints.findByIdAndUserId(r.complaintId(), workerId).orElseThrow(ConsultationService::targetNotFound).getId();
		}
		List<String> evIds = r.consent().evidenceIds() == null ? List.of() : r.consent().evidenceIds().stream().distinct().toList();
		if (!evIds.isEmpty() && evidence.owned(workerId, "ko", evIds).size() != evIds.size()) throw targetNotFound();
		if (consultations.existsByWorkerIdAndComparisonIdAndAgencyIdAndStatusNotAndRevokedAtIsNull(workerId, cmp.getId(), ag.getId(), Consultation.CLOSED)) {
			throw new ApiException(ErrorCode.DUPLICATED_CONSULTATION);
		}

		Consultation c = new Consultation();
		c.setWorkerId(workerId);
		c.setAgencyId(ag.getId());
		c.setComparisonId(cmp.getId());
		c.setContractId(cmp.getContractId());
		c.setStatementId(cmp.getStatementId());
		c.setComplaintId(complaintId);
		c.setMonth(cmp.getMonth());
		c.setDifferenceAmount(cmp.getDifferenceAmount());
		c.setHeadline(headline(cmp.getMonth(), cmp.getDifferenceAmount()));
		LocalDate contractEnd = contracts.findById(cmp.getContractId()).map(Contract::getContractEnd).orElse(null);
		c.setAiSuggestedUrgency(suggestUrgency(cmp.getDifferenceAmount(), contractEnd, LocalDate.now(Times.KST)));
		c.setSharedScope(String.join(",", scope));
		c.setEvidenceIds(String.join(",", evIds));
		c.setAgreedAt(r.consent().agreedAt().toInstant());
		c.setMemo(r.memo() == null || r.memo().isBlank() ? null : r.memo().trim());
		consultations.save(c);
		addEvent(c.getId(), Consultation.RECEIVED, null);
		return c.getId();
	}

	/** "10월 임금 92만원 미지급 의심" 형식의 한 줄 제목(한국어, 담당자 목록용). */
	static String headline(String month, int difference) {
		int m = YearMonth.parse(month).getMonthValue();
		if (difference <= 0) return m + "월 임금 확인 요청";
		String amount = difference >= 10_000 ? (difference / 10_000) + "만원" : String.format("%,d원", difference);
		return m + "월 임금 " + amount + " 미지급 의심";
	}

	/**
	 * 자동 제안 긴급도(참고용). 차액 100만원 이상 HIGH, 30만원 이상 MEDIUM, 그 외 LOW.
	 * 계약 종료(출국 가능성)가 30일 이내면 한 단계 올린다. 확정은 담당자가 triage 로 한다.
	 */
	static String suggestUrgency(int difference, LocalDate contractEnd, LocalDate today) {
		String level = difference >= 1_000_000 ? "HIGH" : difference >= 300_000 ? "MEDIUM" : "LOW";
		if (contractEnd != null && !contractEnd.isAfter(today.plusDays(30))) level = "LOW".equals(level) ? "MEDIUM" : "HIGH";
		return level;
	}

	// ---------------------------------------------------------------- 요약 카드(ConsultationGenerator 가 호출)

	@Transactional(readOnly = true)
	public Consultation get(String id) {
		return consultations.findById(id).orElse(null);
	}

	@Transactional(readOnly = true)
	public String workerLanguage(Consultation c) {
		return users.findById(c.getWorkerId()).map(User::getLanguage).orElse("ko");
	}

	/** 담당자용 한국어 요약(사건 경위·계산 결과·확인 필요 사항). 금액·날짜는 계산 결과를 그대로 쓴다. */
	@Transactional(readOnly = true)
	public String buildSummaryKo(String id, String memoKo) {
		Consultation c = consultations.findById(id).orElseThrow();
		User w = users.findById(c.getWorkerId()).orElseThrow();
		var d = comparisonService.detail(c.getWorkerId(), c.getComparisonId(), "ko");
		Contract k = contracts.findById(c.getContractId()).orElse(null);
		String langName = Languages.ALL.stream().filter(l -> l.code().equals(w.getLanguage())).map(Languages.Language::nameKo).findFirst().orElse(w.getLanguage());

		List<String> lines = new ArrayList<>();
		lines.add("%s 노동자(사용 언어: %s)의 %s 임금 비교 결과입니다.".formatted(w.getName(), langName, c.getMonth()));
		lines.add("예상 임금 %s원, 실제 지급액 %s원, 차액 %s원(%s).".formatted(won(d.expectedAmount()), won(d.actualAmount()),
				won(Math.max(0, d.differenceAmount())), "MISMATCH".equals(d.status()) ? "불일치" : "UNCERTAIN".equals(d.status()) ? "확인 필요" : "일치"));
		if (k != null && k.getWageAmount() != null) {
			lines.add("계약 조건: %s %s원, 근무시간 %s~%s, 숙식비 공제 %s.".formatted("MONTHLY".equals(k.getWageType()) ? "월급" : "시급", won(k.getWageAmount()),
					k.getWorkStartTime(), k.getWorkEndTime(), k.getMealDeduction() == null ? "없음" : won(k.getMealDeduction()) + "원"));
		}
		int minutes = d.appliedConditions().workedMinutes();
		lines.add("근무 %d일, 총 실근무시간 %d시간 %d분.".formatted(d.appliedConditions().workDays(), minutes / 60, minutes % 60));
		if (!d.uncertainties().isEmpty()) {
			lines.add("확인이 필요한 사항: " + d.uncertainties().stream().map(x -> x.message()).collect(Collectors.joining(" / ")));
		}
		lines.add("공유 범위: " + c.scope().stream().map(ConsultationService::scopeKo).collect(Collectors.joining(", ")) + ".");
		if (memoKo != null) lines.add("상담 희망 내용: " + memoKo);
		return String.join("\n", lines);
	}

	@Transactional
	public void applySummary(String id, String memoKo, String summaryKo, String summaryTranslated) {
		consultations.findById(id).ifPresent(c -> {
			c.setMemoKo(memoKo);
			c.setSummaryKo(summaryKo);
			c.setSummaryTranslated(summaryTranslated);
			c.setSummaryStatus("DONE");
		});
	}

	@Transactional
	public void summaryFailed(String id) {
		consultations.findById(id).ifPresent(c -> c.setSummaryStatus("FAILED"));
	}

	// ---------------------------------------------------------------- 조회 / 철회

	@Transactional(readOnly = true)
	public ListResponse list(String workerId, String status, Integer page, Integer size) {
		if (status != null && !status.isBlank() && !STATUSES.contains(status)) throw new ApiException(ErrorCode.VALIDATION_ERROR);
		var pageable = PageInfo.request(page, size);
		var p = status == null || status.isBlank() ? consultations.findByWorkerIdOrderByCreatedAtDesc(workerId, pageable)
				: consultations.findByWorkerIdAndStatusOrderByCreatedAtDesc(workerId, status, pageable);
		Map<String, String> names = agencies.findAll().stream().collect(Collectors.toMap(Agency::getId, Agency::getName));
		return new ListResponse(p.getContent().stream().map(c -> new ListItem(c.getId(), names.get(c.getAgencyId()), c.getStatus(), !c.revoked(),
				messages.countByConsultationIdAndSenderAndReadByWorkerFalse(c.getId(), STAFF), Times.kst(c.getCreatedAt()), Times.kst(c.getUpdatedAt()))).toList(),
				PageInfo.of(p));
	}

	@Transactional(readOnly = true)
	public Detail detail(String workerId, String id) {
		Consultation c = mine(workerId, id);
		Agency ag = agencies.findById(c.getAgencyId()).orElseThrow();
		var timeline = events.findByConsultationIdOrderByAtAsc(c.getId()).stream()
				.map(e -> new TimelineView(e.getStatus(), Times.kst(e.getAt()), null)).toList(); // 담당자 내부 메모는 노출 안 함
		var infos = infoRequests.findByConsultationIdOrderByCreatedAtAsc(c.getId()).stream()
				.map(i -> new InfoRequestView(i.getId(), i.getMessageTranslated() != null ? i.getMessageTranslated() : i.getMessageKo(), csv(i.getRequestedItems()),
						Times.kst(i.getCreatedAt()))).toList();
		var refs = referrals.findByConsultationIdOrderByCreatedAtAsc(c.getId()).stream()
				.map(f -> new ReferralView(f.getType(), f.getOrganization(), f.getNote())).toList();
		String result = c.closed() && c.isResultSharedWithWorker() ? c.getResultSummaryTranslated() : null;
		return new Detail(c.getId(), new AgencyBrief(ag.getId(), ag.getName()), c.getStatus(), c.getSummaryStatus(),
				"DONE".equals(c.getSummaryStatus()) ? new SummaryView(c.getSummaryKo(), c.getSummaryTranslated()) : null,
				new ConsentView(c.scope(), Times.kst(c.getAgreedAt()), Times.kst(c.getRevokedAt())), timeline, infos, refs, result,
				Times.kst(c.getCreatedAt()), Times.kst(c.getUpdatedAt()));
	}

	/** 동의 철회: 즉시 담당자 열람 불가(403). 이미 전달된 내용의 회수는 기관 정책에 따름. */
	@Transactional
	public RevokeResponse revoke(String workerId, String id) {
		Consultation c = mine(workerId, id);
		if (c.revoked()) throw new ApiException(ErrorCode.CONFLICT, "error.consultation.revoked");
		c.setRevokedAt(Instant.now());
		touch(c);
		return new RevokeResponse(c.getId(), Times.kst(c.getRevokedAt()));
	}

	// ---------------------------------------------------------------- 메시지

	@Transactional
	public MessagesResponse workerMessages(String workerId, String id, String language, Integer page, Integer size) {
		Consultation c = mine(workerId, id);
		messages.findByConsultationIdAndSenderAndReadByWorkerFalse(c.getId(), STAFF).forEach(m -> m.setReadByWorker(true));
		return messageList(c, language, page, size);
	}

	/** 노동자 메시지: 선택 언어로 쓰면 한국어로 번역해 담당자에게 전달(동기). 번역 실패해도 저장(FAILED). */
	@Transactional
	public SendMessageResponse sendWorkerMessage(String workerId, String language, String id, SendMessageRequest r) {
		Consultation c = mine(workerId, id);
		if (c.closed() || c.revoked()) throw new ApiException(ErrorCode.CONFLICT, "error.consultation.blocked");
		List<String> attach = r.attachEvidenceIds() == null ? List.of() : r.attachEvidenceIds().stream().distinct().toList();
		if (!attach.isEmpty() && evidence.owned(workerId, "ko", attach).size() != attach.size()) throw new ApiException(ErrorCode.NOT_FOUND, "error.complaint.evidence.notfound");

		ConsultationMessage m = new ConsultationMessage();
		m.setConsultationId(c.getId());
		m.setSender(WORKER);
		m.setWorkerText(r.text().trim());
		String ko = "ko".equals(language) ? m.getWorkerText() : translateOrNull(m.getWorkerText(), "ko");
		m.setKoText(ko);
		m.setTranslationStatus(ko == null ? "FAILED" : "DONE");
		m.setAttachedEvidenceIds(String.join(",", attach));
		m.setReadByWorker(true);
		messages.save(m);
		if (!attach.isEmpty()) { // 첨부한 증거는 공유 범위에 추가
			Set<String> extra = new LinkedHashSet<>(c.extraEvidenceIdList());
			extra.addAll(attach);
			c.setExtraEvidenceIds(String.join(",", extra));
		}
		touch(c);
		return new SendMessageResponse(m.getId(), WORKER, m.getWorkerText(), m.getKoText(), m.getTranslationStatus(), Times.kst(m.getCreatedAt()));
	}

	/** 노동자·담당자 화면 공통 구조. text=노동자 언어 본문, koText=한국어 본문. */
	MessagesResponse messageList(Consultation c, String titleLanguage, Integer page, Integer size) {
		var p = messages.findByConsultationIdOrderByCreatedAtAsc(c.getId(), PageInfo.request(page, size));
		return new MessagesResponse(p.getContent().stream().map(m -> {
			List<String> ids = csv(m.getAttachedEvidenceIds());
			List<EvidenceRef> refs = ids.isEmpty() ? List.of()
					: evidence.owned(c.getWorkerId(), titleLanguage, ids).stream().map(e -> new EvidenceRef(e.id(), e.title())).toList();
			return new MessageView(m.getId(), m.getSender(), m.getWorkerText() != null ? m.getWorkerText() : m.getKoText(), m.getKoText(),
					m.getTranslationStatus(), refs, Times.kst(m.getCreatedAt()));
		}).toList(), PageInfo.of(p));
	}

	// ---------------------------------------------------------------- 공용 보조

	String translateOrNull(String text, String language) {
		try {
			return ai.translate(List.of(text), language).get(0);
		} catch (Exception e) {
			return null;
		}
	}

	ConsultationEvent addEvent(String consultationId, String status, String note) {
		ConsultationEvent e = new ConsultationEvent();
		e.setConsultationId(consultationId);
		e.setStatus(status);
		e.setNote(note);
		return events.save(e);
	}

	static void touch(Consultation c) {
		c.setUpdatedAt(Instant.now());
	}

	private Consultation mine(String workerId, String id) {
		return consultations.findByIdAndWorkerId(id, workerId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "error.consultation.notfound"));
	}

	static ApiException targetNotFound() {
		return new ApiException(ErrorCode.NOT_FOUND, "error.consultation.target");
	}

	static List<String> csv(String v) {
		return v == null || v.isBlank() ? List.of() : Arrays.asList(v.split(","));
	}

	private static String scopeKo(String s) {
		return switch (s) {
			case "SUMMARY" -> "요약";
			case "CONTRACT" -> "근로계약서";
			case "ATTENDANCE" -> "근무기록";
			case "PAYSLIP" -> "급여명세서";
			case "COMPLAINT_DRAFT" -> "진정서 초안";
			default -> s;
		};
	}

	private static String won(int v) {
		return String.format("%,d", v);
	}
}
