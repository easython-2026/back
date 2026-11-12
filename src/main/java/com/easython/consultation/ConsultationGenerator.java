package com.easython.consultation;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/** 상담용 요약 카드 비동기 생성: 상담 희망 내용 한국어 번역 → 한국어 요약(템플릿) → 노동자 언어 번역. */
@Component
@RequiredArgsConstructor
public class ConsultationGenerator {
	private static final Logger log = LoggerFactory.getLogger(ConsultationGenerator.class);

	private final ConsultationService consultations;

	@Async
	public void summarize(String id) {
		try {
			Consultation c = consultations.get(id);
			if (c == null) return;
			String lang = consultations.workerLanguage(c);
			String memoKo = null;
			if (c.getMemo() != null) {
				memoKo = "ko".equals(lang) ? c.getMemo() : consultations.translateOrNull(c.getMemo(), "ko");
				if (memoKo == null) memoKo = c.getMemo() + " (번역 실패, 원문)";
			}
			String ko = consultations.buildSummaryKo(id, memoKo);
			String translated = "ko".equals(lang) ? ko : consultations.translateOrNull(ko, lang); // 번역 실패면 null(한국어 요약은 사용 가능)
			consultations.applySummary(id, memoKo, ko, translated);
		} catch (Exception e) {
			log.error("상담 요약 생성 오류 id={}", id, e);
			consultations.summaryFailed(id);
		}
	}
}
