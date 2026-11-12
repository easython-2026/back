package com.easython.ai;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 키가 없거나 AI_PROVIDER=mock 일 때 쓰는 데모용 응답(투안 시나리오: 숙식비 35만원 공제, 휴게시간 누락).
 * 데모 시드와 테스트에서 같은 샘플을 재사용한다. 번역문(en/vi)은 사람 검수 전 초안.
 */
public class MockAiClient implements AiClient {

	@Override
	public ContractExtraction analyzeContract(byte[] file, String contentType, String language) {
		return sample(language, LocalDate.now().withDayOfMonth(1), 10320);
	}

	@Override
	public StatementExtraction analyzeStatement(byte[] file, String contentType) {
		return sampleStatement(java.time.YearMonth.now().minusMonths(1).toString());
	}

	/** 투안 시나리오: 260시간 근무했는데 기본급 190만원만 지급, 숙식비 35만원 공제 → 실지급 155만원. */
	public static StatementExtraction sampleStatement(String month) {
		return new StatementExtraction(month, List.of(new StatementItem("BASE_PAY", "기본급", 1900000), new StatementItem("MEAL_DEDUCTION", "숙식비", 350000)),
				1900000, 350000, 1550000);
	}

	/** 데모용: 실제 번역은 못 하므로 표시만 붙여 돌려준다(키가 있으면 GatewayAiClient 가 번역). */
	@Override
	public List<String> translate(List<String> texts, String language) {
		return texts.stream().map(t -> "[데모 번역:" + language + "] " + t).toList();
	}

	public static ContractExtraction sample(String language, LocalDate start, int hourlyWage) {
		Terms terms = new Terms("HOURLY", hourlyWage, "08:00", "18:00", null, List.of("MON", "TUE", "WED", "THU", "FRI", "SAT"),
				350000, 10, start.toString(), start.plusYears(1).minusDays(1).toString(), "OO농장", "경기도 포천시 샘플로 1", "김OO");
		String[] ko = { "시급은 금 " + String.format("%,d", hourlyWage) + "원으로 한다.", "근무시간은 08:00부터 18:00까지로 한다.",
				"숙식비로 매월 35만원을 공제한다.", "임금은 매월 10일에 지급한다." };
		String[] tr = switch (language) {
			case "vi" -> new String[] { "Tiền lương theo giờ là " + String.format("%,d", hourlyWage) + " won.",
					"Giờ làm việc từ 08:00 đến 18:00.", "Mỗi tháng khấu trừ 350.000 won tiền ăn ở.", "Lương được trả vào ngày 10 hàng tháng." };
			case "en" -> new String[] { "The hourly wage is KRW " + String.format("%,d", hourlyWage) + ".",
					"Working hours are from 08:00 to 18:00.", "KRW 350,000 per month is deducted for meals and lodging.", "Wages are paid on the 10th of each month." };
			default -> ko;
		};
		Map<String, String> summary = Map.of("ko", "시급 " + String.format("%,d", hourlyWage) + "원, 08:00~18:00, 월 35만원 숙식비 공제, 매월 10일 지급.",
				"en", "Hourly wage KRW " + String.format("%,d", hourlyWage) + ", 08:00-18:00, KRW 350,000/month meal & lodging deduction, paid on the 10th.",
				"vi", "Lương giờ " + String.format("%,d", hourlyWage) + " won, 08:00-18:00, khấu trừ 350.000 won/tháng tiền ăn ở, trả lương ngày 10.");
		List<Clause> clauses = new java.util.ArrayList<>();
		for (int i = 0; i < ko.length; i++) {
			clauses.add(new Clause(ko[i], tr[i], new Position(1, 0.1, 0.25 + i * 0.1, 0.8, 0.05)));
		}
		return new ContractExtraction(terms, summary.getOrDefault(language, summary.get("ko")), clauses);
	}
}
