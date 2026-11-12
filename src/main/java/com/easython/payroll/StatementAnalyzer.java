package com.easython.payroll;

import com.easython.ai.AiClient;
import com.easython.storage.FileStorage;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** 급여명세서 AI 분석(비동기). 서류 내용은 로그에 남기지 않는다. */
@Component
@RequiredArgsConstructor
public class StatementAnalyzer {
	private static final Logger log = LoggerFactory.getLogger(StatementAnalyzer.class);

	private final StatementService statements;
	private final AiClient ai;
	private final FileStorage storage;
	private final JsonMapper json;

	@Async
	public void analyze(String statementId) {
		try {
			PayStatement s = statements.get(statementId);
			if (s == null) return;
			var ex = ai.analyzeStatement(storage.load(s.getFileKey()), s.getFileContentType());
			statements.applyResult(statementId, ex, json.writeValueAsString(ex));
		} catch (AiClient.AiException e) {
			log.warn("명세서 분석 실패 id={} reason={}", statementId, e.reason());
			statements.markFailed(statementId, e.reason());
		} catch (Exception e) {
			log.error("명세서 분석 오류 id={}", statementId, e);
			statements.markFailed(statementId, "INTERNAL_ERROR");
		}
	}
}
