package com.easython.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** 모델 응답(코드펜스·설명이 섞인 JSON)을 레코드로 읽는 경로. 실제 AI 호출 없음. */
class AiParsingTest {
	@Test
	void parsesFencedJsonIntoRecords() {
		String reply = """
				Here is the result:
				```json
				{"terms":{"wageType":"HOURLY","wageAmount":10320,"workDays":["MON","TUE"],"mealDeduction":350000,"unknown":1},
				 "summary":"요약","clauses":[{"originalText":"숙식비 공제","translatedText":"x","position":{"page":1,"x":0.1,"y":0.2,"width":0.5,"height":0.05}}]}
				```""";
		var ex = JsonMapper.builder().build().readValue(GatewayAiClient.extractJson(reply), AiClient.ContractExtraction.class);
		assertEquals(10320, ex.terms().wageAmount());
		assertEquals(2, ex.terms().workDays().size());
		assertEquals(0.2, ex.clauses().get(0).position().y());
	}

	@Test
	void rejectsReplyWithoutJson() {
		var e = assertThrows(AiClient.AiException.class, () -> GatewayAiClient.extractJson("sorry, I cannot read this"));
		assertEquals("PARSE_ERROR", e.reason());
	}
}
