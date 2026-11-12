package com.easython.consultation;

import com.easython.common.ApiResponse;
import com.easython.consultation.ConsultationDtos.*;
import com.easython.security.AuthUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 노동자 쪽 상담 API. */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ConsultationController {
	private final ConsultationService consultations;
	private final ConsultationGenerator generator;

	@GetMapping("/consultation-agencies")
	ApiResponse<AgenciesResponse> agencies(@RequestParam(required = false) String language) {
		return ApiResponse.ok(consultations.agencies(language));
	}

	@PostMapping("/consultations")
	@ResponseStatus(HttpStatus.CREATED)
	ApiResponse<CreateResponse> create(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody CreateRequest r) {
		String id = consultations.create(me.id(), r); // 커밋된 뒤에 요약 생성
		generator.summarize(id);
		return ApiResponse.ok(new CreateResponse(id, Consultation.RECEIVED, "PROCESSING"));
	}

	@GetMapping("/consultations")
	ApiResponse<ListResponse> list(@AuthenticationPrincipal AuthUser me, @RequestParam(required = false) String status,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
		return ApiResponse.ok(consultations.list(me.id(), status, page, size));
	}

	@GetMapping("/consultations/{id}")
	ApiResponse<Detail> detail(@AuthenticationPrincipal AuthUser me, @PathVariable String id) {
		return ApiResponse.ok(consultations.detail(me.id(), id));
	}

	@DeleteMapping("/consultations/{id}/consent")
	ApiResponse<RevokeResponse> revoke(@AuthenticationPrincipal AuthUser me, @PathVariable String id) {
		return ApiResponse.ok(consultations.revoke(me.id(), id));
	}

	@GetMapping("/consultations/{id}/messages")
	ApiResponse<MessagesResponse> messages(@AuthenticationPrincipal AuthUser me, @PathVariable String id,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
		return ApiResponse.ok(consultations.workerMessages(me.id(), id, me.language(), page, size));
	}

	@PostMapping("/consultations/{id}/messages")
	@ResponseStatus(HttpStatus.CREATED)
	ApiResponse<SendMessageResponse> send(@AuthenticationPrincipal AuthUser me, @PathVariable String id, @Valid @RequestBody SendMessageRequest r) {
		return ApiResponse.ok(consultations.sendWorkerMessage(me.id(), me.language(), id, r));
	}
}
