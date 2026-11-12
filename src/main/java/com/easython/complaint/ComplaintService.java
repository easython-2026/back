package com.easython.complaint;

import com.easython.common.ApiException;
import com.easython.common.ErrorCode;
import com.easython.common.PageInfo;
import com.easython.common.Times;
import com.easython.complaint.ComplaintDtos.*;
import com.easython.contract.ContractRepository;
import com.easython.notification.NotificationService;
import com.easython.payroll.ComparisonService;
import com.easython.payroll.PayComparison;
import com.easython.payroll.PayComparisonRepository;
import com.easython.report.EvidenceService;
import com.easython.report.EvidenceService.EvidenceView;
import com.easython.user.User;
import com.easython.user.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Service
@RequiredArgsConstructor
public class ComplaintService {
	/** 생성/번역이 이 시간을 넘기면(서버 재시작 등) 멈춘 것으로 보고 실패 처리 */
	private static final java.time.Duration STALE = java.time.Duration.ofMinutes(5);

	private final ComplaintRepository complaints;
	private final PayComparisonRepository comparisons;
	private final ComparisonService comparisonService;
	private final ContractRepository contracts;
	private final UserRepository users;
	private final EvidenceService evidence;
	private final ComplaintPdf pdf;
	private final MessageSource messages;
	private final JsonMapper json;

	/** 생성 결과 */
	public record Created(String id, String language) {}

	// ---------------------------------------------------------------- 생성

