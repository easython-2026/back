package com.easython.complaint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.easython.complaint.ComplaintDraftBuilder.Input;
import com.easython.complaint.ComplaintDraftBuilder.Item;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 진정서 한국어 초안의 금액·날짜·이름이 입력과 정확히 일치하는지(AI 없이 템플릿으로 생성). */
class ComplaintDraftBuilderTest {
	static Input input(int expected, int actual, List<Item> items, List<String> uncertainties) {
		return new Input("Tuan", "OO농장", "경기도 포천시 샘플로 1", "김OO", LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"),
				"HOURLY", 9500, "08:00", "18:00", List.of("MON", "TUE", "WED", "THU", "FRI", "SAT"), 10, 350000, 26, 15600, expected, actual,
				expected - actual, items, uncertainties, 57);
	}

	@Test
	void buildsDraftWithExactNumbers() {
		var out = ComplaintDraftBuilder.build(input(2_470_000, 1_550_000, List.of(
				new Item("BASE_PAY", "기본급", 2_470_000, 1_900_000, 570_000), new Item("MEAL_DEDUCTION", "숙식비 공제", 350_000, 350_000, 0)), List.of()));
		assertEquals("임금 체불 진정서", out.title());
		assertEquals(920_000, out.unpaidAmount());
		String b = out.body();
		assertTrue(b.contains("진정인 Tuan은(는) OO농장(주소: 경기도 포천시 샘플로 1, 대표자: 김OO)에서 2026-10-01 ~ 2026-10-31"), b);
		assertTrue(b.contains("시급 9,500원, 근무시간 08:00~18:00, 근무일 월, 화, 수, 목, 금, 토, 임금지급일 매월 10일, 숙식비 공제 매월 350,000원"), b);
		assertTrue(b.contains("총 실근무시간은 260시간입니다"), b);
		assertTrue(b.contains("예상 임금은 2,470,000원이나, 실제 지급받은 금액은 1,550,000원으로 920,000원이 지급되지 않았습니다"), b);
		assertTrue(b.contains("- 기본급: 예상 2,470,000원, 실제 1,900,000원"), b);
		assertTrue(b.contains("첨부 증거 57건"), b);
		assertFalse(b.contains("확인이 필요한 사항"), b);
		assertEquals(1, out.claims().size());                // 차이가 0인 숙식비 항목은 청구에서 제외
		assertEquals("기본급 차액", out.claims().get(0).label());
		assertEquals(570_000, out.claims().get(0).amount());
		assertTrue(out.summary().contains("차액 920,000원"));
		assertEquals(5, b.split("\n\n").length);             // 문단 수(인사·계약·근무·임금·요청) — PDF 대조 짝맞춤 기준
	}

	@Test
	void listsUncertaintiesAndFallsBackToSingleClaimWithoutItems() {
		var out = ComplaintDraftBuilder.build(input(2_000_000, 1_850_000, List.of(), List.of("휴게시간이 입력되지 않아 0분으로 계산했습니다.")));
		assertTrue(out.body().contains("【확인이 필요한 사항】"));
		assertTrue(out.body().contains("- 휴게시간이 입력되지 않아 0분으로 계산했습니다."));
		assertEquals(1, out.claims().size());
		assertEquals("임금 차액", out.claims().get(0).label());
		assertEquals(150_000, out.claims().get(0).amount());
		assertEquals(6, out.body().split("\n\n").length);
	}

	@Test
	void overpaymentGivesZeroUnpaid() {
		var out = ComplaintDraftBuilder.build(input(1_000_000, 1_200_000, List.of(), List.of()));
		assertEquals(0, out.unpaidAmount());
		assertTrue(out.claims().isEmpty());
	}

	@Test
	void hoursFormat() {
		assertEquals("260시간", ComplaintDraftBuilder.hours(15600));
		assertEquals("7시간 30분", ComplaintDraftBuilder.hours(450));
	}
}
