package com.easython;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(properties = { "app.storage.local-dir=build/test-uploads", "app.ai.provider=mock" })
class AttendanceFlowTest {
	@Autowired WebApplicationContext ctx;
	MockMvc mvc;

	static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4, 5 };
	static final ZoneId KST = ZoneId.of("Asia/Seoul");
	static final String JSON = MediaType.APPLICATION_JSON_VALUE;

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();
	}

	/** 가입하고 "Bearer ..." 반환. */
	String signup(String email, boolean location, boolean camera) throws Exception {
		String body = """
				{"email":"%s","password":"Test1234!","name":"Tuan","language":"ko",
				 "consents":{"privacy":true,"location":%b,"camera":%b},"agreedAt":"2026-11-11T09:00:00+09:00"}""".formatted(email, location, camera);
		String r = mvc.perform(post("/api/v1/auth/signup").contentType(JSON).content(body)).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		return "Bearer " + JsonPath.read(r, "$.data.accessToken");
	}

	void setWorkplace(String auth) throws Exception {
		mvc.perform(put("/api/v1/users/me/workplace").header("Authorization", auth).contentType(JSON)
				.content("{\"workplace\":{\"name\":\"OO농장\",\"address\":\"포천\",\"latitude\":37.9,\"longitude\":127.2}}"))
				.andExpect(status().isOk());
	}

	ResultActions check(String auth, String type, double lat, double lng, double acc) throws Exception {
		return mvc.perform(post("/api/v1/attendance/location-check").header("Authorization", auth).contentType(JSON)
				.content("{\"type\":\"%s\",\"latitude\":%s,\"longitude\":%s,\"accuracyMeters\":%s}".formatted(type, lat, lng, acc)));
	}

	String verifiedToken(String auth, String type) throws Exception {
		String r = check(auth, type, 37.9001, 127.2002, 18.5).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.verified").value(true)).andReturn().getResponse().getContentAsString();
		return JsonPath.read(r, "$.data.verificationToken");
	}

	ResultActions clock(String auth, String path, String token, byte[] photo) throws Exception {
		return mvc.perform(multipart("/api/v1/attendance/" + path)
				.file(new MockMultipartFile("photo", "a.png", "image/png", photo))
				.param("verificationToken", token).param("latitude", "37.9001").param("longitude", "127.2002")
				.param("accuracyMeters", "18.5").header("Authorization", auth));
	}

	String json(ResultActions a) throws Exception {
		return a.andReturn().getResponse().getContentAsString();
	}

	@Test
	void fullAttendanceFlow() throws Exception {
		String auth = signup("worker@example.com", true, true);

		// 근무장소 없음 → 인증 실패(에러 아님)
		check(auth, "CLOCK_IN", 37.9, 127.2, 10).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.verified").value(false)).andExpect(jsonPath("$.data.failReason").value("NO_WORKPLACE"));
		setWorkplace(auth);
		check(auth, "CLOCK_IN", 37.95, 127.2, 10).andExpect(jsonPath("$.data.failReason").value("OUT_OF_RANGE"))
				.andExpect(jsonPath("$.data.distanceMeters").value(greaterThan(5000))).andExpect(jsonPath("$.data.verificationToken").doesNotExist());
		check(auth, "CLOCK_IN", 37.9, 127.2, 500).andExpect(jsonPath("$.data.failReason").value("LOW_ACCURACY"));
		check(auth, "CLOCK_OUT", 37.9, 127.2, 10).andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("NOT_CLOCKED_IN"));
		check(auth, "BOGUS", 37.9, 127.2, 10).andExpect(status().isBadRequest());

		// 출근: 토큰 검증 / 파일 검증 (잘못된 파일은 토큰을 소모하지 않음)
		String t1 = verifiedToken(auth, "CLOCK_IN");
		clock(auth, "clock-in", "vt_bogus", PNG).andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("GPS_NOT_VERIFIED"));
		clock(auth, "clock-in", t1, "not an image".getBytes()).andExpect(status().isUnsupportedMediaType());
		clock(auth, "clock-in", t1, new byte[6 * 1024 * 1024]).andExpect(status().isPayloadTooLarge())
				.andExpect(jsonPath("$.errorCode").value("FILE_TOO_LARGE"));
		String in = json(clock(auth, "clock-in", t1, PNG).andExpect(status().isCreated()).andExpect(jsonPath("$.data.workStatus").value("WORKING"))
				.andExpect(jsonPath("$.data.photo.hash").value(org.hamcrest.Matchers.hasLength(64))));
		String recordId = JsonPath.read(in, "$.data.recordId");
		String clockInAt = JsonPath.read(in, "$.data.serverTimestamp");

		// 토큰 재사용 불가 / 중복 출근 불가
		clock(auth, "clock-in", t1, PNG).andExpect(status().isForbidden());
		check(auth, "CLOCK_IN", 37.9001, 127.2002, 10).andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("ALREADY_CLOCKED_IN"));
		mvc.perform(get("/api/v1/attendance/today").header("Authorization", auth)).andExpect(jsonPath("$.data.workStatus").value("WORKING"))
				.andExpect(jsonPath("$.data.nextAction").value("CLOCK_OUT")).andExpect(jsonPath("$.data.record.id").value(recordId));
		mvc.perform(put("/api/v1/attendance/records/" + recordId + "/break").header("Authorization", auth).contentType(JSON)
				.content("{\"breakMinutes\":10}")).andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("CONFLICT"))
				.andExpect(jsonPath("$.message").value("퇴근 전 기록은 휴게시간을 입력할 수 없습니다."));

		// 퇴근 (출근 토큰으로는 퇴근 불가)
		clock(auth, "clock-out", t1, PNG).andExpect(status().isForbidden());
		String t2 = verifiedToken(auth, "CLOCK_OUT");
		clock(auth, "clock-out", t2, PNG).andExpect(status().isCreated()).andExpect(jsonPath("$.data.workStatus").value("FINISHED"))
				.andExpect(jsonPath("$.data.needsBreakInput").value(true));
		check(auth, "CLOCK_OUT", 37.9001, 127.2002, 10).andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("ALREADY_CLOCKED_OUT"));
		check(auth, "CLOCK_IN", 37.9001, 127.2002, 10).andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("DUPLICATED_RECORD"));
		mvc.perform(get("/api/v1/attendance/today").header("Authorization", auth)).andExpect(jsonPath("$.data.workStatus").value("FINISHED"))
				.andExpect(jsonPath("$.data.nextAction").doesNotExist());
		// (테스트는 출근·퇴근을 같은 초에 찍으므로 TIME_ORDER_ERROR 도 함께 잡힌다)
		mvc.perform(get("/api/v1/attendance/issues").header("Authorization", auth))
				.andExpect(jsonPath("$.data.issues[*].type").value(hasItem("NO_BREAK_INPUT")));

		// 수정: 원본 유지 + 이력 추가
		String threeHoursEarlier = OffsetDateTime.parse(clockInAt).minusHours(3).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
		mvc.perform(patch("/api/v1/attendance/records/" + recordId).header("Authorization", auth).contentType(JSON)
				.content("{\"clockInAt\":\"" + threeHoursEarlier + "\"}")).andExpect(status().isBadRequest()); // 사유 누락
		mvc.perform(patch("/api/v1/attendance/records/" + recordId).header("Authorization", auth).contentType(JSON)
				.content("{\"clockInAt\":\"" + clockInAt + "\",\"reason\":\"변경 없음\"}")).andExpect(status().isBadRequest()); // 같은 값
		mvc.perform(patch("/api/v1/attendance/records/" + recordId).header("Authorization", auth).contentType(JSON)
				.content("{\"clockInAt\":\"" + threeHoursEarlier + "\",\"reason\":\"출근 촬영을 늦게 함\"}")).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.workedMinutes").value(180)).andExpect(jsonPath("$.data.history[0].field").value("CLOCK_IN"))
				.andExpect(jsonPath("$.data.history[0].before").value(clockInAt));
		mvc.perform(put("/api/v1/attendance/records/" + recordId + "/break").header("Authorization", auth).contentType(JSON)
				.content("{\"breakMinutes\":9999}")).andExpect(status().isBadRequest());
		mvc.perform(put("/api/v1/attendance/records/" + recordId + "/break").header("Authorization", auth).contentType(JSON)
				.content("{\"breakMinutes\":60}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.workedMinutes").value(120));
		mvc.perform(put("/api/v1/attendance/records/" + recordId + "/break").header("Authorization", auth).contentType(JSON)
				.content("{\"breakMinutes\":30}")).andExpect(status().isOk());

		// 상세: 원본 유지, 증거, 이력 3건, 서명 URL로 사진 조회
		String detail = json(mvc.perform(get("/api/v1/attendance/records/" + recordId).header("Authorization", auth)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.source").value("DEVICE")).andExpect(jsonPath("$.data.original.clockInAt").value(clockInAt))
				.andExpect(jsonPath("$.data.original.breakMinutes").doesNotExist())
				.andExpect(jsonPath("$.data.current.clockInAt").value(threeHoursEarlier)).andExpect(jsonPath("$.data.current.breakMinutes").value(30))
				.andExpect(jsonPath("$.data.clockIn.gps.verified").value(true)).andExpect(jsonPath("$.data.clockOut.photo.hash").exists())
				.andExpect(jsonPath("$.data.history.length()").value(3)));
		String photoUrl = JsonPath.read(detail, "$.data.clockIn.photo.url");
		mvc.perform(get(photoUrl)).andExpect(status().isOk()).andExpect(content().contentType("image/png"))
				.andExpect(content().bytes(PNG));
		int sigAt = photoUrl.indexOf("sig=") + 4; // 서명 첫 글자를 반드시 다른 값으로 바꿔 변조
		String tampered = photoUrl.substring(0, sigAt) + (photoUrl.charAt(sigAt) == '0' ? '1' : '0') + photoUrl.substring(sigAt + 1);
		mvc.perform(get(tampered)).andExpect(status().isForbidden());

		// 다른 사람 기록은 404
		String other = signup("other@example.com", true, true);
		mvc.perform(get("/api/v1/attendance/records/" + recordId).header("Authorization", other)).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("근무기록을 찾을 수 없습니다."));

		// 수동 추가
		String yesterday = LocalDate.now(KST).minusDays(1).toString();
		String manual = "{\"workDate\":\"%s\",\"clockInAt\":\"%sT08:00:00+09:00\",\"clockOutAt\":\"%sT18:00:00+09:00\",\"breakMinutes\":60,\"reason\":\"배터리 방전\"}"
				.formatted(yesterday, yesterday, yesterday);
		mvc.perform(post("/api/v1/attendance/records").header("Authorization", auth).contentType(JSON).content(manual)).andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.source").value("MANUAL")).andExpect(jsonPath("$.data.workedMinutes").value(540));
		mvc.perform(post("/api/v1/attendance/records").header("Authorization", auth).contentType(JSON).content(manual)).andExpect(status().isConflict())
				.andExpect(jsonPath("$.errorCode").value("DUPLICATED_RECORD"));
		String future = LocalDate.now(KST).plusDays(1).toString();
		mvc.perform(post("/api/v1/attendance/records").header("Authorization", auth).contentType(JSON)
				.content(manual.replace(yesterday, future))).andExpect(status().isBadRequest());
		String twoDaysAgo = LocalDate.now(KST).minusDays(2).toString();
		mvc.perform(post("/api/v1/attendance/records").header("Authorization", auth).contentType(JSON)
				.content("{\"workDate\":\"%s\",\"clockInAt\":\"%sT18:00:00+09:00\",\"clockOutAt\":\"%sT08:00:00+09:00\",\"reason\":\"x\"}"
						.formatted(twoDaysAgo, twoDaysAgo, twoDaysAgo)))
				.andExpect(status().isBadRequest()); // 퇴근이 출근보다 빠름

		// 주간 / 월간
		mvc.perform(get("/api/v1/attendance/weekly").header("Authorization", auth)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.days.length()").value(7)).andExpect(jsonPath("$.data.totalWorkedMinutes").value(greaterThanOrEqualTo(150)))
				.andExpect(jsonPath("$.data.days[?(@.date=='" + LocalDate.now(KST) + "')].flags[*]").value(hasItem("EDITED")))
				.andExpect(jsonPath("$.data.days[?(@.date=='" + LocalDate.now(KST) + "')].flags[*]").value(not(hasItem("NO_BREAK"))));
		mvc.perform(get("/api/v1/attendance/weekly").param("weekStart", "2026-11-12").header("Authorization", auth)).andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/attendance/monthly").param("month", YearMonth.now(KST).toString()).header("Authorization", auth))
				.andExpect(jsonPath("$.data.summary.workDays").value(greaterThanOrEqualTo(1)));
		mvc.perform(get("/api/v1/attendance/monthly").param("month", "2026-13").header("Authorization", auth)).andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/attendance/monthly").header("Authorization", auth)).andExpect(status().isBadRequest());

		// 데모 시드
		String month = YearMonth.now(KST).minusMonths(2).toString();
		String seeded = json(mvc.perform(post("/api/v1/demo/seed").header("Authorization", auth).contentType(JSON).content("{\"month\":\"" + month + "\"}"))
				.andExpect(status().isCreated()));
		int count = JsonPath.read(seeded, "$.data.attendanceRecords");
		org.junit.jupiter.api.Assertions.assertTrue(count >= 24 && count <= 27, "records=" + count);
		mvc.perform(post("/api/v1/demo/seed").header("Authorization", auth).contentType(JSON).content("{\"month\":\"" + month + "\"}"))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("DUPLICATED_RECORD"));
		mvc.perform(get("/api/v1/attendance/monthly").param("month", month).header("Authorization", auth))
				.andExpect(jsonPath("$.data.summary.workDays").value(count)).andExpect(jsonPath("$.data.days[0].workedMinutes").value(greaterThan(590)));
		mvc.perform(get("/api/v1/attendance/issues").param("month", month).header("Authorization", auth)).andExpect(jsonPath("$.data.count").value(count));
		mvc.perform(get("/api/v1/attendance/issues").param("month", month).header("Authorization", auth).header("Accept-Language", "vi"))
				.andExpect(jsonPath("$.data.count").value(count)); // 사용자 언어는 ko라 메시지는 한국어
	}

	@Test
	void consentIsRequired() throws Exception {
		String noLocation = signup("nolocation@example.com", false, true);
		check(noLocation, "CLOCK_IN", 37.9, 127.2, 10).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.errorCode").value("LOCATION_CONSENT_REQUIRED"));

		String noCamera = signup("nocamera@example.com", true, false);
		setWorkplace(noCamera);
		String t = verifiedToken(noCamera, "CLOCK_IN");
		clock(noCamera, "clock-in", t, PNG).andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("CAMERA_CONSENT_REQUIRED"));

		mvc.perform(get("/api/v1/attendance/today")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/attendance/today").header("Authorization", noCamera)).andExpect(jsonPath("$.data.workStatus").value("NOT_STARTED"))
				.andExpect(jsonPath("$.data.nextAction").value("CLOCK_IN")).andExpect(jsonPath("$.data.hasWorkplace").value(true))
				.andExpect(jsonPath("$.data.record").doesNotExist());
	}
}
