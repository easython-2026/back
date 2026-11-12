package com.easython;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easython.complaint.PdfFonts;
import com.jayway.jsonpath.JsonPath;
import java.time.YearMonth;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(properties = { "app.storage.local-dir=build/test-uploads", "app.ai.provider=mock" })
class ComplaintFlowTest {
	@Autowired WebApplicationContext ctx;
	@Autowired PdfFonts fonts;
	MockMvc mvc;

	static final String JSON = MediaType.APPLICATION_JSON_VALUE;
	static final String MONTH = YearMonth.now().minusMonths(1).toString();

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();
	}

	String signup(String email, String lang) throws Exception {
		String body = """
				{"email":"%s","password":"Test1234!","name":"Tuan","language":"%s",
				 "consents":{"privacy":true,"location":true,"camera":true},"agreedAt":"2026-11-11T09:00:00+09:00"}""".formatted(email, lang);
		String r = mvc.perform(post("/api/v1/auth/signup").contentType(JSON).content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return "Bearer " + JsonPath.read(r, "$.data.accessToken");
	}

	String str(ResultActions a, String path) throws Exception {
		return JsonPath.read(a.andReturn().getResponse().getContentAsString(), path);
	}

	/** 데모 시드 + 급여 비교(불일치) → comparisonId */
	String mismatchComparison(String auth) throws Exception {
		String seeded = mvc.perform(post("/api/v1/demo/seed").header("Authorization", auth).contentType(JSON).content("{\"month\":\"" + MONTH + "\"}"))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return str(mvc.perform(post("/api/v1/payroll/comparisons").header("Authorization", auth).contentType(JSON)
				.content("{\"month\":\"" + MONTH + "\",\"statementId\":\"" + JsonPath.read(seeded, "$.data.statementId") + "\"}"))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.data.status").value("MISMATCH")), "$.data.id");
	}

	void waitTranslated(String auth, String id) throws Exception {
		for (int i = 0; i < 100; i++) {
			String s = mvc.perform(get("/api/v1/complaints/" + id).header("Authorization", auth)).andReturn().getResponse().getContentAsString();
			if ("DONE".equals(JsonPath.read(s, "$.data.status")) && !"PROCESSING".equals(JsonPath.read(s, "$.data.translationStatus"))) return;
			if ("FAILED".equals(JsonPath.read(s, "$.data.status"))) throw new AssertionError("generation failed: " + s);
			Thread.sleep(100);
		}
		throw new AssertionError("complaint generation did not finish");
	}

	@Test
	void complaintLifecycle() throws Exception {
		String auth = signup("complaint@example.com", "vi");
		String cmpId = mismatchComparison(auth);

		// 생성(202) → 한국어 초안 + 번역 완료까지 폴링
		String id = str(mvc.perform(post("/api/v1/complaints").header("Authorization", auth).contentType(JSON).content("{\"comparisonId\":\"" + cmpId + "\"}"))
				.andExpect(status().isAccepted()).andExpect(jsonPath("$.data.status").value("PROCESSING")), "$.data.complaintId");
		waitTranslated(auth, id);
		String d = mvc.perform(get("/api/v1/complaints/" + id).header("Authorization", auth)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.reviewed").value(false)).andExpect(jsonPath("$.data.translationStatus").value("DONE"))
				.andExpect(jsonPath("$.data.complainant.name").value("Tuan")).andExpect(jsonPath("$.data.respondent.name").value("OO농장"))
				.andExpect(jsonPath("$.data.draft.ko.title").value("임금 체불 진정서")).andExpect(jsonPath("$.data.draft.ko.body").value(containsString("OO농장")))
				.andExpect(jsonPath("$.data.draft.ko.claims[0].label").value("기본급 차액"))
				.andExpect(jsonPath("$.data.draft.translated.language").value("vi")).andExpect(jsonPath("$.data.draft.translated.body").value(startsWith("[데모 번역:vi]")))
				.andExpect(jsonPath("$.data.summary.ko").exists()).andExpect(jsonPath("$.data.summary.translated").exists())
				.andExpect(jsonPath("$.data.officialGuide.steps.length()").value(4)).andExpect(jsonPath("$.data.officialGuide.agencyName").value(startsWith("Văn phòng Lao động")))
				.andExpect(jsonPath("$.data.officialGuide.contacts[0].phone").value("1350")).andExpect(jsonPath("$.data.disclaimer").value(startsWith("Đây chỉ là bản nháp")))
				.andExpect(jsonPath("$.data.evidences.length()").value(greaterThan(10))).andReturn().getResponse().getContentAsString();
		int unpaid = JsonPath.read(d, "$.data.draft.ko.unpaidAmount");
		org.junit.jupiter.api.Assertions.assertTrue(unpaid > 900_000, "unpaid=" + unpaid); // 비교 결과의 차액과 동일해야 함
		String evId1 = JsonPath.read(d, "$.data.evidences[0].id");
		String evId2 = JsonPath.read(d, "$.data.evidences[1].id");

		// 확정 전에는 PDF 불가
		mvc.perform(get("/api/v1/complaints/" + id + "/pdf").header("Authorization", auth)).andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("NOT_REVIEWED"));

		// 수정: 검증 → 수정하면 reviewed=false → 재번역 중에는 확정 불가 → 번역 완료 후 확정
		mvc.perform(patch("/api/v1/complaints/" + id).header("Authorization", auth).contentType(JSON).content("{}")).andExpect(status().isBadRequest());
		mvc.perform(patch("/api/v1/complaints/" + id).header("Authorization", auth).contentType(JSON).content("{\"draft\":{\"unpaidAmount\":-5}}")).andExpect(status().isBadRequest());
		mvc.perform(patch("/api/v1/complaints/" + id).header("Authorization", auth).contentType(JSON).content("{\"periodStart\":\"2026-12-01\",\"periodEnd\":\"2026-11-01\"}")).andExpect(status().isBadRequest());
		mvc.perform(patch("/api/v1/complaints/" + id).header("Authorization", auth).contentType(JSON)
				.content("{\"draft\":{\"unpaidAmount\":900000,\"body\":\"수정된 본문\\n\\n둘째 문단\"},\"retranslate\":true,\"confirm\":true}")).andExpect(status().isConflict());
		mvc.perform(patch("/api/v1/complaints/" + id).header("Authorization", auth).contentType(JSON)
				.content("{\"draft\":{\"unpaidAmount\":900000,\"body\":\"수정된 본문\\n\\n둘째 문단\"},\"retranslate\":true}")).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.reviewed").value(false)).andExpect(jsonPath("$.data.draft.ko.unpaidAmount").value(900000));
		waitTranslated(auth, id);
		mvc.perform(get("/api/v1/complaints/" + id).header("Authorization", auth)).andExpect(jsonPath("$.data.draft.translated.body").value(startsWith("[데모 번역:vi] 수정된 본문")))
				.andExpect(jsonPath("$.data.draft.ko.title").value("임금 체불 진정서"));
		mvc.perform(patch("/api/v1/complaints/" + id).header("Authorization", auth).contentType(JSON).content("{\"confirm\":true}")).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.reviewed").value(true));
		// 확정 후 다시 고치면 reviewed 는 false 로 돌아간다
		mvc.perform(patch("/api/v1/complaints/" + id).header("Authorization", auth).contentType(JSON).content("{\"respondent\":{\"ownerName\":\"박OO\"}}"))
				.andExpect(jsonPath("$.data.reviewed").value(false)).andExpect(jsonPath("$.data.respondent.ownerName").value("박OO"));

		// 증거 목록 교체: 빈 배열 400 / 모르는 id 404 / 정상은 reviewed=false 로 되돌림
		mvc.perform(put("/api/v1/complaints/" + id + "/evidences").header("Authorization", auth).contentType(JSON).content("{\"evidenceIds\":[]}")).andExpect(status().isBadRequest());
		mvc.perform(put("/api/v1/complaints/" + id + "/evidences").header("Authorization", auth).contentType(JSON).content("{\"evidenceIds\":[\"" + evId1 + "\",\"GPS_LOG-nope\"]}"))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
		mvc.perform(put("/api/v1/complaints/" + id + "/evidences").header("Authorization", auth).contentType(JSON)
				.content("{\"evidenceIds\":[\"" + evId1 + "\",\"" + evId2 + "\"]}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.evidences.length()").value(2));
		mvc.perform(get("/api/v1/complaints/" + id).header("Authorization", auth)).andExpect(jsonPath("$.data.evidences.length()").value(2));
		mvc.perform(patch("/api/v1/complaints/" + id).header("Authorization", auth).contentType(JSON).content("{\"confirm\":true}")).andExpect(jsonPath("$.data.reviewed").value(true));

		// PDF: 한·모국어 대조 + 증거 목록 + 면책
		ResultActions pdf = mvc.perform(get("/api/v1/complaints/" + id + "/pdf").header("Authorization", auth));
		if (fonts.available()) {
			pdf.andExpect(status().isOk()).andExpect(content().contentType(MediaType.APPLICATION_PDF))
					.andExpect(header().string("Content-Disposition", "attachment; filename=\"complaint_" + id + ".pdf\""));
			byte[] bytes = pdf.andReturn().getResponse().getContentAsByteArray();
			org.junit.jupiter.api.Assertions.assertEquals("%PDF", new String(bytes, 0, 4));
			try (var doc = Loader.loadPDF(bytes)) {
				String text = new PDFTextStripper().getText(doc);
				org.junit.jupiter.api.Assertions.assertTrue(text.contains("임금 체불 진정서"), text);
				org.junit.jupiter.api.Assertions.assertTrue(text.contains("[데모 번역:vi]"), text);
				org.junit.jupiter.api.Assertions.assertTrue(text.contains("SHA-256"), text);
				org.junit.jupiter.api.Assertions.assertTrue(text.contains("박OO"), text);
			}
		} else {
			System.out.println("PDF 폰트가 없어 PDF 내용 검증은 건너뜀(PDF_FONT_PATH 지정 필요)");
		}

		// 목록
		mvc.perform(get("/api/v1/complaints").header("Authorization", auth)).andExpect(status().isOk()).andExpect(jsonPath("$.data.complaints[0].id").value(id))
				.andExpect(jsonPath("$.data.complaints[0].reviewed").value(true)).andExpect(jsonPath("$.data.complaints[0].unpaidAmount").value(900000))
				.andExpect(jsonPath("$.data.pageInfo.totalCount").value(1));

		// 남의 진정서·알 수 없는 id
		String other = signup("complaint-other@example.com", "ko");
		mvc.perform(get("/api/v1/complaints/" + id).header("Authorization", other)).andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("진정서를 찾을 수 없습니다."));
		mvc.perform(get("/api/v1/complaints/" + id + "/pdf").header("Authorization", other)).andExpect(status().isNotFound());
		mvc.perform(post("/api/v1/complaints").header("Authorization", other).contentType(JSON).content("{\"comparisonId\":\"" + cmpId + "\"}")).andExpect(status().isNotFound());
		mvc.perform(post("/api/v1/complaints").header("Authorization", auth).contentType(JSON).content("{\"comparisonId\":\"" + cmpId + "\",\"periodStart\":\"2026-12-01\",\"periodEnd\":\"2026-11-01\"}"))
				.andExpect(status().isBadRequest());
		mvc.perform(post("/api/v1/complaints").header("Authorization", auth).contentType(JSON).content("{}")).andExpect(status().isBadRequest());
	}

	@Test
	void preconditions() throws Exception {
		// 일치(MATCH) 결과로는 만들 수 없다
		String auth = signup("complaint-match@example.com", "ko");
		String seeded = mvc.perform(post("/api/v1/demo/seed").header("Authorization", auth).contentType(JSON).content("{\"month\":\"" + MONTH + "\"}"))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		String manual = str(mvc.perform(post("/api/v1/payroll/statements/manual").header("Authorization", auth).contentType(JSON)
				.content("{\"month\":\"" + MONTH + "\",\"receivedAmount\":99999999}")).andExpect(status().isCreated()), "$.data.statementId");
		String matchCmp = str(mvc.perform(post("/api/v1/payroll/comparisons").header("Authorization", auth).contentType(JSON)
				.content("{\"month\":\"" + MONTH + "\",\"statementId\":\"" + manual + "\"}")).andExpect(jsonPath("$.data.status").value("MATCH")), "$.data.id");
		mvc.perform(post("/api/v1/complaints").header("Authorization", auth).contentType(JSON).content("{\"comparisonId\":\"" + matchCmp + "\"}"))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("NOT_MISMATCH"));

		// 사업장 정보가 없으면 만들 수 없다(근무장소만 등록한 사용자는 데모 시드가 사업장을 채우지 않음)
		String nb = signup("complaint-nobiz@example.com", "ko");
		mvc.perform(put("/api/v1/users/me/workplace").header("Authorization", nb).contentType(JSON)
				.content("{\"workplace\":{\"name\":\"OO농장\",\"address\":\"포천\",\"latitude\":37.9,\"longitude\":127.2}}")).andExpect(status().isOk());
		String cmp = mismatchComparison(nb);
		mvc.perform(post("/api/v1/complaints").header("Authorization", nb).contentType(JSON).content("{\"comparisonId\":\"" + cmp + "\"}"))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("BUSINESS_INFO_REQUIRED")).andExpect(jsonPath("$.message").value("사업장 정보를 먼저 등록해 주세요."));
		// 사업장을 등록하면 만들 수 있다(한국어 사용자는 번역 없이 같은 텍스트)
		mvc.perform(put("/api/v1/users/me/workplace").header("Authorization", nb).contentType(JSON)
				.content("{\"business\":{\"name\":\"OO농장\",\"address\":\"포천\",\"ownerName\":\"김OO\"}}")).andExpect(status().isOk());
		String id = str(mvc.perform(post("/api/v1/complaints").header("Authorization", nb).contentType(JSON).content("{\"comparisonId\":\"" + cmp + "\"}")).andExpect(status().isAccepted()), "$.data.complaintId");
		waitTranslated(nb, id);
		mvc.perform(get("/api/v1/complaints/" + id).header("Authorization", nb)).andExpect(jsonPath("$.data.draft.translated.language").value("ko"))
				.andExpect(jsonPath("$.data.evidences[*].type").value(hasItem("GPS_LOG")));
	}
}
