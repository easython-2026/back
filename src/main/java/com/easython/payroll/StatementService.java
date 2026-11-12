package com.easython.payroll;

import com.easython.ai.AiClient.StatementExtraction;
import com.easython.common.ApiException;
import com.easython.common.ErrorCode;
import com.easython.common.Times;
import com.easython.common.Uploads;
import com.easython.contract.ContractDtos.FileView;
import com.easython.payroll.PayrollDtos.*;
import com.easython.storage.FileStorage;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class StatementService {
	private static final Duration FILE_URL_TTL = Duration.ofMinutes(30);
	private static final Duration ANALYSIS_TIMEOUT = Duration.ofMinutes(5);

	private final PayStatementRepository statements;
	private final PayStatementItemRepository items;
	private final FileStorage storage;

	/** 파일 저장 + PROCESSING 명세서 생성. 분석은 커밋 후 컨트롤러가 StatementAnalyzer 로 시작. */
	@Transactional
	public String upload(String userId, MultipartFile file, String month) {
		if (file == null || file.isEmpty()) throw new ApiException(ErrorCode.VALIDATION_ERROR);
		if (month != null && !month.isBlank()) parseMonth(month);
		if (file.getSize() > Uploads.MAX_DOCUMENT_BYTES) throw new ApiException(ErrorCode.FILE_TOO_LARGE);
		byte[] bytes;
		try {
			bytes = file.getBytes();
		} catch (java.io.IOException e) {
			throw new IllegalStateException(e);
		}
		var type = Uploads.detectDocument(bytes);
		PayStatement s = new PayStatement();
		s.setUserId(userId);
		s.setMonth(month == null || month.isBlank() ? null : month);
		s.setFileKey("statement_" + s.getId() + "." + type.ext());
		s.setFileContentType(type.contentType());
		s.setFileHash(FileStorage.sha256(bytes));
		storage.save(s.getFileKey(), bytes);
		statements.save(s);
		return s.getId();
	}

	/** 수령액만 직접 입력(명세서 없음). 입력과 동시에 확정. */
	@Transactional
	public ManualResponse manual(String userId, ManualRequest r) {
		parseMonth(r.month());
		PayStatement s = new PayStatement();
		s.setUserId(userId);
		s.setSource(PayStatement.Source.MANUAL);
		s.setStatus(PayStatement.Status.DONE);
		s.setConfirmed(true);
		s.setMonth(r.month());
		s.setReceivedAmount(r.receivedAmount());
		s.setNetAmount(r.receivedAmount());
		s.setPaidAt(r.paidAt());
		statements.save(s);
		return new ManualResponse(s.getId(), "MANUAL", "DONE", true);
	}

	@Transactional
	public StatementDetail detail(String userId, String id) {
		PayStatement s = find(userId, id);
		expireIfStale(s);
		return view(s);
	}

	@Transactional
	public StatementDetail patch(String userId, String id, StatementPatchRequest r) {
		PayStatement s = find(userId, id);
		expireIfStale(s);
		if (s.getStatus() == PayStatement.Status.PROCESSING) throw new ApiException(ErrorCode.CONFLICT, "error.statement.processing");
		if (r.month() == null && r.items() == null && r.netAmount() == null && r.confirm() == null) throw new ApiException(ErrorCode.VALIDATION_ERROR);
		if (r.month() != null) {
			parseMonth(r.month());
			s.setMonth(r.month());
		}
		if (r.items() != null) {
			items.deleteByStatementId(s.getId());
			items.flush();
			List<PayStatementItem> rows = new ArrayList<>();
			for (int i = 0; i < r.items().size(); i++) rows.add(row(s.getId(), i, r.items().get(i).type(), r.items().get(i).label(), r.items().get(i).amount()));
			items.saveAll(rows);
			recompute(s, rows, r.netAmount());
		} else if (r.netAmount() != null) {
			s.setNetAmount(r.netAmount());
		}
		if (Boolean.TRUE.equals(r.confirm())) {
			if (s.getMonth() == null || s.getNetAmount() == null) throw new ApiException(ErrorCode.VALIDATION_ERROR); // 월과 실지급액은 필수
			s.setConfirmed(true);
		} else if (Boolean.FALSE.equals(r.confirm())) {
			s.setConfirmed(false);
		}
		if (s.getStatus() == PayStatement.Status.FAILED) s.setStatus(PayStatement.Status.DONE); // 직접 입력으로 복구
		return view(s);
	}

	// ---------------------------------------------------------------- 분석 결과 반영(StatementAnalyzer)

	@Transactional
	public void applyResult(String id, StatementExtraction ex, String ocrJson) {
		PayStatement s = statements.findById(id).orElse(null);
		if (s == null) return;
		if (s.getMonth() == null && ex.month() != null && ex.month().matches("\\d{4}-(0[1-9]|1[0-2])")) s.setMonth(ex.month());
		items.deleteByStatementId(id);
		items.flush();
		List<PayStatementItem> rows = new ArrayList<>();
		if (ex.items() != null) {
			int i = 0;
			for (var it : ex.items()) {
				StatementItemType type = parseType(it.type());
				if (type == null || it.amount() == null || it.amount() < 0 || it.label() == null) continue; // 형식이 안 맞는 항목은 버린다
				rows.add(row(id, i++, type, it.label(), it.amount()));
			}
		}
		items.saveAll(rows);
		recompute(s, rows, ex.netAmount());
		if (rows.isEmpty() && ex.grossAmount() != null) s.setGrossAmount(ex.grossAmount());
		s.setOcrJson(ocrJson);
		s.setStatus(PayStatement.Status.DONE);
		s.setFailReason(null);
	}

	@Transactional
	public void markFailed(String id, String reason) {
		statements.findById(id).ifPresent(s -> {
			s.setStatus(PayStatement.Status.FAILED);
			s.setFailReason(reason);
		});
	}

	@Transactional(readOnly = true)
	public PayStatement get(String id) {
		return statements.findById(id).orElse(null);
	}

	// ---------------------------------------------------------------- helpers

	/** 총지급·공제합계·실지급 계산. 실지급을 직접 줬으면 그 값을 우선. */
	private void recompute(PayStatement s, List<PayStatementItem> rows, Integer explicitNet) {
		int gross = rows.stream().filter(x -> !x.getType().isDeduction()).mapToInt(PayStatementItem::getAmount).sum();
		int ded = rows.stream().filter(x -> x.getType().isDeduction()).mapToInt(PayStatementItem::getAmount).sum();
		s.setGrossAmount(rows.isEmpty() ? null : gross);
		s.setDeductionTotal(rows.isEmpty() ? null : ded);
		s.setNetAmount(explicitNet != null ? explicitNet : rows.isEmpty() ? s.getNetAmount() : Math.max(0, gross - ded));
	}

	private StatementDetail view(PayStatement s) {
		boolean processing = s.getStatus() == PayStatement.Status.PROCESSING;
		FileView file = null;
		if (s.getFileKey() != null) {
			var url = storage.signedUrl(s.getFileKey(), FILE_URL_TTL);
			file = new FileView(url.url(), Times.kst(url.expiresAt()), s.getFileHash());
		}
		List<ItemView> list = processing ? null
				: items.findByStatementIdOrderByOrd(s.getId()).stream().map(x -> new ItemView(x.getType().name(), x.getLabel(), x.getAmount())).toList();
		return new StatementDetail(s.getId(), s.getStatus().name(), s.getFailReason(), s.getSource().name(), s.getMonth(), s.isConfirmed(), file,
				list, processing ? null : s.getGrossAmount(), processing ? null : s.getDeductionTotal(), processing ? null : s.getNetAmount(),
				s.getReceivedAmount());
	}

	private void expireIfStale(PayStatement s) {
		if (s.getStatus() == PayStatement.Status.PROCESSING && s.getAnalysisStartedAt().isBefore(Instant.now().minus(ANALYSIS_TIMEOUT))) {
			s.setStatus(PayStatement.Status.FAILED);
			s.setFailReason("TIMEOUT");
		}
	}

	PayStatement find(String userId, String id) {
		return statements.findByIdAndUserId(id, userId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "error.statement.notfound"));
	}

	private static PayStatementItem row(String statementId, int ord, StatementItemType type, String label, int amount) {
		PayStatementItem it = new PayStatementItem();
		it.setStatementId(statementId);
		it.setOrd(ord);
		it.setType(type);
		it.setLabel(label);
		it.setAmount(amount);
		return it;
	}

	private static StatementItemType parseType(String v) {
		try {
			return v == null ? null : StatementItemType.valueOf(v);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	static YearMonth parseMonth(String v) {
		try {
			return YearMonth.parse(v);
		} catch (Exception e) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR);
		}
	}
}
