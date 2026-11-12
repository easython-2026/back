package com.easython.contract;

import com.easython.ai.AiClient.Clause;
import com.easython.ai.AiClient.ContractExtraction;
import com.easython.ai.AiClient.Terms;
import com.easython.common.ApiException;
import com.easython.common.ErrorCode;
import com.easython.common.Languages;
import com.easython.common.PageInfo;
import com.easython.common.Times;
import com.easython.contract.ContractDtos.*;
import com.easython.storage.FileStorage;
import com.easython.user.User;
import com.easython.user.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class ContractService {
	private static final long MAX_FILE_BYTES = com.easython.common.Uploads.MAX_DOCUMENT_BYTES;
	private static final Duration FILE_URL_TTL = Duration.ofMinutes(30);
	/** 분석이 이 시간을 넘기면(서버 재시작 등) 실패로 처리해 사용자가 직접 입력할 수 있게 한다. */
	private static final Duration ANALYSIS_TIMEOUT = Duration.ofMinutes(5);

	private final ContractRepository contracts;
	private final ContractClauseRepository clauses;
	private final UserRepository users;
	private final ReviewService reviews;
	private final FileStorage storage;
	private final MessageSource messages;
	private final com.easython.consultation.ConsultationShares shares;

	// ---------------------------------------------------------------- 업로드 / 재분석

	/** 파일 저장 + PROCESSING 계약서 생성. 분석 시작은 커밋 후 컨트롤러가 ContractAnalyzer 로 넘긴다. */
	@Transactional
	public String upload(String userId, MultipartFile file, String source) {
		if (file == null || file.isEmpty()) throw new ApiException(ErrorCode.VALIDATION_ERROR);
		if (file.getSize() > MAX_FILE_BYTES) throw new ApiException(ErrorCode.FILE_TOO_LARGE);
		byte[] bytes;
		try {
			bytes = file.getBytes();
		} catch (java.io.IOException e) {
			throw new IllegalStateException(e);
		}
		var typeExt = com.easython.common.Uploads.detectDocument(bytes);
		User u = user(userId);

		Contract c = new Contract();
		c.setUserId(userId);
		c.setLanguage(u.getLanguage());
		c.setSource("CAMERA".equals(source) || "UPLOAD".equals(source) ? source : null);
		c.setFileKey("contract_" + c.getId() + "." + typeExt.ext());
		c.setFileContentType(typeExt.contentType());
		c.setFileHash(FileStorage.sha256(bytes));
		storage.save(c.getFileKey(), bytes);
		contracts.save(c);
		return c.getId();
	}

	/** FAILED/DONE 상태에서 다시 분석(언어 변경 포함). 사용자가 수정한 값은 보존된다. */
	@Transactional
	public String reanalyze(String userId, String contractId, String language) {
		Contract c = find(userId, contractId);
		expireIfStale(c);
		if (c.getStatus() == Contract.Status.PROCESSING) throw new ApiException(ErrorCode.CONFLICT, "error.contract.processing");
		if (language != null && !language.isBlank()) {
			if (!Languages.isSupported(language)) throw new ApiException(ErrorCode.VALIDATION_ERROR);
			c.setLanguage(language);
		} else {
			c.setLanguage(user(userId).getLanguage());
		}
		c.setStatus(Contract.Status.PROCESSING);
		c.setFailReason(null);
		c.setAnalysisStartedAt(Instant.now());
		return c.getId();
	}

	/** 분석 결과 반영(ContractAnalyzer 가 호출). 사용자가 수정한 필드는 건드리지 않는다. */
	@Transactional
	public void applyResult(String contractId, ContractExtraction ex, String ocrJson) {
		Contract c = contracts.findById(contractId).orElse(null);
		if (c == null) return; // 분석 중 삭제됨
		Set<String> edited = c.editedSet();
		Terms t = ex.terms() == null ? new Terms(null, null, null, null, null, null, null, null, null, null, null, null, null) : ex.terms();
		if (!edited.contains("wageType") && validEnum(t.wageType(), "HOURLY", "MONTHLY")) c.setWageType(t.wageType());
		if (!edited.contains("wageAmount")) c.setWageAmount(nonNeg(t.wageAmount()));
		if (!edited.contains("workStartTime")) c.setWorkStartTime(hhmm(t.workStartTime()));
		if (!edited.contains("workEndTime")) c.setWorkEndTime(hhmm(t.workEndTime()));
		if (!edited.contains("breakMinutesPerDay")) c.setBreakMinutesPerDay(nonNeg(t.breakMinutesPerDay()));
		if (!edited.contains("workDays")) c.setWorkDays(t.workDays() == null ? null : String.join(",", t.workDays().stream().filter(d -> validEnum(d, "MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")).toList()));
		if (!edited.contains("mealDeduction")) c.setMealDeduction(nonNeg(t.mealDeduction()));
		if (!edited.contains("payday")) c.setPayday(t.payday() != null && t.payday() >= 1 && t.payday() <= 31 ? t.payday() : null);
		if (!edited.contains("contractStart")) c.setContractStart(date(t.contractStart()));
		if (!edited.contains("contractEnd")) c.setContractEnd(date(t.contractEnd()));
		if (!edited.contains("employerName")) c.setEmployerName(t.employerName());
		if (!edited.contains("employerAddress")) c.setEmployerAddress(t.employerAddress());
		if (!edited.contains("ownerName")) c.setOwnerName(t.ownerName());
		c.setTranslationSummary(ex.summary());
		if (c.getOcrTermsJson() == null) c.setOcrTermsJson(ocrJson); // 최초 추출값만 보관
		replaceClauses(c.getId(), ex.clauses());
		c.setStatus(Contract.Status.DONE);
		c.setFailReason(null);
	}

	@Transactional
	public void markFailed(String contractId, String reason) {
		contracts.findById(contractId).ifPresent(c -> {
			c.setStatus(Contract.Status.FAILED);
			c.setFailReason(reason);
		});
	}

	/** 분석에 필요한 입력(파일 바이트는 호출 측에서 storage.load). */
	@Transactional(readOnly = true)
	public Contract get(String contractId) {
		return contracts.findById(contractId).orElse(null);
	}

	void replaceClauses(String contractId, List<Clause> list) {
		clauses.deleteByContractId(contractId);
		clauses.flush();
		if (list == null) return;
		List<ContractClause> rows = new ArrayList<>();
		for (int i = 0; i < list.size(); i++) {
			Clause c = list.get(i);
			if (c.originalText() == null || c.originalText().isBlank()) continue;
			ContractClause row = new ContractClause();
			row.setContractId(contractId);
			row.setOrd(i);
			row.setOriginalText(c.originalText());
			row.setTranslatedText(c.translatedText());
			if (c.position() != null) {
				row.setPosPage(c.position().page());
				row.setPosX(clamp(c.position().x()));
				row.setPosY(clamp(c.position().y()));
				row.setPosWidth(clamp(c.position().width()));
				row.setPosHeight(clamp(c.position().height()));
			}
			rows.add(row);
		}
		clauses.saveAll(rows);
	}

	// ---------------------------------------------------------------- 조회 / 수정 / 확정 / 삭제

	@Transactional
	public DetailResponse detail(String userId, String contractId, String language) {
		Contract c = find(userId, contractId);
		expireIfStale(c);
		Locale locale = Locale.forLanguageTag(language);
		boolean processing = c.getStatus() == Contract.Status.PROCESSING;
		List<ContractClause> cl = processing ? List.of() : clauses.findByContractIdOrderByOrd(c.getId());
		FileView file = null;
		if (c.getFileKey() != null) {
			var url = storage.signedUrl(c.getFileKey(), FILE_URL_TTL);
			file = new FileView(url.url(), Times.kst(url.expiresAt()), c.getFileHash());
		}
		return new DetailResponse(c.getId(), c.getStatus().name(), c.getFailReason(), c.isConfirmed(), file,
				processing ? null : terms(c),
				processing ? null : new TranslationView(c.getLanguage(), c.getTranslationSummary()),
				processing ? null : cl.stream().map(ContractService::clauseView).toList(),
				processing ? null : reviews.reviews(c, cl, locale),
				messages.getMessage("disclaimer.default", null, locale), Times.kst(c.getCreatedAt()));
	}

	@Transactional(readOnly = true)
	public ListResponse list(String userId, String language, Integer page, Integer size) {
		Locale locale = Locale.forLanguageTag(language);
		var p = contracts.findByUserIdOrderByCreatedAtDesc(userId, PageInfo.request(page, size));
		List<ListItem> items = p.getContent().stream().map(c -> new ListItem(c.getId(), c.getStatus().name(), c.isConfirmed(),
				c.getEmployerName(),
				c.getStatus() == Contract.Status.PROCESSING ? 0 : reviews.reviews(c, clauses.findByContractIdOrderByOrd(c.getId()), locale).stream()
						.filter(r -> "CHECK".equals(r.level())).count(),
				Times.kst(c.getCreatedAt()))).toList();
		return new ListResponse(items, PageInfo.of(p));
	}

	/** 보낸 필드만 수정하고, 수정값으로 검토 항목을 다시 계산해 돌려준다. */
	@Transactional
	public TermsUpdateResponse updateTerms(String userId, String contractId, String language, TermsRequest r) {
		Contract c = find(userId, contractId);
		expireIfStale(c);
		if (c.getStatus() == Contract.Status.PROCESSING) throw new ApiException(ErrorCode.CONFLICT, "error.contract.editing");
		Set<String> touched = new HashSet<>();
		if (r.wageType() != null) { c.setWageType(r.wageType()); touched.add("wageType"); }
		if (r.wageAmount() != null) { c.setWageAmount(r.wageAmount()); touched.add("wageAmount"); }
		if (r.workStartTime() != null) { c.setWorkStartTime(r.workStartTime()); touched.add("workStartTime"); }
		if (r.workEndTime() != null) { c.setWorkEndTime(r.workEndTime()); touched.add("workEndTime"); }
		if (r.breakMinutesPerDay() != null) { c.setBreakMinutesPerDay(r.breakMinutesPerDay()); touched.add("breakMinutesPerDay"); }
		if (r.workDays() != null) { c.setWorkDays(String.join(",", new java.util.LinkedHashSet<>(r.workDays()))); touched.add("workDays"); }
		if (r.mealDeduction() != null) { c.setMealDeduction(r.mealDeduction()); touched.add("mealDeduction"); }
		if (r.payday() != null) { c.setPayday(r.payday()); touched.add("payday"); }
		if (r.contractStart() != null) { c.setContractStart(r.contractStart()); touched.add("contractStart"); }
		if (r.contractEnd() != null) { c.setContractEnd(r.contractEnd()); touched.add("contractEnd"); }
		if (r.employerName() != null) { c.setEmployerName(r.employerName()); touched.add("employerName"); }
		if (r.employerAddress() != null) { c.setEmployerAddress(r.employerAddress()); touched.add("employerAddress"); }
		if (r.ownerName() != null) { c.setOwnerName(r.ownerName()); touched.add("ownerName"); }
		if (touched.isEmpty()) throw new ApiException(ErrorCode.VALIDATION_ERROR);
		c.markEdited(touched);
		var cl = clauses.findByContractIdOrderByOrd(c.getId());
		return new TermsUpdateResponse(terms(c), reviews.reviews(c, cl, Locale.forLanguageTag(language)));
	}

	/** 사용자가 원문과 대조해 확정. 확정된 계약서 중 최신이 급여 계산 기준. 사업장 정보가 비었으면 자동 채움. */
	@Transactional
	public ConfirmResponse confirm(String userId, String contractId) {
		Contract c = find(userId, contractId);
		expireIfStale(c);
		if (c.getStatus() == Contract.Status.PROCESSING || c.getWageType() == null || c.getWageAmount() == null) {
			throw new ApiException(ErrorCode.CONFLICT, "error.contract.required");
		}
		c.setConfirmed(true);
		c.setConfirmedAt(Instant.now());
		User u = user(userId);
		boolean filled = false;
		if (!u.hasBusiness() && c.getEmployerName() != null && c.getEmployerAddress() != null && c.getOwnerName() != null) {
			u.setBusinessName(c.getEmployerName());
			u.setBusinessAddress(c.getEmployerAddress());
			u.setBusinessOwnerName(c.getOwnerName());
			u.setBusinessFromContract(true);
			filled = true;
		}
		return new ConfirmResponse(true, filled);
	}

	@Transactional
	public void delete(String userId, String contractId) {
		Contract c = find(userId, contractId);
		if (shares.sharesContract(userId, c.getId())) throw new ApiException(ErrorCode.ACTIVE_CONSULTATION);
		clauses.deleteByContractId(c.getId());
		contracts.delete(c);
		if (c.getFileKey() != null) storage.delete(c.getFileKey());
	}

	// ---------------------------------------------------------------- helpers

	private void expireIfStale(Contract c) {
		if (c.getStatus() == Contract.Status.PROCESSING && c.getAnalysisStartedAt().isBefore(Instant.now().minus(ANALYSIS_TIMEOUT))) {
			c.setStatus(Contract.Status.FAILED);
			c.setFailReason("TIMEOUT");
		}
	}

	public static TermsView terms(Contract c) {
		return new TermsView(c.getWageType(), c.getWageAmount(), c.getWorkStartTime(), c.getWorkEndTime(), c.getBreakMinutesPerDay(),
				c.workDayList(), c.getMealDeduction(), c.getPayday(), c.getContractStart(), c.getContractEnd(),
				c.getEmployerName(), c.getEmployerAddress(), c.getOwnerName());
	}

	private static ClauseView clauseView(ContractClause x) {
		PositionView pos = x.getPosX() == null ? null : new PositionView(x.getPosPage(), x.getPosX(), x.getPosY(), x.getPosWidth(), x.getPosHeight());
		return new ClauseView(x.getId(), x.getOriginalText(), x.getTranslatedText(), pos);
	}

	Contract find(String userId, String contractId) {
		return contracts.findByIdAndUserId(contractId, userId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "error.contract.notfound"));
	}

	private User user(String userId) {
		return users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
	}

	// AI 응답은 신뢰하지 않고 형식을 검증해서 저장한다.
	private static boolean validEnum(String v, String... allowed) {
		return v != null && java.util.Arrays.asList(allowed).contains(v);
	}

	private static Integer nonNeg(Integer v) {
		return v != null && v >= 0 ? v : null;
	}

	private static String hhmm(String v) {
		return v != null && v.matches("^([01]\\d|2[0-3]):[0-5]\\d$") ? v : null;
	}

	private static LocalDate date(String v) {
		try {
			return v == null ? null : LocalDate.parse(v);
		} catch (Exception e) {
			return null;
		}
	}

	private static Double clamp(Double v) {
		return v == null ? null : Math.max(0, Math.min(1, v));
	}
}
