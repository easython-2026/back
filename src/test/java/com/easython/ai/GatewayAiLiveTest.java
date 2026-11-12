package com.easython.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실제 AI 게이트웨이 호출 확인(유료 크레딧 소모). 평소엔 건너뛰고, 직접 켤 때만:
 *   AI_LIVE_TEST=1 AI_API_KEY=... ./gradlew test --tests '*GatewayAiLiveTest'
 * 키는 환경변수에서만 읽는다(코드/저장소에 남기지 않음).
 */
@EnabledIfEnvironmentVariable(named = "AI_LIVE_TEST", matches = "1")
class GatewayAiLiveTest {
	@Test
	void readsKoreanContractImage() throws Exception {
		String key = System.getenv().getOrDefault("AI_API_KEY", System.getenv("DKU_API_KEY"));
		assertNotNull(key, "AI_API_KEY 또는 DKU_API_KEY 환경변수가 필요합니다");
		var client = new GatewayAiClient(System.getenv().getOrDefault("AI_BASE_URL", "https://dku-chat.kloud.zone/llm/v1"), key,
				System.getenv().getOrDefault("AI_MODEL", "anthropic/claude-haiku-4.5"), Duration.ofSeconds(90), JsonMapper.builder().build());
		byte[] img = Files.readAllBytes(Path.of("src/test/resources/sample-contract.png"));
		var ex = client.analyzeContract(img, "image/png", "vi");

		System.out.println("LIVE terms: " + ex.terms());
		System.out.println("LIVE summary: " + ex.summary());
		System.out.println("LIVE clauses: " + (ex.clauses() == null ? 0 : ex.clauses().size()));
		if (ex.clauses() != null && !ex.clauses().isEmpty()) System.out.println("LIVE clause0: " + ex.clauses().get(0));
		assertEquals("HOURLY", ex.terms().wageType());
		assertEquals(9500, ex.terms().wageAmount());
		assertEquals(350000, ex.terms().mealDeduction());
		assertEquals(10, ex.terms().payday());
		assertEquals(java.util.List.of("MON", "TUE", "WED", "THU", "FRI", "SAT"), ex.terms().workDays());
		assertTrue(ex.clauses().size() >= 3);
	}
}
