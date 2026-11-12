package com.easython.ai;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import tools.jackson.databind.json.JsonMapper;

@Configuration
@EnableAsync
public class AiConfig {
	private static final Logger log = LoggerFactory.getLogger(AiConfig.class);

	/** 키가 있으면 실제 게이트웨이, 없거나 AI_PROVIDER=mock 이면 모의 응답. */
	@Bean
	AiClient aiClient(@Value("${app.ai.provider}") String provider, @Value("${app.ai.api-key}") String apiKey,
			@Value("${app.ai.base-url}") String baseUrl, @Value("${app.ai.model}") String model,
			@Value("${app.ai.timeout-seconds}") long timeoutSeconds, JsonMapper json) {
		if ("mock".equalsIgnoreCase(provider) || apiKey.isBlank()) {
			log.warn("AI 키가 없거나 mock 설정입니다. 데모용 모의 응답을 사용합니다.");
			return new MockAiClient();
		}
		log.info("AI 게이트웨이 사용: model={}", model); // 키는 출력하지 않는다
		return new GatewayAiClient(baseUrl, apiKey, model, Duration.ofSeconds(timeoutSeconds), json);
	}
}
