package com.easython;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(properties = { "app.storage.local-dir=build/test-uploads", "app.ai.provider=mock" })
class AuthUserFlowTest {
	@Autowired WebApplicationContext ctx;
	MockMvc mvc;

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();
	}

	static final String SIGNUP = """
			{"email":"Tuan@Example.com","password":"Test1234!","name":"Tuan","language":"vi",
			 "consents":{"privacy":true,"location":true,"camera":true},"agreedAt":"2026-11-11T09:00:00+09:00"}""";

	String body(org.springframework.test.web.servlet.ResultActions r) throws Exception {
		return r.andReturn().getResponse().getContentAsString();
	}

	@Test
	void fullFlow() throws Exception {
		mvc.perform(get("/api/v1/languages")).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.languages[?(@.code=='vi')].nameKo").value("베트남어"));

		mvc.perform(get("/api/v1/auth/email/check").param("email", "tuan@example.com"))
				.andExpect(jsonPath("$.data.available").value(true));
		mvc.perform(get("/api/v1/auth/email/check").param("email", "bad")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));

		String signup = body(mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(SIGNUP))
				.andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.message").doesNotExist()));
		String access0 = JsonPath.read(signup, "$.data.accessToken");

		// 중복 이메일(대소문자 무관) / 동의 거부 / 약한 비밀번호
		mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(SIGNUP))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("DUPLICATED_EMAIL"));
		mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
				.content(SIGNUP.replace("\"privacy\":true", "\"privacy\":false").replace("Tuan@", "x@")))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("CONSENT_REQUIRED"));
		mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
				.content(SIGNUP.replace("Test1234!", "short").replace("Tuan@", "y@")))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));

		// 로그인
		mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"tuan@example.com\",\"password\":\"wrong\"}"))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.errorCode").value("INVALID_CREDENTIALS"));
		String login = body(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"tuan@example.com\",\"password\":\"Test1234!\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.user.language").value("vi"))
				.andExpect(jsonPath("$.data.user.hasWorkplace").value(false)));
		String access = JsonPath.read(login, "$.data.accessToken");
		String refresh = JsonPath.read(login, "$.data.refreshToken");

		// 인증 필요 / 베트남어 에러 메시지
		mvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED")).andExpect(jsonPath("$.message").value("로그인이 필요합니다."));
		mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + access0)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.email").value("tuan@example.com"))
				.andExpect(jsonPath("$.data.consents.location").value(true))
				.andExpect(jsonPath("$.data.createdAt").value(org.hamcrest.Matchers.endsWith("+09:00")));
		mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).header("Accept-Language", "vi")
				.content("{\"email\":\"tuan@example.com\",\"password\":\"wrong\"}"))
				.andExpect(jsonPath("$.message").value("Email hoặc mật khẩu không đúng."));

		// 내 정보 수정 / 설정 / 근무장소
		String auth = "Bearer " + access;
		mvc.perform(patch("/api/v1/users/me").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
				.content("{\"language\":\"en\"}")).andExpect(jsonPath("$.data.language").value("en"));
		mvc.perform(patch("/api/v1/users/me").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
				.content("{\"language\":\"xx\"}")).andExpect(status().isBadRequest());
		mvc.perform(patch("/api/v1/users/me/settings").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
				.content("{\"payday\":10}")).andExpect(jsonPath("$.data.payday").value(10))
				.andExpect(jsonPath("$.data.paydayAlertEnabled").value(true));
		mvc.perform(patch("/api/v1/users/me/settings").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
				.content("{\"payday\":32}")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/users/me/workplace").header("Authorization", auth))
				.andExpect(jsonPath("$.data.workplace").doesNotExist());
		mvc.perform(put("/api/v1/users/me/workplace").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
				.content("{\"workplace\":{\"name\":\"OO농장\",\"address\":\"포천\",\"latitude\":37.9,\"longitude\":127.2},"
						+ "\"business\":{\"name\":\"OO농장\",\"address\":\"포천\",\"ownerName\":\"김OO\"}}"))
				.andExpect(jsonPath("$.data.workplace.radiusMeters").value(100))
				.andExpect(jsonPath("$.data.business.fromContract").value(false));
		mvc.perform(put("/api/v1/users/me/workplace").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
				.content("{\"workplace\":{\"name\":\"a\",\"address\":\"b\",\"latitude\":99,\"longitude\":0}}"))
				.andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/users/me").header("Authorization", auth))
				.andExpect(jsonPath("$.data.hasWorkplace").value(true)).andExpect(jsonPath("$.data.payday").value(10));

		// 토큰 회전: 새 쌍 발급, 옛 refresh는 재사용 불가
		String rotated = body(mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\":\"" + refresh + "\"}")).andExpect(status().isOk()));
		String refresh2 = JsonPath.read(rotated, "$.data.refreshToken");
		mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\":\"" + refresh + "\"}"))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.errorCode").value("INVALID_REFRESH_TOKEN"));
		// access 토큰을 refresh로 쓰면 거부
		mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\":\"" + access + "\"}")).andExpect(status().isUnauthorized());

		// 로그아웃 후 refresh 불가
		mvc.perform(post("/api/v1/auth/logout").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\":\"" + refresh2 + "\"}")).andExpect(status().isOk());
		mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\":\"" + refresh2 + "\"}")).andExpect(status().isUnauthorized());

		// 관리자 경로는 일반 사용자 403
		mvc.perform(get("/api/v1/admin/dashboard").header("Authorization", auth)).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
	}
}
