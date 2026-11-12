package com.easython;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
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
class ContractFlowTest {
	@Autowired WebApplicationContext ctx;
	MockMvc mvc;

	static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4, 5 };
	static final String JSON = MediaType.APPLICATION_JSON_VALUE;

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();
	}

	String signup(String email, String lang) throws Exception {
		String body = """
				{"email":"%s","password":"Test1234!","name":"Tuan","language":"%s",
				 "consents":{"privacy":true,"location":true,"camera":true},"agreedAt":"2026-11-11T09:00:00+09:00"}""".formatted(email, lang);
		String r = mvc.perform(post("/api/v1/auth/signup").contentType(JSON).content(body)).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		return "Bearer " + JsonPath.read(r, "$.data.accessToken");
	}

	ResultActions upload(String auth, byte[] bytes) throws Exception {
		return mvc.perform(multipart("/api/v1/contracts").file(new MockMultipartFile("file", "c.png", "image/png", bytes))
				.param("source", "CAMERA").header("Authorization", auth));
	}

	/** 업로드 후 분석이 끝날 때까지 폴링(비동기). */
	String uploadAndWait(String auth) throws Exception {
		String r = upload(auth, PNG).andExpect(status().isAccepted()).andExpect(jsonPath("$.data.status").value("PROCESSING"))
				.andReturn().getResponse().getContentAsString();
		String id = JsonPath.read(r, "$.data.contractId");
		waitDone(auth, id);
		return id;
	}

	void waitDone(String auth, String id) throws Exception {
		for (int i = 0; i < 100; i++) {
			String s = mvc.perform(get("/api/v1/contracts/" + id).header("Authorization", auth)).andReturn().getResponse().getContentAsString();
			if (!"PROCESSING".equals(JsonPath.read(s, "$.data.status"))) return;
			Thread.sleep(100);
		}
		throw new AssertionError("analysis did not finish");
	}

	@Test
	void contractFlow() throws Exception {
		String auth = signup("contract@example.com", "vi");

		// 파일 검증
		upload(auth, "not a contract".getBytes()).andExpect(status().isUnsupportedMediaType());
		upload(auth, new byte[11 * 1024 * 1024]).andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.errorCode").value("FILE_TOO_LARGE"));
		mvc.perform(multipart("/api/v1/contracts").header("Authorization", auth)).andExpect(status().isBadRequest());

		// 업로드(202) → 분석 완료
		String id = uploadAndWait(auth);
		String detail = mvc.perform(get("/api/v1/contracts/" + id).header("Authorization", auth)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("DONE")).andExpect(jsonPath("$.data.confirmed").value(false))
				.andExpect(jsonPath("$.data.file.hash").exists()).andExpect(jsonPath("$.data.terms.wageType").value("HOURLY"))
				.andExpect(jsonPath("$.data.terms.mealDeduction").value(350000)).andExpect(jsonPath("$.data.terms.breakMinutesPerDay").doesNotExist())
				.andExpect(jsonPath("$.data.terms.workDays.length()").value(6)).andExpect(jsonPath("$.data.translation.language").value("vi"))
				.andExpect(jsonPath("$.data.clauses.length()").value(4)).andExpect(jsonPath("$.data.clauses[2].translatedText").value("Mỗi tháng khấu trừ 350.000 won tiền ăn ở."))
				.andExpect(jsonPath("$.data.reviews[*].type").value(hasItem("MEAL_LODGING_DEDUCTION")))
				.andExpect(jsonPath("$.data.reviews[*].type").value(hasItem("BREAK_TIME_MISSING")))
				.andExpect(jsonPath("$.data.reviews[*].type").value(not(hasItem("MIN_WAGE"))))
				.andExpect(jsonPath("$.data.disclaimer").value(org.hamcrest.Matchers.startsWith("Đây chỉ là bản nháp")))
				.andReturn().getResponse().getContentAsString();
		java.util.List<String> mealClauseIds = JsonPath.read(detail, "$.data.reviews[?(@.type=='MEAL_LODGING_DEDUCTION')].clauseId");
		String clauseId2 = JsonPath.read(detail, "$.data.clauses[2].id");
		org.junit.jupiter.api.Assertions.assertEquals(clauseId2, mealClauseIds.get(0)); // 숙식비 조항에 하이라이트 연결

		// 조건 수정 → 검토 항목 재계산
		mvc.perform(patch("/api/v1/contracts/" + id + "/terms").header("Authorization", auth).contentType(JSON).content("{}")).andExpect(status().isBadRequest());
		mvc.perform(patch("/api/v1/contracts/" + id + "/terms").header("Authorization", auth).contentType(JSON).content("{\"wageType\":\"DAILY\"}")).andExpect(status().isBadRequest());
		mvc.perform(patch("/api/v1/contracts/" + id + "/terms").header("Authorization", auth).contentType(JSON).content("{\"payday\":32}")).andExpect(status().isBadRequest());
		mvc.perform(patch("/api/v1/contracts/" + id + "/terms").header("Authorization", auth).contentType(JSON)
				.content("{\"wageAmount\":9500,\"breakMinutesPerDay\":60}")).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.terms.wageAmount").value(9500)).andExpect(jsonPath("$.data.reviews[*].type").value(hasItem("MIN_WAGE")))
				.andExpect(jsonPath("$.data.reviews[*].type").value(not(hasItem("BREAK_TIME_MISSING"))));
		// 월급 환산: 6일×9시간 근무(주휴 포함) 150만원은 최저임금 미달, 300만원은 충족
		mvc.perform(patch("/api/v1/contracts/" + id + "/terms").header("Authorization", auth).contentType(JSON)
				.content("{\"wageType\":\"MONTHLY\",\"wageAmount\":1500000}")).andExpect(jsonPath("$.data.reviews[*].type").value(hasItem("MIN_WAGE")));
		mvc.perform(patch("/api/v1/contracts/" + id + "/terms").header("Authorization", auth).contentType(JSON)
				.content("{\"wageAmount\":3000000}")).andExpect(jsonPath("$.data.reviews[*].type").value(not(hasItem("MIN_WAGE"))));
		mvc.perform(patch("/api/v1/contracts/" + id + "/terms").header("Authorization", auth).contentType(JSON)
				.content("{\"wageType\":\"HOURLY\",\"wageAmount\":9500}")).andExpect(status().isOk());

		// 확정 → 사업장 자동 채움
		mvc.perform(post("/api/v1/contracts/" + id + "/confirm").header("Authorization", auth)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.confirmed").value(true)).andExpect(jsonPath("$.data.businessAutoFilled").value(true));
		mvc.perform(get("/api/v1/users/me/workplace").header("Authorization", auth)).andExpect(jsonPath("$.data.business.fromContract").value(true))
				.andExpect(jsonPath("$.data.business.ownerName").value("김OO"));

		// 재분석: 언어 변경, 사용자가 수정한 값(시급 9500)은 보존
		mvc.perform(post("/api/v1/contracts/" + id + "/reanalyze").header("Authorization", auth).contentType(JSON).content("{\"language\":\"xx\"}"))
				.andExpect(status().isBadRequest());
		mvc.perform(post("/api/v1/contracts/" + id + "/reanalyze").header("Authorization", auth).contentType(JSON).content("{\"language\":\"en\"}"))
				.andExpect(status().isAccepted());
		waitDone(auth, id);
		mvc.perform(get("/api/v1/contracts/" + id).header("Authorization", auth)).andExpect(jsonPath("$.data.translation.language").value("en"))
				.andExpect(jsonPath("$.data.terms.wageAmount").value(9500)).andExpect(jsonPath("$.data.terms.mealDeduction").value(350000))
				.andExpect(jsonPath("$.data.clauses[0].translatedText").value("The hourly wage is KRW 10,320."));

		// 두 번째 계약서는 사업장이 이미 있어 자동 채움 안 함 / 목록 / 페이지 검증
		String id2 = uploadAndWait(auth);
		mvc.perform(post("/api/v1/contracts/" + id2 + "/confirm").header("Authorization", auth)).andExpect(jsonPath("$.data.businessAutoFilled").value(false));
		mvc.perform(get("/api/v1/contracts").header("Authorization", auth)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.contracts.length()").value(2)).andExpect(jsonPath("$.data.contracts[0].id").value(id2))
				.andExpect(jsonPath("$.data.contracts[0].checkCount").value(greaterThanOrEqualTo(2))).andExpect(jsonPath("$.data.pageInfo.totalCount").value(2));
		mvc.perform(get("/api/v1/contracts").param("page", "0").header("Authorization", auth)).andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/contracts").param("size", "101").header("Authorization", auth)).andExpect(status().isBadRequest());

		// 다른 사람 계약서는 404
		String other = signup("othercontract@example.com", "ko");
		mvc.perform(get("/api/v1/contracts/" + id).header("Authorization", other)).andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("계약서를 찾을 수 없습니다."));
		mvc.perform(post("/api/v1/contracts/" + id + "/confirm").header("Authorization", other)).andExpect(status().isNotFound());

		// 삭제
		mvc.perform(delete("/api/v1/contracts/" + id2).header("Authorization", auth)).andExpect(status().isOk());
		mvc.perform(get("/api/v1/contracts/" + id2).header("Authorization", auth)).andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/contracts").header("Authorization", auth)).andExpect(jsonPath("$.data.pageInfo.totalCount").value(1));
	}

	@Test
	void demoSeedCreatesConfirmedSampleContract() throws Exception {
		String auth = signup("democontract@example.com", "ko");
		String month = java.time.YearMonth.now().minusMonths(3).toString();
		String r = mvc.perform(post("/api/v1/demo/seed").header("Authorization", auth).contentType(JSON).content("{\"month\":\"" + month + "\"}"))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.data.contractId").exists()).andReturn().getResponse().getContentAsString();
		String cid = JsonPath.read(r, "$.data.contractId");
		mvc.perform(get("/api/v1/contracts/" + cid).header("Authorization", auth)).andExpect(jsonPath("$.data.status").value("DONE"))
				.andExpect(jsonPath("$.data.confirmed").value(true)).andExpect(jsonPath("$.data.file").doesNotExist())
				.andExpect(jsonPath("$.data.reviews[*].type").value(hasItem("MEAL_LODGING_DEDUCTION")));
	}
}
