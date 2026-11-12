package com.easython.complaint;

import com.easython.common.ApiResponse;
import com.easython.complaint.ComplaintDtos.*;
import com.easython.security.AuthUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

@RestController
@RequestMapping("/api/v1/complaints")
@RequiredArgsConstructor
public class ComplaintController {
	private final ComplaintService complaints;
	private final ComplaintGenerator generator;

	@PostMapping
	@ResponseStatus(HttpStatus.ACCEPTED)
	ApiResponse<CreateResponse> create(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody CreateRequest r) {
		String id = complaints.create(me.id(), r); // 커밋된 뒤에 생성 시작
		generator.generate(id);
		return ApiResponse.ok(new CreateResponse(id, "PROCESSING"));
	}

	@GetMapping
	ApiResponse<ListResponse> list(@AuthenticationPrincipal AuthUser me, @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
		return ApiResponse.ok(complaints.list(me.id(), page, size));
	}

	@GetMapping("/{complaintId}")
	ApiResponse<Detail> detail(@AuthenticationPrincipal AuthUser me, @PathVariable String complaintId) {
		return ApiResponse.ok(complaints.detail(me.id(), complaintId, me.language()));
	}

	@PatchMapping("/{complaintId}")
	ApiResponse<Detail> patch(@AuthenticationPrincipal AuthUser me, @PathVariable String complaintId, @Valid @RequestBody PatchRequest r) {
		Detail d = complaints.patch(me.id(), me.language(), complaintId, r);
		if (Boolean.TRUE.equals(r.retranslate())) generator.retranslate(complaintId);
		return ApiResponse.ok(d);
	}

	@PutMapping("/{complaintId}/evidences")
	ApiResponse<EvidencesResponse> evidences(@AuthenticationPrincipal AuthUser me, @PathVariable String complaintId, @Valid @RequestBody EvidencesRequest r) {
		return ApiResponse.ok(complaints.putEvidences(me.id(), me.language(), complaintId, r.evidenceIds()));
	}

	/** 이 API만 JSON 봉투 없이 PDF 파일을 직접 반환(실패 시에는 공통 에러 JSON). */
	@GetMapping("/{complaintId}/pdf")
	ResponseEntity<byte[]> pdf(@AuthenticationPrincipal AuthUser me, @PathVariable String complaintId) {
		byte[] body = complaints.pdf(me.id(), complaintId, me.language());
		return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
				.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"complaint_" + complaintId + ".pdf\"").body(body);
	}
}
