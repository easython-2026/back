package com.easython;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easython.auth.PasswordResetCodeRepository;
import com.easython.user.UserRepository;
import com.jayway.jsonpath.JsonPath;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(properties = { "app.storage.local-dir=build/test-uploads", "app.ai.provider=mock" })
class AccountFlowTest {
	@Autowired WebApplicationContext ctx;
	@Autowired PasswordResetCodeRepository codes;
	@Autowired UserRepository users;
	@Autowired PasswordEncoder encoder;
	MockMvc mvc;

	static final String JSON = MediaType.APPLICATION_JSON_VALUE;
	static final YearMonth LAST = YearMonth.now().minusMonths(1);
	static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4, 5 };

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();
	}

	String signup(String email) throws Exception {
		String body = """
				{"email":"%s","password":"Test1234!","name":"Tuan","language":"ko",
				 "consents":{"privacy":true,"location":true,"camera":true},"agreedAt":"2026-11-11T09:00:00+09:00"}""".formatted(email);
		return "Bearer " + JsonPath.read(mvc.perform(post("/api/v1/auth/signup").contentType(JSON).content(body)).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString(), "$.data.accessToken");
	}

	ResultActions login(String email, String pw) throws Exception {
		return mvc.perform(post("/api/v1/auth/login").contentType(JSON).content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, pw)));
	}

	String seed(String auth) throws Exception {
		return mvc.perform(post("/api/v1/demo/seed").header("Authorization", auth).contentType(JSON).content("{\"month\":\"" + LAST + "\"}"))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
	}

	void waitUntil(java.util.concurrent.Callable<Boolean> cond) throws Exception {
		for (int i = 0; i < 100; i++) {
			if (cond.call()) return;
			Thread.sleep(100);
		}
		throw new AssertionError("timeout");
	}

	@Test
	void consents() throws Exception {
		String auth = signup("acc-consent@example.com");
		mvc.perform(get("/api/v1/users/me/consents").header("Authorization", auth)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.privacy").value(true)).andExpect(jsonPath("$.data.location").value(true))
				.andExpect(jsonPath("$.data.updatedAt").value("2026-11-11T09:00:00+09:00")).andExpect(jsonPath("$.data.shares.length()").value(0));
		mvc.perform(patch("/api/v1/users/me/consents").header("Authorization", auth).contentType(JSON).content("{\"privacy\":false}")).andExpect(status().isBadRequest());
		mvc.perform(patch("/api/v1/users/me/consents").header("Authorization", auth).contentType(JSON).content("{}")).andExpect(status().isBadRequest());
		mvc.perform(patch("/api/v1/users/me/consents").header("Authorization", auth).contentType(JSON).content("{\"location\":false}")).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.location").value(false)).andExpect(jsonPath("$.data.camera").value(true));
		// 위치 동의를 끄면 출퇴근 GPS 인증 불가
		mvc.perform(post("/api/v1/attendance/location-check").header("Authorization", auth).contentType(JSON)
				.content("{\"type\":\"CLOCK_IN\",\"latitude\":37.9,\"longitude\":127.2,\"accuracyMeters\":10}")).andExpect(status().isForbidden());
	}

	@Test
	void passwordReset() throws Exception {
		String email = "acc-reset@example.com";
		signup(email);
		// 없는 이메일도 같은 응답(계정 존재 여부 비노출)
		mvc.perform(post("/api/v1/auth/password/reset-request").contentType(JSON).content("{\"email\":\"nobody@example.com\"}")).andExpect(status().isOk())
				.andExpect(jsonPath("$.message").value("인증 코드를 보냈습니다."));
		mvc.perform(post("/api/v1/auth/password/reset-request").contentType(JSON).content("{\"email\":\"bad\"}")).andExpect(status().isBadRequest());
		mvc.perform(post("/api/v1/auth/password/reset-request").contentType(JSON).content("{\"email\":\"" + email + "\"}")).andExpect(status().isOk());

		// 메일 대신 테스트에서 저장된 코드(해시)를 알려진 값으로 바꿔 확인
		String userId = users.findByEmail(email).orElseThrow().getId();
		var code = codes.findFirstByUserIdAndUsedFalseOrderByCreatedAtDesc(userId).orElseThrow();
		code.setCodeHash(encoder.encode("123456"));
		codes.save(code);

		mvc.perform(post("/api/v1/auth/password/reset").contentType(JSON).content("{\"email\":\"" + email + "\",\"code\":\"123456\",\"newPassword\":\"short\"}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
		mvc.perform(post("/api/v1/auth/password/reset").contentType(JSON).content("{\"email\":\"" + email + "\",\"code\":\"000000\",\"newPassword\":\"NewPass1234\"}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("INVALID_CODE"));
		mvc.perform(post("/api/v1/auth/password/reset").contentType(JSON).content("{\"email\":\"" + email + "\",\"code\":\"123456\",\"newPassword\":\"NewPass1234\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.message").value("비밀번호가 변경되었습니다."));
		mvc.perform(post("/api/v1/auth/password/reset").contentType(JSON).content("{\"email\":\"" + email + "\",\"code\":\"123456\",\"newPassword\":\"Again1234\"}"))
				.andExpect(status().isBadRequest()); // 한 번 쓴 코드는 재사용 불가
		login(email, "Test1234!").andExpect(status().isUnauthorized());
		login(email, "NewPass1234").andExpect(status().isOk());

		// 같은 이메일 요청 제한(15분에 5번)
		for (int i = 0; i < 4; i++) mvc.perform(post("/api/v1/auth/password/reset-request").contentType(JSON).content("{\"email\":\"" + email + "\"}")).andExpect(status().isOk());
		mvc.perform(post("/api/v1/auth/password/reset-request").contentType(JSON).content("{\"email\":\"" + email + "\"}"))
				.andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.errorCode").value("TOO_MANY_REQUESTS"));
	}

	@Test
	void anomaliesAndNoRecordIssues() throws Exception {
		String auth = signup("acc-anomaly@example.com");
		int days = JsonPath.read(seed(auth), "$.data.attendanceRecords");
		// 시드는 매일 10시간 근무·휴게 미입력 → 장시간 무휴게 패턴
		mvc.perform(get("/api/v1/attendance/anomalies").param("month", LAST.toString()).header("Authorization", auth)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.patterns[0].type").value("LONG_HOURS_NO_BREAK")).andExpect(jsonPath("$.data.patterns[0].dates.length()").value(days))
				.andExpect(jsonPath("$.data.patterns[0].message").value(startsWith("8시간 이상"))).andExpect(jsonPath("$.data.disclaimer").exists());
		mvc.perform(get("/api/v1/attendance/anomalies").param("month", "bad").header("Authorization", auth)).andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/attendance/issues").param("month", LAST.toString()).header("Authorization", auth))
				.andExpect(jsonPath("$.data.issues[*].type").value(not(hasItem("NO_RECORD")))); // 계약 근무요일을 모두 기록함

		// 근무기록을 삭제 요청하면 계약상 근무요일(월~토)이 전부 '기록 없음'으로 잡힌다
		mvc.perform(post("/api/v1/users/me/data-deletion-requests").header("Authorization", auth).contentType(JSON).content("{\"targets\":[\"NOPE\"]}"))
				.andExpect(status().isBadRequest());
		mvc.perform(post("/api/v1/users/me/data-deletion-requests").header("Authorization", auth).contentType(JSON).content("{\"targets\":[\"ATTENDANCE\"]}"))
				.andExpect(status().isAccepted()).andExpect(jsonPath("$.data.status").value("RECEIVED"));
		waitUntil(() -> (int) JsonPath.read(mvc.perform(get("/api/v1/attendance/monthly").param("month", LAST.toString()).header("Authorization", auth))
				.andReturn().getResponse().getContentAsString(), "$.data.summary.workDays") == 0);
		long workdays = LAST.atDay(1).datesUntil(LAST.atEndOfMonth().plusDays(1)).filter(d -> d.getDayOfWeek() != DayOfWeek.SUNDAY).count();
		mvc.perform(get("/api/v1/attendance/issues").param("month", LAST.toString()).header("Authorization", auth))
				.andExpect(jsonPath("$.data.count").value((int) workdays)).andExpect(jsonPath("$.data.issues[0].type").value("NO_RECORD"))
				.andExpect(jsonPath("$.data.issues[0].recordId").doesNotExist());
	}

	@Test
	void deletionRequestBlockedWhileSharedAndAccountDeletion() throws Exception {
		String email = "acc-delete@example.com";
		String auth = signup(email);
		String seeded = seed(auth);
		String cmp = JsonPath.read(mvc.perform(post("/api/v1/payroll/comparisons").header("Authorization", auth).contentType(JSON)
				.content("{\"month\":\"" + LAST + "\",\"statementId\":\"" + JsonPath.read(seeded, "$.data.statementId") + "\"}")).andReturn().getResponse().getContentAsString(), "$.data.id");
		List<String> agencies = JsonPath.read(mvc.perform(get("/api/v1/consultation-agencies").header("Authorization", auth)).andReturn().getResponse().getContentAsString(), "$.data.agencies[*].id");
		String consultation = JsonPath.read(mvc.perform(post("/api/v1/consultations").header("Authorization", auth).contentType(JSON).content("""
				{"agencyId":"%s","comparisonId":"%s","consent":{"sharedScope":["ATTENDANCE"],"agreedAt":"2026-11-11T11:00:00+09:00"}}""".formatted(agencies.get(0), cmp)))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.data.consultationId");
		mvc.perform(get("/api/v1/users/me/consents").header("Authorization", auth)).andExpect(jsonPath("$.data.shares[0].consultationId").value(consultation))
				.andExpect(jsonPath("$.data.shares[0].revokedAt").doesNotExist());

		// 공유 중인 자료는 삭제·탈퇴 불가
		mvc.perform(post("/api/v1/users/me/data-deletion-requests").header("Authorization", auth).contentType(JSON).content("{\"targets\":[\"ATTENDANCE\"]}"))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("ACTIVE_CONSULTATION"));
		mvc.perform(post("/api/v1/users/me/data-deletion-requests").header("Authorization", auth).contentType(JSON).content("{\"targets\":[\"COMPLAINTS\"]}"))
				.andExpect(status().isAccepted()); // 진정서는 공유 범위 밖이라 가능
		mvc.perform(delete("/api/v1/users/me").header("Authorization", auth).contentType(JSON).content("{\"password\":\"wrong\"}"))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("비밀번호가 올바르지 않습니다."));
		mvc.perform(delete("/api/v1/users/me").header("Authorization", auth).contentType(JSON).content("{\"password\":\"Test1234!\"}"))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("진행 중인 상담이 있어 탈퇴할 수 없습니다."));

		// 공유 철회 후 탈퇴 → 자료 삭제, 로그인·토큰 사용 불가, 같은 이메일로 재가입 가능
		mvc.perform(delete("/api/v1/consultations/" + consultation + "/consent").header("Authorization", auth)).andExpect(status().isOk());
		mvc.perform(multipart("/api/v1/contracts").file(new MockMultipartFile("file", "c.png", "image/png", PNG)).header("Authorization", auth)).andExpect(status().isAccepted());
		mvc.perform(delete("/api/v1/users/me").header("Authorization", auth).contentType(JSON).content("{\"password\":\"Test1234!\"}")).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.deletedAt").exists()).andExpect(jsonPath("$.data.retentionNotice").value(startsWith("계정과")));
		mvc.perform(get("/api/v1/users/me").header("Authorization", auth)).andExpect(status().isUnauthorized());
		login(email, "Test1234!").andExpect(status().isUnauthorized());
		var deleted = users.findAll().stream().filter(u -> u.getDeletedAt() != null && u.getEmail().startsWith("deleted-")).findFirst().orElseThrow();
		org.junit.jupiter.api.Assertions.assertNull(deleted.getBusinessName());
		String again = signup(email);
		mvc.perform(get("/api/v1/attendance/monthly").param("month", LAST.toString()).header("Authorization", again)).andExpect(jsonPath("$.data.summary.workDays").value(0));
		org.junit.jupiter.api.Assertions.assertTrue(LocalDate.now().isAfter(LAST.atEndOfMonth()));
	}
}
