package com.easython.contract;

import com.easython.ai.AiClient;
import com.easython.storage.FileStorage;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** 계약서 AI 분석(비동기). 결과/실패는 ContractService 에 반영하고 클라이언트는 GET 으로 폴링한다. */
@Component
@RequiredArgsConstructor
public class ContractAnalyzer {
	private static final Logger log = LoggerFactory.getLogger(ContractAnalyzer.class);

	private final ContractService contracts;
	private final AiClient ai;
	private final FileStorage storage;
	private final JsonMapper json;

	@Async
	public void analyze(String contractId) {
		try {
			Contract c = contracts.get(contractId);
			if (c == null) return;
			byte[] file = storage.load(c.getFileKey());
			AiClient.ContractExtraction ex = ai.analyzeContract(file, c.getFileContentType(), c.getLanguage());
			contracts.applyResult(contractId, ex, ex.terms() == null ? null : json.writeValueAsString(ex.terms()));
		} catch (AiClient.AiException e) {
			log.warn("계약서 분석 실패 id={} reason={}", contractId, e.reason()); // 서류 내용은 로그에 남기지 않는다
			contracts.markFailed(contractId, e.reason());
		} catch (Exception e) {
			log.error("계약서 분석 오류 id={}", contractId, e);
			contracts.markFailed(contractId, "INTERNAL_ERROR");
		}
	}
}
