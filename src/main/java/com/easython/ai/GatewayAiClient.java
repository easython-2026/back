package com.easython.ai;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * OpenAI 호환 게이트웨이(dku-chat 등)로 Claude 등 모델을 호출. 키는 환경변수로만 받는다.
 * 보안: 요청/응답 본문(개인 서류)과 키는 로그에 남기지 않는다.
 */
public class GatewayAiClient implements AiClient {
	private final String baseUrl;
	private final String apiKey;
	private final String model;
	private final Duration timeout;
	private final JsonMapper json;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

	public GatewayAiClient(String baseUrl, String apiKey, String model, Duration timeout, JsonMapper json) {
		this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
		this.apiKey = apiKey;
		this.model = model;
		this.timeout = timeout;
		this.json = json;
	}

	@Override
	public ContractExtraction analyzeContract(byte[] file, String contentType, String language) {
		String lang = Locale.forLanguageTag(language).getDisplayLanguage(Locale.ENGLISH);
		String system = """
				You read Korean employment contracts for migrant workers. Respond with ONE JSON object only, no markdown, no commentary.
				Never invent values: use null when something is not written in the document.
				Day ranges are inclusive and end exactly where the text ends: "월요일부터 토요일까지" = MON,TUE,WED,THU,FRI,SAT (NOT Sunday).
				Checkbox options count only when marked ([✔], [V], [O], filled); unmarked [ ] options are not chosen.
				Wage: take the type and amount of the BASE PAY (기본급/시급/월급). If the base pay is hourly (시간급, 시급, "(월, 시간, 일, 주)급" with 시간 marked),
				wageType is HOURLY and wageAmount is that hourly amount — even when a monthly figure such as 월 통상임금 (monthly normal wage) is also written.
				Use MONTHLY only when the base pay itself is a monthly amount. 월 통상임금 / monthly normal wage is a derived total, never the base pay.
				In the government standard form (표준근로계약서, 7. 임금) the row "기본급[(월, 시간, 일, 주)급] ( X )원" holds the base pay X: report X as wageAmount, never the 월 통상임금 total above it.
				Sanity check: a base pay below 100,000 KRW is an hourly wage (HOURLY), not a monthly one.
				Meal/lodging deduction: add up EVERY monthly amount the worker pays or has deducted for lodging (숙박, 숙소, 기숙사) and meals (식사, 식비)
				— e.g. 숙박 월 200,000원 + 식사 월 150,000원 = 350000. Use null only when no such amount is written.""";
		String user = """
				Read the attached Korean employment contract and return JSON exactly in this shape:
				{
				 "terms": {
				  "wageType": "HOURLY" or "MONTHLY",
				  "wageAmount": integer KRW of the base pay (the hourly amount when wageType is HOURLY),
				  "workStartTime": "HH:mm", "workEndTime": "HH:mm",
				  "breakMinutesPerDay": integer minutes or null if not stated,
				  "workDays": ["MON","TUE","WED","THU","FRI","SAT","SUN"] subset,
				  "mealDeduction": integer KRW per month = lodging + meals combined, or null,
				  "payday": day of month 1-31,
				  "contractStart": "YYYY-MM-DD", "contractEnd": "YYYY-MM-DD",
				  "employerName": string, "employerAddress": string, "ownerName": string
				 },
				 "summary": "2-4 sentence summary of the key conditions, written in %s",
				 "clauses": [ up to 15 clauses about wage, working hours, breaks, meal/lodging deduction, payday, contract period:
				  {"originalText": "exact Korean text", "translatedText": "translation into %s",
				   "position": {"page": 1, "x": 0-1, "y": 0-1, "width": 0-1, "height": 0-1}  (approximate location as fractions of the page; null if unsure)} ]
				}""".formatted(lang, lang);
		String text = chat(system, user, file, contentType);
		try {
			return json.readValue(extractJson(text), ContractExtraction.class);
		} catch (RuntimeException e) {
			throw new AiException("PARSE_ERROR", e);
		}
	}

