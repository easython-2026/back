package com.easython;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.easython.attendance.WorkRecord;
import com.easython.attendance.WorkRecordRepository;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(properties = { "app.storage.local-dir=build/test-uploads", "app.ai.provider=mock" })
class NotificationReportFlowTest {
	@Autowired WebApplicationContext ctx;
	@Autowired WorkRecordRepository records;
	MockMvc mvc;

	static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4, 5 };
	static final String JSON = MediaType.APPLICATION_JSON_VALUE;
	static final String LAST_MONTH = YearMonth.now().minusMonths(1).toString();

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();
	}

	record Login(String auth, String userId) {}

	Login signup(String email, String lang) throws Exception {
		String body = """
				{"email":"%s","password":"Test1234!","name":"Tuan","language":"%s",
				 "consents":{"privacy":true,"location":true,"camera":true},"agreedAt":"2026-11-11T09:00:00+09:00"}""".formatted(email, lang);
		String r = mvc.perform(post("/api/v1/auth/signup").contentType(JSON).content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return new Login("Bearer " + JsonPath.read(r, "$.data.accessToken"), JsonPath.read(r, "$.data.userId"));
	}

	@Test
	void paydayNotificationLifecycle() throws Exception {
		var u = signup("notif-payday@example.com", "vi");
		mvc.perform(get("/api/v1/notifications").header("Authorization", u.auth())).andExpect(status().isOk()).andExpect(jsonPath("$.data.unreadCount").value(0));

		// 월급날(매월 1일)이 지났고 전월 실수령액이 없으면 조회 시점에 팝업 알림 생성
		mvc.perform(patch("/api/v1/users/me/settings").header("Authorization", u.auth()).contentType(JSON).content("{\"payday\":1}")).andExpect(status().isOk());
		String list = mvc.perform(get("/api/v1/notifications").header("Authorization", u.auth())).andExpect(jsonPath("$.data.unreadCount").value(1))
				.andExpect(jsonPath("$.data.notifications[0].type").value("PAYDAY")).andExpect(jsonPath("$.data.notifications[0].popup").value(true))
				.andExpect(jsonPath("$.data.notifications[0].payload.month").value(LAST_MONTH))
				.andExpect(jsonPath("$.data.notifications[0].title").value("Hôm nay là ngày lĩnh lương")) // 사용자 언어
				.andReturn().getResponse().getContentAsString();
		String id = JsonPath.read(list, "$.data.notifications[0].id");

		// 다시 조회해도 중복 생성 안 됨 / 팝업 대상 조회
		mvc.perform(get("/api/v1/notifications").header("Authorization", u.auth())).andExpect(jsonPath("$.data.pageInfo.totalCount").value(1));
		mvc.perform(get("/api/v1/notifications").param("popupOnly", "true").header("Authorization", u.auth())).andExpect(jsonPath("$.data.notifications", hasSize(1)));

		// 읽음 처리하면 팝업에서 빠지고, 다시 읽음 처리해도 200
		mvc.perform(patch("/api/v1/notifications/" + id + "/read").header("Authorization", u.auth())).andExpect(status().isOk()).andExpect(jsonPath("$.data.read").value(true));
		mvc.perform(patch("/api/v1/notifications/" + id + "/read").header("Authorization", u.auth())).andExpect(status().isOk());
		mvc.perform(get("/api/v1/notifications").param("popupOnly", "true").header("Authorization", u.auth())).andExpect(jsonPath("$.data.notifications", hasSize(0)));
		mvc.perform(get("/api/v1/notifications").param("unreadOnly", "true").header("Authorization", u.auth())).andExpect(jsonPath("$.data.unreadCount").value(0))
				.andExpect(jsonPath("$.data.notifications", hasSize(0)));
		mvc.perform(get("/api/v1/notifications").header("Authorization", u.auth())).andExpect(jsonPath("$.data.notifications[0].popup").value(false));

		// 남의 알림 / 없는 알림은 404, 페이지 값 검증
		var other = signup("notif-other@example.com", "ko");
		mvc.perform(patch("/api/v1/notifications/" + id + "/read").header("Authorization", other.auth())).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("알림을 찾을 수 없습니다."));
		mvc.perform(get("/api/v1/notifications").param("size", "0").header("Authorization", u.auth())).andExpect(status().isBadRequest());

		// 알림을 끄면 생성되지 않음
		var off = signup("notif-off@example.com", "ko");
		mvc.perform(patch("/api/v1/users/me/settings").header("Authorization", off.auth()).contentType(JSON).content("{\"payday\":1,\"paydayAlertEnabled\":false}")).andExpect(status().isOk());
		mvc.perform(get("/api/v1/notifications").header("Authorization", off.auth())).andExpect(jsonPath("$.data.unreadCount").value(0));
		// 이미 전월 수령액을 입력했다면 월급날 알림 없음
		var paid = signup("notif-paid@example.com", "ko");
		mvc.perform(patch("/api/v1/users/me/settings").header("Authorization", paid.auth()).contentType(JSON).content("{\"payday\":1}")).andExpect(status().isOk());
		mvc.perform(post("/api/v1/payroll/statements/manual").header("Authorization", paid.auth()).contentType(JSON)
				.content("{\"month\":\"" + LAST_MONTH + "\",\"receivedAmount\":1000000}")).andExpect(status().isCreated());
		mvc.perform(get("/api/v1/notifications").header("Authorization", paid.auth())).andExpect(jsonPath("$.data.unreadCount").value(0));
	}

	@Test
	void missingRecordAndMismatchNotifications() throws Exception {
		var u = signup("notif-missing@example.com", "ko");
		// 3일 전에 출근만 찍고 퇴근을 안 한 기록 → 퇴근 누락
		WorkRecord r = new WorkRecord();
		r.setUserId(u.userId());
		r.setWorkDate(LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(3));
		r.setSource(WorkRecord.Source.DEVICE);
		r.setClockInAt(Instant.now().minusSeconds(3 * 86400));
		r.setOriginalClockInAt(r.getClockInAt());
		records.save(r);
		mvc.perform(get("/api/v1/notifications").header("Authorization", u.auth())).andExpect(jsonPath("$.data.notifications[0].type").value("MISSING_RECORD"))
				.andExpect(jsonPath("$.data.notifications[0].payload.recordId").value(r.getId())).andExpect(jsonPath("$.data.notifications[0].body").value(endsWith("퇴근 시각을 입력해 주세요.")));
		mvc.perform(get("/api/v1/notifications").header("Authorization", u.auth())).andExpect(jsonPath("$.data.pageInfo.totalCount").value(1)); // 중복 없음

		// 급여 비교 결과가 불일치면 알림
		var d = signup("notif-mismatch@example.com", "ko");
		String seeded = mvc.perform(post("/api/v1/demo/seed").header("Authorization", d.auth()).contentType(JSON).content("{\"month\":\"" + LAST_MONTH + "\"}"))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		String cmp = mvc.perform(post("/api/v1/payroll/comparisons").header("Authorization", d.auth()).contentType(JSON)
				.content("{\"month\":\"" + LAST_MONTH + "\",\"statementId\":\"" + JsonPath.read(seeded, "$.data.statementId") + "\"}"))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		mvc.perform(get("/api/v1/notifications").header("Authorization", d.auth())).andExpect(jsonPath("$.data.notifications[0].type").value("MISMATCH"))
				.andExpect(jsonPath("$.data.notifications[0].payload.comparisonId").value((String) JsonPath.read(cmp, "$.data.id")))
				.andExpect(jsonPath("$.data.notifications[0].title").value("급여가 예상과 달라요"));
	}

	@Test
	void monthlyReportAndEvidences() throws Exception {
		var u = signup("report@example.com", "ko");
		String seeded = mvc.perform(post("/api/v1/demo/seed").header("Authorization", u.auth()).contentType(JSON).content("{\"month\":\"" + LAST_MONTH + "\"}"))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		int days = JsonPath.read(seeded, "$.data.attendanceRecords");

		// 비교 전: 근무 요약만, wage 는 null
		mvc.perform(get("/api/v1/reports/monthly").param("month", LAST_MONTH).header("Authorization", u.auth())).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.work.workDays").value(days)).andExpect(jsonPath("$.data.work.days", hasSize(days)))
				.andExpect(jsonPath("$.data.work.days[0].flags").value(hasItem("NO_BREAK"))).andExpect(jsonPath("$.data.wage").doesNotExist())
				.andExpect(jsonPath("$.data.evidenceCount").value(days * 4)) // 하루 2회(출/퇴근) x (GPS 로그 + 서버 타임스탬프), 시드는 사진 없음
				.andExpect(jsonPath("$.data.disclaimer").exists());

		// 비교 후: 임금 요약 + 추가 확인 항목
		mvc.perform(post("/api/v1/payroll/comparisons").header("Authorization", u.auth()).contentType(JSON)
				.content("{\"month\":\"" + LAST_MONTH + "\",\"statementId\":\"" + JsonPath.read(seeded, "$.data.statementId") + "\"}")).andExpect(status().isCreated());
		mvc.perform(get("/api/v1/reports/monthly").param("month", LAST_MONTH).header("Authorization", u.auth())).andExpect(jsonPath("$.data.wage.status").value("MISMATCH"))
				.andExpect(jsonPath("$.data.wage.differenceAmount").value(greaterThan(900000))).andExpect(jsonPath("$.data.wage.extraChecks[*].code").value(hasItem("MISSING_BREAK")));
		mvc.perform(get("/api/v1/reports/monthly").param("month", "2020-01").header("Authorization", u.auth())).andExpect(jsonPath("$.data.work.workDays").value(0))
				.andExpect(jsonPath("$.data.wage").doesNotExist());
		mvc.perform(get("/api/v1/reports/monthly").param("month", "2026-13").header("Authorization", u.auth())).andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/reports/monthly").header("Authorization", u.auth())).andExpect(status().isBadRequest());

		// 증거 목록: 월 필터, 종류 필터, 페이지, 최신순
		mvc.perform(get("/api/v1/evidences").param("month", LAST_MONTH).header("Authorization", u.auth())).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.pageInfo.totalCount").value(days * 4)).andExpect(jsonPath("$.data.evidences", hasSize(20)))
				.andExpect(jsonPath("$.data.evidences[0].id").exists()).andExpect(jsonPath("$.data.evidences[0].refType").value("attendance_record"));
		mvc.perform(get("/api/v1/evidences").param("month", LAST_MONTH).param("type", "GPS_LOG").param("size", "100").header("Authorization", u.auth()))
				.andExpect(jsonPath("$.data.pageInfo.totalCount").value(days * 2)).andExpect(jsonPath("$.data.evidences[0].title").value(org.hamcrest.Matchers.containsString("GPS 기록")));
		mvc.perform(get("/api/v1/evidences").param("type", "ATTENDANCE_PHOTO").header("Authorization", u.auth())).andExpect(jsonPath("$.data.pageInfo.totalCount").value(0));
		mvc.perform(get("/api/v1/evidences").param("type", "NOPE").header("Authorization", u.auth())).andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/evidences").param("month", "bad").header("Authorization", u.auth())).andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/evidences").param("from", "2020-01-01").param("to", "2020-12-31").header("Authorization", u.auth())).andExpect(jsonPath("$.data.pageInfo.totalCount").value(0));
		String page2 = mvc.perform(get("/api/v1/evidences").param("month", LAST_MONTH).param("page", "2").param("size", "50").header("Authorization", u.auth()))
				.andExpect(jsonPath("$.data.pageInfo.currentPage").value(2)).andReturn().getResponse().getContentAsString();
		org.junit.jupiter.api.Assertions.assertTrue((int) JsonPath.read(page2, "$.data.evidences.length()") > 0);

		// 계약서 원본 업로드는 CONTRACT 증거(해시 포함)로 나타난다. 남의 증거는 보이지 않는다.
		String cid = JsonPath.read(mvc.perform(multipart("/api/v1/contracts").file(new MockMultipartFile("file", "c.png", "image/png", PNG)).header("Authorization", u.auth()))
				.andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString(), "$.data.contractId");
		mvc.perform(get("/api/v1/evidences").param("type", "CONTRACT").header("Authorization", u.auth())).andExpect(jsonPath("$.data.evidences[0].id").value("CONTRACT-" + cid))
				.andExpect(jsonPath("$.data.evidences[0].hash").exists()).andExpect(jsonPath("$.data.evidences[0].refType").value("contract"));
		var other = signup("report-other@example.com", "ko");
		mvc.perform(get("/api/v1/evidences").header("Authorization", other.auth())).andExpect(jsonPath("$.data.pageInfo.totalCount").value(0));
	}
}
