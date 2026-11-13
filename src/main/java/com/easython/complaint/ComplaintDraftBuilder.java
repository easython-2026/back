package com.easython.complaint;

import com.easython.complaint.ComplaintDtos.Claim;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 진정서 한국어 초안을 코드 템플릿으로 만든다. 금액·날짜·이름이 틀리면 안 되는 문서라 AI가 쓰지 않고,
 * AI는 완성된 한국어를 모국어로 번역하는 데만 쓴다. 순수 함수라 DB 없이 테스트 가능.
 */
public final class ComplaintDraftBuilder {
	private static final Map<String, String> DAYS = Map.of("MON", "월", "TUE", "화", "WED", "수", "THU", "목", "FRI", "금", "SAT", "토", "SUN", "일");

	public record Item(String type, String label, int expected, int actual, int difference) {}

	public record Input(String complainantName, String respondentName, String respondentAddress, String respondentOwner,
			LocalDate periodStart, LocalDate periodEnd, String wageType, Integer wageAmount, String workStartTime, String workEndTime,
			List<String> workDays, Integer payday, Integer mealDeduction, int workedDays, int workedMinutes, int expected, int actual,
			int difference, List<Item> items, List<String> uncertaintyMessages, int evidenceCount, int deviceDays, int manualDays) {}

	public record Output(String title, String body, String summary, List<Claim> claims, int unpaidAmount) {}

	private ComplaintDraftBuilder() {}

	public static Output build(Input in) {
		int unpaid = Math.max(0, in.difference());
		String period = in.periodStart() + " ~ " + in.periodEnd();
		String hours = hours(in.workedMinutes());

		List<String> p = new ArrayList<>();
		p.add("진정인 %s은(는) %s(주소: %s, 대표자: %s)에서 %s 기간 동안 근로하였으나, 아래와 같이 임금을 전부 지급받지 못하여 진정합니다."
				.formatted(in.complainantName(), in.respondentName(), in.respondentAddress(), in.respondentOwner(), period));

		StringBuilder contract = new StringBuilder("【근로계약 내용】 ");
		List<String> parts = new ArrayList<>();
		if (in.wageAmount() != null) parts.add(("MONTHLY".equals(in.wageType()) ? "월급 " : "시급 ") + won(in.wageAmount()) + "원");
		if (in.workStartTime() != null && in.workEndTime() != null) parts.add("근무시간 " + in.workStartTime() + "~" + in.workEndTime());
		if (in.workDays() != null && !in.workDays().isEmpty()) parts.add("근무일 " + in.workDays().stream().map(d -> DAYS.getOrDefault(d, d)).collect(Collectors.joining(", ")));
		if (in.payday() != null) parts.add("임금지급일 매월 " + in.payday() + "일");
		if (in.mealDeduction() != null && in.mealDeduction() > 0) parts.add("숙식비 공제 매월 " + won(in.mealDeduction()) + "원");
		p.add(contract.append(String.join(", ", parts)).append(".").toString());

		p.add("【근무 사실】 해당 기간 %d일 근무하였고, 총 실근무시간은 %s입니다. %s(첨부 증거 %d건 참고)."
				.formatted(in.workedDays(), hours, recordBasis(in.deviceDays(), in.manualDays()), in.evidenceCount()));

		StringBuilder wage = new StringBuilder("【임금 내역】 근로계약과 근무기록에 따른 예상 임금은 %s원이나, 실제 지급받은 금액은 %s원으로 %s원이 지급되지 않았습니다."
				.formatted(won(in.expected()), won(in.actual()), won(unpaid)));
		List<Claim> claims = claims(in);
		for (Item it : in.items()) {
			wage.append("\n- ").append(it.label()).append(": 예상 ").append(won(it.expected())).append("원, 실제 ").append(won(it.actual())).append("원");
		}
		p.add(wage.toString());

		if (!in.uncertaintyMessages().isEmpty()) {
			p.add("【확인이 필요한 사항】 위 금액은 앱이 계산한 추정액이며 다음 사항은 확인이 필요합니다.\n- " + String.join("\n- ", in.uncertaintyMessages()));
		}
		p.add("【요청】 위 미지급 임금 %s원이 지급될 수 있도록 사실관계를 조사하여 시정해 주시기 바랍니다.".formatted(won(unpaid)));

		String summary = "근무기간 %s, %s, 총 실근무시간 %s, 예상 임금 %s원, 실제 지급액 %s원, 차액 %s원, 첨부 증거 %d건."
				.formatted(period, in.wageAmount() == null ? "계약 조건 미확인" : ("MONTHLY".equals(in.wageType()) ? "월급 " : "시급 ") + won(in.wageAmount()) + "원",
						hours, won(in.expected()), won(in.actual()), won(unpaid), in.evidenceCount());
		return new Output("임금 체불 진정서", String.join("\n\n", p), summary, claims, unpaid);
	}

	/** 기록의 근거를 사실대로: 앱 인증 기록(GPS·사진·서버 시각)과 사후 수동 입력 기록을 구분한다. */
	static String recordBasis(int deviceDays, int manualDays) {
		if (manualDays == 0) return "출퇴근은 GPS 위치 인증, 사진, 서버 기록 시각으로 남아 있습니다";
		if (deviceDays == 0) return "출퇴근 기록은 모두 사후에 직접 입력한 기록입니다";
		return "이 중 %d일은 GPS 위치 인증, 사진, 서버 기록 시각으로 남아 있고, %d일은 사후에 직접 입력한 기록입니다".formatted(deviceDays, manualDays);
	}

	/** 항목별 차액(근로자에게 불리한 방향으로 차이가 나는 항목만). 항목이 없으면(수령액만 입력) 총 차액 한 줄. */
	static List<Claim> claims(Input in) {
		List<Claim> out = new ArrayList<>();
		for (Item it : in.items()) {
			if (it.difference() <= 0) continue;
			String label = switch (it.type()) {
				case "BASE_PAY" -> "기본급 차액";
				case "OVERTIME_PAY" -> "연장근로 가산수당 차액";
				case "MEAL_DEDUCTION" -> "숙식비 공제 초과분";
				default -> it.label() + " 차액";
			};
			out.add(new Claim(label, it.difference()));
		}
		if (out.isEmpty() && in.difference() > 0) out.add(new Claim("임금 차액", in.difference()));
		return out;
	}

	static String won(int v) {
		return String.format("%,d", v);
	}

	static String hours(int minutes) {
		return minutes % 60 == 0 ? (minutes / 60) + "시간" : (minutes / 60) + "시간 " + (minutes % 60) + "분";
	}
}