	/** 급여 비교(불일치)에서 진정서 생성을 시작한다. 한국어 초안·번역은 커밋 후 ComplaintGenerator 가 비동기로 만든다. */
	@Transactional
	public String create(String userId, CreateRequest r) {
		PayComparison cmp = comparisons.findByIdAndUserId(r.comparisonId(), userId)
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "error.comparison.notfound"));
		if ("MATCH".equals(cmp.getStatus())) throw new ApiException(ErrorCode.NOT_MISMATCH);
		User u = users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
		if (!u.hasBusiness()) throw new ApiException(ErrorCode.BUSINESS_INFO_REQUIRED);

		YearMonth ym = YearMonth.parse(cmp.getMonth());
		LocalDate start = date(r.periodStart(), ym.atDay(1));
		LocalDate end = date(r.periodEnd(), ym.atEndOfMonth());
		if (start.isAfter(end)) throw new ApiException(ErrorCode.VALIDATION_ERROR);

		Complaint c = new Complaint();
		c.setUserId(userId);
		c.setComparisonId(cmp.getId());
		c.setPeriodStart(start);
		c.setPeriodEnd(end);
		c.setRespondentName(u.getBusinessName());
		c.setRespondentAddress(u.getBusinessAddress());
		c.setRespondentOwner(u.getBusinessOwnerName());
		c.setLanguage(u.getLanguage());

		// 대상 기간의 증거 + 비교에 쓰인 계약서·명세서를 자동 첨부(남의 것·없는 것은 owned() 가 걸러낸다)
		Set<String> ids = new LinkedHashSet<>();
		evidence.between(userId, "ko", start, end).forEach(e -> ids.add(e.id()));
		ids.add("CONTRACT-" + cmp.getContractId());
		ids.add("PAYSLIP-" + cmp.getStatementId());
		c.setEvidenceIds(String.join(",", evidence.owned(userId, "ko", ids).stream().map(EvidenceView::id).toList()));
		complaints.save(c);
		return c.getId();
	}

	/** 한국어 초안(코드 템플릿)을 만들어 저장하고 번역 대상 텍스트를 돌려준다. (ComplaintGenerator 가 호출) */
	@Transactional
	public Created generateKo(String id) {
		Complaint c = complaints.findById(id).orElse(null);
		if (c == null) return null;
		var cmp = comparisons.findByIdAndUserId(c.getComparisonId(), c.getUserId()).orElseThrow();
		var detail = comparisonService.detail(c.getUserId(), cmp.getId(), "ko");
		var contract = contracts.findById(cmp.getContractId()).orElseThrow();
		User u = users.findById(c.getUserId()).orElseThrow();

		var in = new ComplaintDraftBuilder.Input(u.getName(), c.getRespondentName(), c.getRespondentAddress(), c.getRespondentOwner(),
				c.getPeriodStart(), c.getPeriodEnd(), contract.getWageType(), contract.getWageAmount(), contract.getWorkStartTime(), contract.getWorkEndTime(),
				contract.workDayList(), contract.getPayday(), contract.getMealDeduction(), detail.appliedConditions().workDays(),
				detail.appliedConditions().workedMinutes(), detail.expectedAmount(), detail.actualAmount(), detail.differenceAmount(),
				detail.items().stream().map(i -> new ComplaintDraftBuilder.Item(i.type(), i.label(), i.expected(), i.actual(), i.difference())).toList(),
				detail.uncertainties().stream().map(x -> x.message()).toList(), c.evidenceIdList().size());
		var out = ComplaintDraftBuilder.build(in);
		c.setTitleKo(out.title());
		c.setBodyKo(out.body());
		c.setSummaryKo(out.summary());
		c.setClaimsJson(json.writeValueAsString(out.claims()));
		c.setUnpaidAmount(out.unpaidAmount());
		c.setStatus(Complaint.Phase.DONE); // 한국어 초안은 이 시점에 완성. 번역은 translationStatus 로 따로 추적
		touch(c);
		return new Created(c.getId(), c.getLanguage());
	}

	/** 번역 결과 반영(summary 는 최초 생성 때만 포함). */
	@Transactional
	public void applyTranslation(String id, String title, String body, String summary) {
		complaints.findById(id).ifPresent(c -> {
			c.setTranslatedTitle(title);
			c.setTranslatedBody(body);
			if (summary != null) c.setSummaryTranslated(summary);
			c.setTranslationStatus(Complaint.Phase.DONE);
			c.setFailReason(null);
			touch(c);
		});
	}

	/** 번역만 실패: 한국어 초안은 쓸 수 있으므로 status 는 유지하고 재번역하도록 안내. */
	@Transactional
	public void translationFailed(String id, String reason) {
		complaints.findById(id).ifPresent(c -> {
			c.setTranslationStatus(Complaint.Phase.FAILED);
			c.setFailReason(reason);
			touch(c);
		});
	}

	@Transactional
	public void generationFailed(String id, String reason) {
		complaints.findById(id).ifPresent(c -> {
			c.setStatus(Complaint.Phase.FAILED);
			c.setTranslationStatus(Complaint.Phase.FAILED);
			c.setFailReason(reason);
			touch(c);
		});
	}

	@Transactional(readOnly = true)
	public Complaint get(String id) {
		return complaints.findById(id).orElse(null);
	}

	// ---------------------------------------------------------------- 조회

	@Transactional
	public Detail detail(String userId, String id, String language) {
		Complaint c = find(userId, id);
		expireIfStale(c);
		return view(c, language);
	}

	@Transactional(readOnly = true)
	public ListResponse list(String userId, Integer page, Integer size) {
		var p = complaints.findByUserIdOrderByCreatedAtDesc(userId, PageInfo.request(page, size));
		return new ListResponse(p.getContent().stream().map(c -> new ListItem(c.getId(), c.getStatus().name(), c.isReviewed(), c.getPeriodStart(),
				c.getPeriodEnd(), c.getUnpaidAmount(), Times.kst(c.getCreatedAt()))).toList(), PageInfo.of(p));
	}

	// ---------------------------------------------------------------- 수정 / 확정 / 증거

	/** 보낸 필드만 수정. 내용을 바꾸면 reviewed=false 로 돌아간다. 재번역은 커밋 후 컨트롤러가 시작. */
	@Transactional
	public Detail patch(String userId, String language, String id, PatchRequest r) {
		Complaint c = find(userId, id);
		expireIfStale(c);
		if (c.getStatus() == Complaint.Phase.PROCESSING) throw new ApiException(ErrorCode.CONFLICT, "error.complaint.processing");
		if (r.draft() == null && r.periodStart() == null && r.periodEnd() == null && r.respondent() == null && r.retranslate() == null && r.confirm() == null) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR);
		}
		boolean modified = false;
		if (r.draft() != null) {
			var d = r.draft();
			if (d.title() != null) c.setTitleKo(d.title());
			if (d.body() != null) c.setBodyKo(d.body());
			if (d.unpaidAmount() != null) c.setUnpaidAmount(d.unpaidAmount());
			if (d.claims() != null) c.setClaimsJson(json.writeValueAsString(d.claims()));
			modified = true;
		}
		if (r.periodStart() != null || r.periodEnd() != null) {
			LocalDate s = date(r.periodStart(), c.getPeriodStart());
			LocalDate e = date(r.periodEnd(), c.getPeriodEnd());
			if (s.isAfter(e)) throw new ApiException(ErrorCode.VALIDATION_ERROR);
			c.setPeriodStart(s);
			c.setPeriodEnd(e);
			modified = true;
		}
		if (r.respondent() != null) {
			var p = r.respondent();
			if (p.name() != null) c.setRespondentName(p.name());
			if (p.address() != null) c.setRespondentAddress(p.address());
			if (p.ownerName() != null) c.setRespondentOwner(p.ownerName());
			modified = true;
		}
		if (modified) c.setReviewed(false);
		if (Boolean.TRUE.equals(r.retranslate())) {
			c.setTranslationStatus(Complaint.Phase.PROCESSING);
			c.setLanguage(users.findById(userId).map(User::getLanguage).orElse(c.getLanguage())); // 현재 선택 언어로 다시 번역
		}
		if (Boolean.TRUE.equals(r.confirm())) {
			if (c.getTranslationStatus() == Complaint.Phase.PROCESSING) throw new ApiException(ErrorCode.CONFLICT, "error.complaint.processing");
			c.setReviewed(true);
		} else if (Boolean.FALSE.equals(r.confirm())) {
			c.setReviewed(false);
		}
		touch(c);
		return view(c, language);
	}

	/** 첨부 증거 전체 교체. 모르는 id·남의 증거가 섞여 있으면 404. */
	@Transactional
	public EvidencesResponse putEvidences(String userId, String language, String id, List<String> evidenceIds) {
		Complaint c = find(userId, id);
		List<String> distinct = evidenceIds.stream().distinct().toList();
		var owned = evidence.owned(userId, language, distinct);
		if (owned.size() != distinct.size()) throw new ApiException(ErrorCode.NOT_FOUND, "error.complaint.evidence.notfound");
		c.setEvidenceIds(String.join(",", distinct));
		c.setReviewed(false);
		touch(c);
		return new EvidencesResponse(owned.stream().map(ComplaintService::brief).toList());
	}

	// ---------------------------------------------------------------- PDF

	@Transactional
	public byte[] pdf(String userId, String id, String language) {
		Complaint c = find(userId, id);
		expireIfStale(c);
		if (c.getStatus() == Complaint.Phase.PROCESSING || c.getTranslationStatus() == Complaint.Phase.PROCESSING) {
			throw new ApiException(ErrorCode.CONFLICT, "error.complaint.generating");
		}
		if (!c.isReviewed()) throw new ApiException(ErrorCode.NOT_REVIEWED);
		return pdf.render(view(c, language), evidence.owned(userId, "ko", c.evidenceIdList()));
	}

	// ---------------------------------------------------------------- 표시

	private Detail view(Complaint c, String language) {
		Locale locale = Locale.forLanguageTag(language);
		User u = users.findById(c.getUserId()).orElseThrow();
		boolean processing = c.getStatus() == Complaint.Phase.PROCESSING;
		List<Claim> claims = c.getClaimsJson() == null ? List.of() : json.readValue(c.getClaimsJson(), new TypeReference<List<Claim>>() {});
		Draft draft = null;
		Summary summary = null;
		if (!processing && c.getTitleKo() != null) {
			TranslatedDraft tr = c.getTranslatedBody() == null ? null : new TranslatedDraft(c.getLanguage(), c.getTranslatedTitle(), c.getTranslatedBody());
			draft = new Draft(new KoDraft(c.getTitleKo(), c.getBodyKo(), c.getUnpaidAmount(), claims), tr);
			summary = new Summary(c.getSummaryKo(), c.getSummaryTranslated());
		}
		List<EvidenceBrief> evs = evidence.owned(c.getUserId(), language, c.evidenceIdList()).stream().map(ComplaintService::brief).toList();
		return new Detail(c.getId(), c.getStatus().name(), c.getTranslationStatus().name(), c.getFailReason(), c.getComparisonId(), c.isReviewed(),
				c.getPeriodStart(), c.getPeriodEnd(), new Party(u.getName()), new Respondent(c.getRespondentName(), c.getRespondentAddress(), c.getRespondentOwner()),
				summary, draft, evs, guide(locale), messages.getMessage("disclaimer.default", null, locale), Times.kst(c.getCreatedAt()), Times.kst(c.getUpdatedAt()));
	}

	/** 공식 진정 절차 안내(실제 접수 자동 제출은 하지 않는다). */
	private OfficialGuide guide(Locale l) {
		List<String> steps = new ArrayList<>();
		for (int i = 1; i <= 4; i++) steps.add(messages.getMessage("guide.step." + i, null, l));
		return new OfficialGuide(messages.getMessage("guide.agency", null, l), steps,
				List.of(new Contact(messages.getMessage("guide.contact.1", null, l), "1350"), new Contact(messages.getMessage("guide.contact.2", null, l), "1577-0071")));
	}

	private static EvidenceBrief brief(EvidenceView e) {
		return new EvidenceBrief(e.id(), e.type(), e.title(), e.occurredAt());
	}

	private void expireIfStale(Complaint c) {
		if (c.getUpdatedAt().isBefore(Instant.now().minus(STALE))) {
			if (c.getStatus() == Complaint.Phase.PROCESSING) {
				c.setStatus(Complaint.Phase.FAILED);
				c.setTranslationStatus(Complaint.Phase.FAILED);
				c.setFailReason("TIMEOUT");
			} else if (c.getTranslationStatus() == Complaint.Phase.PROCESSING) {
				c.setTranslationStatus(Complaint.Phase.FAILED);
				c.setFailReason("TIMEOUT");
			}
		}
	}

	private Complaint find(String userId, String id) {
		return complaints.findByIdAndUserId(id, userId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "error.complaint.notfound"));
	}

	private static void touch(Complaint c) {
		c.setUpdatedAt(Instant.now());
	}

	private static LocalDate date(String v, LocalDate fallback) {
		if (v == null || v.isBlank()) return fallback;
		try {
			return LocalDate.parse(v);
		} catch (java.time.format.DateTimeParseException e) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR);
		}
	}
}
