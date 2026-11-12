package com.easython.complaint;

import com.easython.ai.AiClient;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/** 진정서 비동기 처리: 한국어 초안 생성 → 모국어 번역(AI). 서류 내용은 로그에 남기지 않는다. */
@Component
@RequiredArgsConstructor
public class ComplaintGenerator {
	private static final Logger log = LoggerFactory.getLogger(ComplaintGenerator.class);

	private final ComplaintService complaints;
	private final AiClient ai;

	@Async
	public void generate(String id) {
		ComplaintService.Created created;
		try {
			created = complaints.generateKo(id);
			if (created == null) return;
		} catch (Exception e) {
			log.error("진정서 초안 생성 오류 id={}", id, e);
			complaints.generationFailed(id, "GENERATION_ERROR");
			return;
		}
		Complaint c = complaints.get(id);
		translate(id, created.language(), List.of(c.getTitleKo(), c.getBodyKo(), c.getSummaryKo()), true);
	}

	@Async
	public void retranslate(String id) {
		Complaint c = complaints.get(id);
		if (c == null) return;
		translate(id, c.getLanguage(), List.of(c.getTitleKo(), c.getBodyKo()), false);
	}

	private void translate(String id, String language, List<String> ko, boolean withSummary) {
		try {
			List<String> t = "ko".equals(language) ? ko : ai.translate(ko, language); // 한국어 사용자는 번역 불필요
			complaints.applyTranslation(id, t.get(0), t.get(1), withSummary ? t.get(2) : null);
		} catch (AiClient.AiException e) {
			log.warn("진정서 번역 실패 id={} reason={}", id, e.reason());
			complaints.translationFailed(id, e.reason());
		} catch (Exception e) {
			log.error("진정서 번역 오류 id={}", id, e);
			complaints.translationFailed(id, "INTERNAL_ERROR");
		}
	}
}
