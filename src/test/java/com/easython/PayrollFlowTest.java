package com.easython;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.YearMonth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(properties = { "app.storage.local-dir=build/test-uploads", "app.ai.provider=mock" })
class PayrollFlowTest {
	@Autowired WebApplicationContext ctx;
	MockMvc mvc;

	static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4, 5 };
	static final String JSON = MediaType.APPLICATION_JSON_VALUE;
	static final String MONTH = YearMonth.now().minusMonths(1).toString();

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();
	}

	String signup(String email) throws Exception {
		String body = """
				{"email":"%s","password":"Test1234!","name":"Tuan","language":"ko",
				 "consents":{"privacy":true,"location":true,"camera":true},"agreedAt":"2026-11-11T09:00:00+09:00"}""".formatted(email);
		String r = mvc.perform(post("/api/v1/auth/signup").contentType(JSON).content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return "Bearer " + JsonPath.read(r, "$.data.accessToken");
	}

	String read(ResultActions a, String path) throws Exception {
		return JsonPath.read(a.andReturn().getResponse().getContentAsString(), path);
	}

	ResultActions compare(String auth, String month, String statementId) throws Exception {
		return mvc.perform(post("/api/v1/payroll/comparisons").header("Authorization", auth).contentType(JSON)
				.content("{\"month\":\"%s\",\"statementId\":\"%s\"}".formatted(month, statementId)));
	}

	void waitDone(String auth, String path) throws Exception {
		for (int i = 0; i < 100; i++) {
			String s = mvc.perform(get(path).header("Authorization", auth)).andReturn().getResponse().getContentAsString();
			if (!"PROCESSING".equals(JsonPath.read(s, "$.data.status"))) return;
			Thread.sleep(100);
		}
		throw new AssertionError("analysis did not finish");
	}

	@Test
	void demoScenarioComparison() throws Exception {
		String auth = signup("payroll@example.com");
		String seeded = mvc.perform(post("/api/v1/demo/seed").header("Authorization", auth).contentType(JSON).content("{\"month\":\"" + MONTH + "\"}"))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.data.statementId").exists()).andReturn().getResponse().getContentAsString();
		String statementId = JsonPath.read(seeded, "$.data.statementId");

		// 급여비교 탭 상태: 확정된 명세서가 있고 비교 전
		mvc.perform(get("/api/v1/payroll/comparisons").param("month", MONTH).header("Authorization", auth)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.state").value("READY")).andExpect(jsonPath("$.data.statementId").value(statementId))
				.andExpect(jsonPath("$.data.payday").value(YearMonth.parse(MONTH).plusMonths(1).atDay(10).toString())).andExpect(jsonPath("$.data.comparison").doesNotExist());
		mvc.perform(get("/api/v1/payroll/comparisons").header("Authorization", auth)).andExpect(status().isBadRequest());

		// 비교: 약 255시간 근무했는데 기본급 190만원만 → 불일치
		String cmp = compare(auth, MONTH, statementId).andExpect(status().isCreated()).andExpect(jsonPath("$.data.status").value("MISMATCH"))
				.andExpect(jsonPath("$.data.items[0].type").value("BASE_PAY")).andExpect(jsonPath("$.data.items[0].actual").value(1900000))
				.andExpect(jsonPath("$.data.items[0].difference").value(greaterThan(500000)))
				.andExpect(jsonPath("$.data.items[?(@.type=='MEAL_DEDUCTION')].difference").value(hasItem(0))) // 숙식비는 계약대로 공제
				.andExpect(jsonPath("$.data.actualAmount").value(1550000)).andExpect(jsonPath("$.data.differenceAmount").value(greaterThan(900000)))
				.andExpect(jsonPath("$.data.uncertainties[*].code").value(hasItem("MISSING_BREAK")))
				.andExpect(jsonPath("$.data.appliedConditions.unsupportedRules").value(hasItem("주휴수당")))
				.andExpect(jsonPath("$.data.actions").value(hasItem("CREATE_COMPLAINT"))).andExpect(jsonPath("$.data.explanation.text").value(startsWith("예상 임금은")))
				.andExpect(jsonPath("$.data.disclaimer").exists()).andReturn().getResponse().getContentAsString();
		String cmpId = JsonPath.read(cmp, "$.data.id");
		int expected = JsonPath.read(cmp, "$.data.expectedAmount");
		org.junit.jupiter.api.Assertions.assertEquals(expected - 1550000, (int) JsonPath.read(cmp, "$.data.differenceAmount"));
		org.junit.jupiter.api.Assertions.assertTrue(expected > 2_900_000 && expected < 3_300_000, "expected=" + expected); // 10,320원 x 약 260~270시간 + 일 2시간 연장 가산(0.5배)

		mvc.perform(get("/api/v1/payroll/comparisons").param("month", MONTH).header("Authorization", auth)).andExpect(jsonPath("$.data.state").value("COMPARED"))
				.andExpect(jsonPath("$.data.comparison.id").value(cmpId)).andExpect(jsonPath("$.data.comparison.status").value("MISMATCH"));

		// 조회 시점의 사용자 언어로 설명을 다시 만든다
		mvc.perform(patch("/api/v1/users/me").header("Authorization", auth).contentType(JSON).content("{\"language\":\"vi\"}")).andExpect(status().isOk());
		mvc.perform(get("/api/v1/payroll/comparisons/" + cmpId).header("Authorization", auth)).andExpect(jsonPath("$.data.explanation.language").value("vi"))
				.andExpect(jsonPath("$.data.explanation.text").value(startsWith("Lương dự kiến"))).andExpect(jsonPath("$.data.items[0].label").value("Lương cơ bản"));
		mvc.perform(patch("/api/v1/users/me").header("Authorization", auth).contentType(JSON).content("{\"language\":\"ko\"}")).andExpect(status().isOk());

		// 수령액만 입력(총액 비교, 항목 없음)
		String manualId = read(mvc.perform(post("/api/v1/payroll/statements/manual").header("Authorization", auth).contentType(JSON)
				.content("{\"month\":\"" + MONTH + "\",\"receivedAmount\":1850000,\"paidAt\":\"2026-11-10\"}"))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.data.confirmed").value(true)), "$.data.statementId");
		compare(auth, MONTH, manualId).andExpect(jsonPath("$.data.items.length()").value(0)).andExpect(jsonPath("$.data.actualAmount").value(1850000))
				.andExpect(jsonPath("$.data.status").value("MISMATCH"));

		// 명세서 사진 업로드 → 분석(비동기) → 미확정이면 비교 불가 → 수정·확정
		String upId = read(mvc.perform(multipart("/api/v1/payroll/statements").file(new MockMultipartFile("file", "s.png", "image/png", PNG)).header("Authorization", auth))
				.andExpect(status().isAccepted()), "$.data.statementId");
		waitDone(auth, "/api/v1/payroll/statements/" + upId);
		mvc.perform(get("/api/v1/payroll/statements/" + upId).header("Authorization", auth)).andExpect(jsonPath("$.data.status").value("DONE"))
				.andExpect(jsonPath("$.data.items.length()").value(2)).andExpect(jsonPath("$.data.netAmount").value(1550000)).andExpect(jsonPath("$.data.confirmed").value(false))
				.andExpect(jsonPath("$.data.month").value(MONTH)).andExpect(jsonPath("$.data.file.hash").exists());
		compare(auth, MONTH, upId).andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("STATEMENT_NOT_CONFIRMED"));
		mvc.perform(patch("/api/v1/payroll/statements/" + upId).header("Authorization", auth).contentType(JSON)
				.content("{\"items\":[{\"type\":\"BASE_PAY\",\"label\":\"기본급\",\"amount\":-5}]}")).andExpect(status().isBadRequest());
		mvc.perform(patch("/api/v1/payroll/statements/" + upId).header("Authorization", auth).contentType(JSON)
				.content("{\"items\":[{\"type\":\"WHATEVER\",\"label\":\"x\",\"amount\":5}]}")).andExpect(status().isBadRequest());
		mvc.perform(patch("/api/v1/payroll/statements/" + upId).header("Authorization", auth).contentType(JSON).content("{}")).andExpect(status().isBadRequest());
		mvc.perform(patch("/api/v1/payroll/statements/" + upId).header("Authorization", auth).contentType(JSON)
				.content("{\"items\":[{\"type\":\"BASE_PAY\",\"label\":\"기본급\",\"amount\":3500000},{\"type\":\"TAX_INSURANCE\",\"label\":\"4대보험\",\"amount\":100000}],\"confirm\":true}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.netAmount").value(3400000)).andExpect(jsonPath("$.data.confirmed").value(true))
				.andExpect(jsonPath("$.data.grossAmount").value(3500000)).andExpect(jsonPath("$.data.deductionTotal").value(100000));
		// 충분히 지급 → 일치, 세금·보험 공제는 근거 확인 필요로 표시
		compare(auth, MONTH, upId).andExpect(jsonPath("$.data.status").value("MATCH")).andExpect(jsonPath("$.data.actions.length()").value(0))
				.andExpect(jsonPath("$.data.uncertainties[*].code").value(hasItem("UNCLEAR_DEDUCTION")));

		// 오류 케이스
		compare(auth, "2026-13", upId).andExpect(status().isBadRequest());
		compare(auth, MONTH, "nope").andExpect(status().isNotFound());
		String other = signup("payroll-other@example.com");
		mvc.perform(get("/api/v1/payroll/statements/" + upId).header("Authorization", other)).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("명세서를 찾을 수 없습니다."));
		mvc.perform(get("/api/v1/payroll/comparisons/" + cmpId).header("Authorization", other)).andExpect(status().isNotFound());
	}

	@Test
	void comparisonPreconditions() throws Exception {
		String auth = signup("payroll-pre@example.com");
		String stmt = read(mvc.perform(post("/api/v1/payroll/statements/manual").header("Authorization", auth).contentType(JSON)
				.content("{\"month\":\"" + MONTH + "\",\"receivedAmount\":1000000}")).andExpect(status().isCreated()), "$.data.statementId");
		compare(auth, MONTH, stmt).andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("CONTRACT_NOT_CONFIRMED"));

		// 계약서 확정 후에도 근무기록이 없으면 비교 불가
		String cid = read(mvc.perform(multipart("/api/v1/contracts").file(new MockMultipartFile("file", "c.png", "image/png", PNG)).header("Authorization", auth))
				.andExpect(status().isAccepted()), "$.data.contractId");
		waitDone(auth, "/api/v1/contracts/" + cid);
		mvc.perform(post("/api/v1/contracts/" + cid + "/confirm").header("Authorization", auth)).andExpect(status().isOk());
		compare(auth, MONTH, stmt).andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("NO_ATTENDANCE_DATA"));
		mvc.perform(get("/api/v1/payroll/comparisons").param("month", MONTH).header("Authorization", auth)).andExpect(jsonPath("$.data.state").value("READY"));
		mvc.perform(get("/api/v1/payroll/comparisons").param("month", "2020-01").header("Authorization", auth)).andExpect(jsonPath("$.data.state").value("NOT_SUBMITTED"));
	}
}
