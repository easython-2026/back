package com.easython.ai;

import java.util.List;

/** AI 연동 경계. 구현: GatewayAiClient(실제 호출), MockAiClient(키 없을 때 데모용). */
public interface AiClient {

	/** 계약서 이미지·PDF를 읽어 조건 추출 + 조항 번역. language = 번역 대상 언어 코드. */
	ContractExtraction analyzeContract(byte[] file, String contentType, String language);

	/** 급여명세서 이미지·PDF를 읽어 항목/금액 추출. */
	StatementExtraction analyzeStatement(byte[] file, String contentType);

	/** 텍스트 조각들을 대상 언어(ko 포함)로 번역(같은 순서·같은 개수, 원문 언어 자동 감지). 숫자·날짜·이름은 그대로 유지. */
	List<String> translate(List<String> texts, String language);

	/** AI 호출 실패(네트워크, 타임아웃, 응답 형식 오류). reason 은 클라이언트에 내려갈 수 있는 짧은 코드. */
	class AiException extends RuntimeException {
		private final String reason;

		public AiException(String reason, Throwable cause) {
			super(reason, cause);
			this.reason = reason;
		}

		public String reason() {
			return reason;
		}
	}

	record Position(Integer page, Double x, Double y, Double width, Double height) {}

	record Clause(String originalText, String translatedText, Position position) {}

	record Terms(String wageType, Integer wageAmount, String workStartTime, String workEndTime, Integer breakMinutesPerDay,
			List<String> workDays, Integer mealDeduction, Integer payday, String contractStart, String contractEnd,
			String employerName, String employerAddress, String ownerName) {}

	record StatementItem(String type, String label, Integer amount) {}

	record StatementExtraction(String month, List<StatementItem> items, Integer grossAmount, Integer deductionTotal, Integer netAmount) {}

	record ContractExtraction(Terms terms, String summary, List<Clause> clauses) {}
}
