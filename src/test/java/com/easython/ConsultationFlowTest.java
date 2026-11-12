package com.easython;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.YearMonth;
import java.util.List;
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
class ConsultationFlowTest {
	@Autowired WebApplicationContext ctx;
	MockMvc mvc;

	static final String JSON = MediaType.APPLICATION_JSON_VALUE;
	static final String MONTH = YearMonth.now().minusMonths(1).toString();
	static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4, 5 };

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();
	}

	String signup(String email, String lang) throws Exception {
		String body = """
				{"email":"%s","password":"Test1234!","name":"Tuan","language":"%s",
				 "consents":{"privacy":true,"location":true,"camera":true},"agreedAt":"2026-11-11T09:00:00+09:00"}""".formatted(email, lang);
		return "Bearer " + str(mvc.perform(post("/api/v1/auth/signup").contentType(JSON).content(body)).andExpect(status().isOk()), "$.data.accessToken");
	}

	String adminLogin(String email) throws Exception {
		return "Bearer " + str(mvc.perform(post("/api/v1/admin/auth/login").contentType(JSON).content("{\"email\":\"" + email + "\",\"password\":\"Test1234!\"}"))
				.andExpect(status().isOk()), "$.data.accessToken");
	}

	String str(ResultActions a, String path) throws Exception {
		Object v = JsonPath.read(a.andReturn().getResponse().getContentAsString(), path);
		return String.valueOf(v);
	}

	String body(ResultActions a) throws Exception {
		return a.andReturn().getResponse().getContentAsString();
	}

	String mismatch(String auth) throws Exception {
		String seeded = body(mvc.perform(post("/api/v1/demo/seed").header("Authorization", auth).contentType(JSON).content("{\"month\":\"" + MONTH + "\"}"))
				.andExpect(status().isCreated()));
		return str(mvc.perform(post("/api/v1/payroll/comparisons").header("Authorization", auth).contentType(JSON)
				.content("{\"month\":\"" + MONTH + "\",\"statementId\":\"" + JsonPath.read(seeded, "$.data.statementId") + "\"}")).andExpect(status().isCreated()), "$.data.id");
	}

	String agencyId(String auth, String namePrefix) throws Exception {
		List<String> ids = JsonPath.read(body(mvc.perform(get("/api/v1/consultation-agencies").header("Authorization", auth))),
				"$.data.agencies[?(@.name =~ /" + namePrefix + ".*/)].id");
		return ids.get(0);
	}

	ResultActions create(String auth, String agency, String cmp, String scope) throws Exception {
		return mvc.perform(post("/api/v1/consultations").header("Authorization", auth).contentType(JSON).content("""
				{"agencyId":"%s","comparisonId":"%s","consent":{"sharedScope":%s,"agreedAt":"2026-11-11T11:00:00+09:00"},"memo":"Tôi chưa nhận đủ lương"}"""
				.formatted(agency, cmp, scope)));
	}

	void waitSummary(String auth, String id) throws Exception {
		for (int i = 0; i < 100; i++) {
			if (!"PROCESSING".equals(str(mvc.perform(get("/api/v1/consultations/" + id).header("Authorization", auth)), "$.data.summaryStatus"))) return;
			Thread.sleep(100);
		}
		throw new AssertionError("summary not finished");
	}

	@Test
	void fullConsultationFlow() throws Exception {
		String worker = signup("consult@example.com", "vi");
		String cmp = mismatch(worker);

		// 기관 목록(언어 필터)
		mvc.perform(get("/api/v1/consultation-agencies").header("Authorization", worker)).andExpect(jsonPath("$.data.agencies.length()").value(3));
		mvc.perform(get("/api/v1/consultation-agencies").param("language", "vi").header("Authorization", worker)).andExpect(jsonPath("$.data.agencies.length()").value(2));
		String center = agencyId(worker, "OO이주민지원센터");
		String ngo = agencyId(worker, "OO이주노동자");

		// 신청 검증
		create(worker, center, cmp, "[\"XYZ\"]").andExpect(status().isBadRequest());
		create(worker, center, cmp, "[\"COMPLAINT_DRAFT\"]").andExpect(status().isBadRequest()); // 진정서 id 없음
		create(worker, "nope", cmp, "[\"SUMMARY\"]").andExpect(status().isNotFound());
		String id = str(create(worker, center, cmp, "[\"ATTENDANCE\",\"PAYSLIP\"]").andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.status").value("RECEIVED")).andExpect(jsonPath("$.data.summaryStatus").value("PROCESSING")), "$.data.consultationId");
		create(worker, center, cmp, "[\"SUMMARY\"]").andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("DUPLICATED_CONSULTATION"));

		waitSummary(worker, id);
		mvc.perform(get("/api/v1/consultations/" + id).header("Authorization", worker)).andExpect(jsonPath("$.data.summaryStatus").value("DONE"))
				.andExpect(jsonPath("$.data.summary.ko").value(containsString("Tuan 노동자(사용 언어: 베트남어)")))
				.andExpect(jsonPath("$.data.summary.ko").value(containsString("공유 범위: 요약, 근무기록, 급여명세서")))
				.andExpect(jsonPath("$.data.summary.ko").value(containsString("상담 희망 내용: [데모 번역:ko] Tôi chưa nhận đủ lương")))
				.andExpect(jsonPath("$.data.summary.translated").value(startsWith("[데모 번역:vi]")))
				.andExpect(jsonPath("$.data.consent.sharedScope").value(org.hamcrest.Matchers.contains("SUMMARY", "ATTENDANCE", "PAYSLIP")))
				.andExpect(jsonPath("$.data.timeline[0].status").value("RECEIVED")).andExpect(jsonPath("$.data.agency.id").value(center));

		// 로그인 분리: 노동자 계정은 관리자 로그인 불가, 담당자 계정은 노동자 로그인 불가
		mvc.perform(post("/api/v1/admin/auth/login").contentType(JSON).content("{\"email\":\"consult@example.com\",\"password\":\"Test1234!\"}")).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/v1/auth/login").contentType(JSON).content("{\"email\":\"staff@agency.org\",\"password\":\"Test1234!\"}")).andExpect(status().isUnauthorized());
		String staffLogin = body(mvc.perform(post("/api/v1/admin/auth/login").contentType(JSON).content("{\"email\":\"staff@agency.org\",\"password\":\"Test1234!\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.staff.role").value("ADMIN")).andExpect(jsonPath("$.data.staff.agencyId").value(center)));
		String admin = "Bearer " + JsonPath.read(staffLogin, "$.data.accessToken");
		mvc.perform(get("/api/v1/admin/dashboard").header("Authorization", worker)).andExpect(status().isForbidden());

		// 목록·대시보드
		mvc.perform(get("/api/v1/admin/consultations").header("Authorization", admin)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.consultations[?(@.id=='" + id + "')].headline").value(hasItem(startsWith(YearMonth.parse(MONTH).getMonthValue() + "월 임금 "))))
				.andExpect(jsonPath("$.data.consultations[?(@.id=='" + id + "')].aiSuggestedUrgency").value(hasItem("HIGH")))
				.andExpect(jsonPath("$.data.consultations[?(@.id=='" + id + "')].workerLanguage").value(hasItem("vi")));
		mvc.perform(get("/api/v1/admin/consultations").param("sort", "urgency").header("Authorization", admin)).andExpect(status().isOk());
		mvc.perform(get("/api/v1/admin/consultations").param("sort", "bad").header("Authorization", admin)).andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/admin/dashboard").header("Authorization", admin)).andExpect(jsonPath("$.data.newCount").value(greaterThan(0)));

		// 상세: 동의 범위만(계약서 미동의 → contract null), 증거·타임라인·임금 비교
		mvc.perform(get("/api/v1/admin/consultations/" + id).header("Authorization", admin)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.worker.name").value("Tuan")).andExpect(jsonPath("$.data.contract").doesNotExist())
				.andExpect(jsonPath("$.data.complaintDraft").doesNotExist()).andExpect(jsonPath("$.data.timeline.length()").value(greaterThan(20)))
				.andExpect(jsonPath("$.data.wageComparison.differenceAmount").value(greaterThan(900000)))
				.andExpect(jsonPath("$.data.evidences[*].type").value(hasItem("GPS_LOG"))).andExpect(jsonPath("$.data.evidences[*].type").value(hasItem("PAYSLIP")))
				.andExpect(jsonPath("$.data.evidences[*].type").value(not(hasItem("CONTRACT"))))
				.andExpect(jsonPath("$.data.summary.ko").value(containsString("Tuan"))).andExpect(jsonPath("$.data.disclaimer").value("참고용 자료이며 법적 판단이 아닙니다."));

		// 긴급도 확정 / 담당자 배정
		mvc.perform(patch("/api/v1/admin/consultations/" + id + "/triage").header("Authorization", admin).contentType(JSON).content("{\"urgencyLevel\":\"URGENT\"}")).andExpect(status().isBadRequest());
		mvc.perform(patch("/api/v1/admin/consultations/" + id + "/triage").header("Authorization", admin).contentType(JSON).content("{\"urgencyLevel\":\"MEDIUM\",\"note\":\"검토\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.urgencyLevel").value("MEDIUM"));
		String staffList = body(mvc.perform(get("/api/v1/admin/staff").header("Authorization", admin)).andExpect(jsonPath("$.data.staff.length()").value(2)));
		List<String> staff2 = JsonPath.read(staffList, "$.data.staff[?(@.name=='이담당')].id");
		mvc.perform(put("/api/v1/admin/consultations/" + id + "/assignee").header("Authorization", admin).contentType(JSON).content("{\"staffId\":\"" + staff2.get(0) + "\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.assignee.name").value("이담당"));
		mvc.perform(put("/api/v1/admin/consultations/" + id + "/assignee").header("Authorization", admin).contentType(JSON).content("{\"staffId\":\"nope\"}")).andExpect(status().isNotFound());

		// 상태 전이
		mvc.perform(patch("/api/v1/admin/consultations/" + id + "/status").header("Authorization", admin).contentType(JSON).content("{\"status\":\"INFO_REQUESTED\"}"))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("INVALID_TRANSITION"));
		mvc.perform(patch("/api/v1/admin/consultations/" + id + "/status").header("Authorization", admin).contentType(JSON).content("{\"status\":\"CLOSED\"}")).andExpect(status().isBadRequest());
		mvc.perform(patch("/api/v1/admin/consultations/" + id + "/status").header("Authorization", admin).contentType(JSON).content("{\"status\":\"REVIEWING\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("REVIEWING"));

		// 추가 자료 요청 → 노동자 언어로 번역 + 알림
		mvc.perform(post("/api/v1/admin/consultations/" + id + "/info-requests").header("Authorization", admin).contentType(JSON)
				.content("{\"message\":\"10월 급여명세서를 추가로 공유해 주세요.\",\"requestedItems\":[\"PAYSLIP\"]}")).andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.status").value("INFO_REQUESTED"));
		mvc.perform(get("/api/v1/consultations/" + id).header("Authorization", worker)).andExpect(jsonPath("$.data.status").value("INFO_REQUESTED"))
				.andExpect(jsonPath("$.data.infoRequests[0].message").value("[데모 번역:vi] 10월 급여명세서를 추가로 공유해 주세요."))
				.andExpect(jsonPath("$.data.timeline[*].note").value(not(hasItem("검토"))));
		mvc.perform(get("/api/v1/notifications").header("Authorization", worker)).andExpect(jsonPath("$.data.notifications[*].type").value(hasItem("INFO_REQUEST")))
				.andExpect(jsonPath("$.data.notifications[*].type").value(hasItem("CONSULTATION_UPDATE")));

		// 담당자 메시지 → 노동자에게 번역, 안 읽음 → 읽음
		mvc.perform(post("/api/v1/admin/consultations/" + id + "/messages").header("Authorization", admin).contentType(JSON).content("{\"text\":\"확인했습니다.\"}"))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.data.text").value("[데모 번역:vi] 확인했습니다.")).andExpect(jsonPath("$.data.koText").value("확인했습니다."));
		mvc.perform(get("/api/v1/consultations").header("Authorization", worker)).andExpect(jsonPath("$.data.consultations[0].unreadMessages").value(1))
				.andExpect(jsonPath("$.data.consultations[0].shared").value(true));
		mvc.perform(get("/api/v1/consultations/" + id + "/messages").header("Authorization", worker)).andExpect(jsonPath("$.data.messages[0].sender").value("STAFF"));
		mvc.perform(get("/api/v1/consultations").header("Authorization", worker)).andExpect(jsonPath("$.data.consultations[0].unreadMessages").value(0));
		mvc.perform(get("/api/v1/consultations").param("status", "NOPE").header("Authorization", worker)).andExpect(status().isBadRequest());

		// 노동자 메시지 + 증거 첨부(공유 범위 밖이던 계약서도 첨부하면 공유됨)
		String contractId = str(mvc.perform(multipart("/api/v1/contracts").file(new MockMultipartFile("file", "c.png", "image/png", PNG)).header("Authorization", worker))
				.andExpect(status().isAccepted()), "$.data.contractId");
		mvc.perform(post("/api/v1/consultations/" + id + "/messages").header("Authorization", worker).contentType(JSON)
				.content("{\"text\":\"Tôi đã gửi hợp đồng\",\"attachEvidenceIds\":[\"CONTRACT-" + contractId + "\"]}")).andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.koText").value("[데모 번역:ko] Tôi đã gửi hợp đồng")).andExpect(jsonPath("$.data.translationStatus").value("DONE"));
		mvc.perform(post("/api/v1/consultations/" + id + "/messages").header("Authorization", worker).contentType(JSON)
				.content("{\"text\":\"x\",\"attachEvidenceIds\":[\"CONTRACT-nope\"]}")).andExpect(status().isNotFound());
		mvc.perform(post("/api/v1/consultations/" + id + "/messages").header("Authorization", worker).contentType(JSON).content("{\"text\":\"\"}")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/admin/consultations").header("Authorization", admin))
				.andExpect(jsonPath("$.data.consultations[?(@.id=='" + id + "')].hasNewMessage").value(hasItem(true)));
		mvc.perform(get("/api/v1/admin/consultations/" + id + "/messages").header("Authorization", admin))
				.andExpect(jsonPath("$.data.messages[1].attachedEvidences[0].id").value("CONTRACT-" + contractId));
		mvc.perform(get("/api/v1/admin/consultations").header("Authorization", admin))
				.andExpect(jsonPath("$.data.consultations[?(@.id=='" + id + "')].hasNewMessage").value(hasItem(false)));
		mvc.perform(get("/api/v1/admin/consultations/" + id).header("Authorization", admin))
				.andExpect(jsonPath("$.data.evidences[?(@.type=='CONTRACT')].fileUrl").value(hasItem(startsWith("/api/v1/files/contract_"))));

		// 진행 중 상담에 공유된 자료: 근무기록 수정·계약서 삭제 불가
		String recordId = str(mvc.perform(get("/api/v1/attendance/monthly").param("month", MONTH).header("Authorization", worker)), "$.data.days[0].recordId");
		mvc.perform(patch("/api/v1/attendance/records/" + recordId).header("Authorization", worker).contentType(JSON).content("{\"breakMinutes\":30,\"reason\":\"x\"}"))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("Không thể sửa bản ghi đang được chia sẻ cho tư vấn."));
		mvc.perform(delete("/api/v1/contracts/" + contractId).header("Authorization", worker)).andExpect(status().isConflict())
				.andExpect(jsonPath("$.errorCode").value("ACTIVE_CONSULTATION"));

		// 연계 → 검토 → 종결(결과 번역 공유)
		mvc.perform(patch("/api/v1/admin/consultations/" + id + "/status").header("Authorization", admin).contentType(JSON).content("{\"status\":\"REVIEWING\"}")).andExpect(status().isOk());
		mvc.perform(post("/api/v1/admin/consultations/" + id + "/referrals").header("Authorization", admin).contentType(JSON)
				.content("{\"type\":\"LABOR_ATTORNEY\",\"organization\":\"OO노무법인\",\"note\":\"진정 대리 상담\"}")).andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.status").value("REFERRED"));
		mvc.perform(post("/api/v1/admin/consultations/" + id + "/referrals").header("Authorization", admin).contentType(JSON)
				.content("{\"type\":\"LAWYER\",\"organization\":\"x\"}")).andExpect(status().isBadRequest());
		mvc.perform(post("/api/v1/admin/consultations/" + id + "/result").header("Authorization", admin).contentType(JSON)
				.content("{\"content\":\"진정 절차 안내\",\"actionTaken\":\"노무사 연계\",\"closeReason\":\"진정 접수 완료\"}")).andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.status").value("CLOSED"));
		mvc.perform(post("/api/v1/admin/consultations/" + id + "/result").header("Authorization", admin).contentType(JSON)
				.content("{\"content\":\"a\",\"actionTaken\":\"b\",\"closeReason\":\"c\"}")).andExpect(status().isConflict());
		mvc.perform(get("/api/v1/consultations/" + id).header("Authorization", worker)).andExpect(jsonPath("$.data.status").value("CLOSED"))
				.andExpect(jsonPath("$.data.resultSummary").value(startsWith("[데모 번역:vi] 상담 내용: 진정 절차 안내"))).andExpect(jsonPath("$.data.referrals[0].organization").value("OO노무법인"));
		mvc.perform(post("/api/v1/consultations/" + id + "/messages").header("Authorization", worker).contentType(JSON).content("{\"text\":\"hi\"}")).andExpect(status().isConflict());
		mvc.perform(post("/api/v1/admin/consultations/" + id + "/messages").header("Authorization", admin).contentType(JSON).content("{\"text\":\"hi\"}")).andExpect(status().isConflict());
		mvc.perform(delete("/api/v1/contracts/" + contractId).header("Authorization", worker)).andExpect(status().isOk()); // 종결 후에는 삭제 가능

		// 동의 철회: 담당자 열람 불가, 목록에서 제외
		String id2 = str(create(worker, center, cmp, "[\"SUMMARY\"]").andExpect(status().isCreated()), "$.data.consultationId"); // 이전 건은 종결되어 재신청 가능
		mvc.perform(delete("/api/v1/consultations/" + id2 + "/consent").header("Authorization", worker)).andExpect(status().isOk()).andExpect(jsonPath("$.data.revokedAt").exists());
		mvc.perform(delete("/api/v1/consultations/" + id2 + "/consent").header("Authorization", worker)).andExpect(status().isConflict());
		mvc.perform(get("/api/v1/admin/consultations/" + id2).header("Authorization", admin)).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.errorCode").value("CONSENT_NOT_GRANTED"));
		mvc.perform(get("/api/v1/admin/consultations").header("Authorization", admin)).andExpect(jsonPath("$.data.consultations[*].id").value(not(hasItem(id2))));
		mvc.perform(post("/api/v1/consultations/" + id2 + "/messages").header("Authorization", worker).contentType(JSON).content("{\"text\":\"hi\"}")).andExpect(status().isConflict());

		// 다른 기관 사건은 403, 남의 상담은 404
		String id3 = str(create(worker, ngo, cmp, "[\"SUMMARY\"]").andExpect(status().isCreated()), "$.data.consultationId");
		mvc.perform(get("/api/v1/admin/consultations/" + id3).header("Authorization", admin)).andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
		String other = signup("consult-other@example.com", "ko");
		mvc.perform(get("/api/v1/consultations/" + id).header("Authorization", other)).andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("상담을 찾을 수 없습니다."));
	}
}