	@Override
	public StatementExtraction analyzeStatement(byte[] file, String contentType) {
		String system = "You read Korean pay statements (급여명세서) of migrant workers. Respond with ONE JSON object only, no markdown. "
				+ "Never invent values: use null when something is not written in the document.";
		String user = """
				Read the attached Korean pay statement and return JSON exactly in this shape:
				{"month": "YYYY-MM" (the month the pay is for, null if unknown),
				 "items": [{"type": one of BASE_PAY, OVERTIME_PAY, NIGHT_PAY, HOLIDAY_PAY, WEEKLY_HOLIDAY_PAY, ALLOWANCE, MEAL_DEDUCTION, LODGING_DEDUCTION, TAX_INSURANCE, OTHER_DEDUCTION,
				            "label": exact item name as printed, "amount": positive integer KRW (deductions are also positive)}],
				 "grossAmount": integer total pay before deductions, "deductionTotal": integer, "netAmount": integer amount actually paid}
				Mapping: 기본급=BASE_PAY, 연장근로수당=OVERTIME_PAY, 야간수당=NIGHT_PAY, 휴일수당=HOLIDAY_PAY, 주휴수당=WEEKLY_HOLIDAY_PAY, 기타수당=ALLOWANCE,
				식비=MEAL_DEDUCTION, 숙소비/기숙사비=LODGING_DEDUCTION, 소득세·4대보험=TAX_INSURANCE, others=OTHER_DEDUCTION.""";
		try {
			return json.readValue(extractJson(chat(system, user, file, contentType)), StatementExtraction.class);
		} catch (AiException e) {
			throw e;
		} catch (RuntimeException e) {
			throw new AiException("PARSE_ERROR", e);
		}
	}

	@Override
	public List<String> translate(List<String> koTexts, String language) {
		String lang = Locale.forLanguageTag(language).getDisplayLanguage(Locale.ENGLISH);
		String system = "You are a precise legal translator for Korean labor documents. Respond with ONE JSON array of strings only, no markdown.";
		String user = ("Translate each text in the JSON array below into %s (the source language may be Korean or any other language). Keep the same number and order of items. Keep numbers, dates, amounts, names and line breaks exactly as they are. "
				+ "Do not add or remove information.\n").formatted(lang);
		try {
			String reply = chat(system, user + json.writeValueAsString(koTexts), null, null);
			int s = reply.indexOf('['), e = reply.lastIndexOf(']');
			if (s < 0 || e < s) throw new AiException("PARSE_ERROR", null);
			List<?> out = json.readValue(reply.substring(s, e + 1), List.class);
			if (out.size() != koTexts.size()) throw new AiException("PARSE_ERROR", null);
			return out.stream().map(String::valueOf).toList();
		} catch (AiException e) {
			throw e;
		} catch (RuntimeException e) {
			throw new AiException("PARSE_ERROR", e);
		}
	}

	/** 시스템/사용자 프롬프트 + 첨부(이미지·PDF)를 보내고 응답 텍스트를 돌려준다. */
	String chat(String system, String user, byte[] file, String contentType) {
		try {
			List<Object> parts = new ArrayList<>();
			parts.add(Map.of("type", "text", "text", user));
			if (file != null) {
				String dataUrl = "data:" + contentType + ";base64," + Base64.getEncoder().encodeToString(file);
				parts.add("application/pdf".equals(contentType)
						? Map.of("type", "file", "file", Map.of("filename", "document.pdf", "file_data", dataUrl))
						: Map.of("type", "image_url", "image_url", Map.of("url", dataUrl)));
			}
			String body = json.writeValueAsString(Map.of("model", model, "max_tokens", 4000, "temperature", 0,
					"messages", List.of(Map.of("role", "system", "content", system), Map.of("role", "user", "content", parts))));
			HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions")).timeout(timeout)
					.header("Content-Type", "application/json").header("Authorization", "Bearer " + apiKey)
					.POST(HttpRequest.BodyPublishers.ofString(body)).build();
			HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
			if (res.statusCode() / 100 != 2) throw new AiException("AI_SERVICE_ERROR", null);
			Map<?, ?> parsed = json.readValue(res.body(), Map.class);
			Map<?, ?> message = (Map<?, ?>) ((Map<?, ?>) ((List<?>) parsed.get("choices")).get(0)).get("message");
			return String.valueOf(message.get("content"));
		} catch (AiException e) {
			throw e;
		} catch (java.net.http.HttpTimeoutException e) {
			throw new AiException("TIMEOUT", e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AiException("AI_SERVICE_ERROR", e);
		} catch (Exception e) {
			throw new AiException("AI_SERVICE_ERROR", e);
		}
	}

	/** 모델이 ```json 펜스나 앞뒤 설명을 붙여도 첫 { ~ 마지막 } 만 취한다. */
	static String extractJson(String text) {
		int s = text.indexOf('{');
		int e = text.lastIndexOf('}');
		if (s < 0 || e < s) throw new AiException("PARSE_ERROR", null);
		return text.substring(s, e + 1);
	}
}
