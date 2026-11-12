package com.easython.consultation;

import com.easython.common.ApiResponse;
import com.easython.consultation.ConsultationDtos.*;
import com.easython.security.AuthUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 상담기관 담당자 API (/api/v1/admin/**, role=ADMIN 만 — SecurityConfig). */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminController {
	private final AdminService admin;

	@PostMapping("/auth/login")
	ApiResponse<StaffLoginResponse> login(@Valid @RequestBody StaffLoginRequest r) {
		return ApiResponse.ok(admin.login(r));
	}

	@GetMapping("/consultations")
	ApiResponse<AdminListResponse> list(@AuthenticationPrincipal AuthUser me, @RequestParam(required = false) String sort,
			@RequestParam(required = false) String status, @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
		return ApiResponse.ok(admin.list(me.id(), sort, status, page, size));
	}

	@GetMapping("/dashboard")
	ApiResponse<Dashboard> dashboard(@AuthenticationPrincipal AuthUser me) {
		return ApiResponse.ok(admin.dashboard(me.id()));
	}

	@GetMapping("/consultations/{id}")
	ApiResponse<AdminDetail> detail(@AuthenticationPrincipal AuthUser me, @PathVariable String id) {
		return ApiResponse.ok(admin.detail(me.id(), id));
	}

	@PatchMapping("/consultations/{id}/triage")
	ApiResponse<TriageResponse> triage(@AuthenticationPrincipal AuthUser me, @PathVariable String id, @Valid @RequestBody TriageRequest r) {
		return ApiResponse.ok(admin.triage(me.id(), id, r));
	}

	@GetMapping("/staff")
	ApiResponse<StaffListResponse> staff(@AuthenticationPrincipal AuthUser me) {
		return ApiResponse.ok(admin.staffList(me.id()));
	}

	@PutMapping("/consultations/{id}/assignee")
	ApiResponse<AssigneeResponse> assign(@AuthenticationPrincipal AuthUser me, @PathVariable String id, @Valid @RequestBody AssigneeRequest r) {
		return ApiResponse.ok(admin.assign(me.id(), id, r));
	}

	@PostMapping("/consultations/{id}/info-requests")
	@ResponseStatus(HttpStatus.CREATED)
	ApiResponse<CreatedResponse> infoRequest(@AuthenticationPrincipal AuthUser me, @PathVariable String id, @Valid @RequestBody InfoRequestBody r) {
		return ApiResponse.ok(admin.requestInfo(me.id(), id, r));
	}

	@PostMapping("/consultations/{id}/referrals")
	@ResponseStatus(HttpStatus.CREATED)
	ApiResponse<CreatedResponse> referral(@AuthenticationPrincipal AuthUser me, @PathVariable String id, @Valid @RequestBody ReferralRequest r) {
		return ApiResponse.ok(admin.refer(me.id(), id, r));
	}

	@PatchMapping("/consultations/{id}/status")
	ApiResponse<StatusResponse> status(@AuthenticationPrincipal AuthUser me, @PathVariable String id, @Valid @RequestBody StatusRequest r) {
		return ApiResponse.ok(admin.status(me.id(), id, r));
	}

	@PostMapping("/consultations/{id}/result")
	@ResponseStatus(HttpStatus.CREATED)
	ApiResponse<ResultResponse> result(@AuthenticationPrincipal AuthUser me, @PathVariable String id, @Valid @RequestBody ResultRequest r) {
		return ApiResponse.ok(admin.close(me.id(), id, r));
	}

	@GetMapping("/consultations/{id}/messages")
	ApiResponse<MessagesResponse> messages(@AuthenticationPrincipal AuthUser me, @PathVariable String id,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
		return ApiResponse.ok(admin.messages(me.id(), id, page, size));
	}

	@PostMapping("/consultations/{id}/messages")
	@ResponseStatus(HttpStatus.CREATED)
	ApiResponse<SendMessageResponse> send(@AuthenticationPrincipal AuthUser me, @PathVariable String id, @Valid @RequestBody StaffMessageRequest r) {
		return ApiResponse.ok(admin.sendMessage(me.id(), id, r));
	}
}
