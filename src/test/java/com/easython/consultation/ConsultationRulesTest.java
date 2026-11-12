package com.easython.consultation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ConsultationRulesTest {
	static final LocalDate TODAY = LocalDate.parse("2026-11-12");

	@Test
	void headline() {
		assertEquals("10월 임금 92만원 미지급 의심", ConsultationService.headline("2026-10", 920_000));
		assertEquals("10월 임금 9,500원 미지급 의심", ConsultationService.headline("2026-10", 9_500));
		assertEquals("10월 임금 확인 요청", ConsultationService.headline("2026-10", 0));
	}

	@Test
	void suggestedUrgency() {
		assertEquals("HIGH", ConsultationService.suggestUrgency(1_000_000, null, TODAY));
		assertEquals("MEDIUM", ConsultationService.suggestUrgency(300_000, null, TODAY));
		assertEquals("LOW", ConsultationService.suggestUrgency(299_999, null, TODAY));
		// 계약 종료(출국 가능성) 30일 이내면 한 단계 상향
		assertEquals("MEDIUM", ConsultationService.suggestUrgency(100_000, TODAY.plusDays(30), TODAY));
		assertEquals("HIGH", ConsultationService.suggestUrgency(500_000, TODAY.plusDays(10), TODAY));
		assertEquals("LOW", ConsultationService.suggestUrgency(100_000, TODAY.plusDays(31), TODAY));
	}
}
