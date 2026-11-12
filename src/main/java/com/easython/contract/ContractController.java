package com.easython.contract;

import com.easython.common.ApiResponse;
import com.easython.contract.ContractDtos.*;
import com.easython.security.AuthUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/contracts")
@RequiredArgsConstructor
public class ContractController {
	private final ContractService contracts;
	private final ContractAnalyzer analyzer;

	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@ResponseStatus(HttpStatus.ACCEPTED)
	ApiResponse<UploadResponse> upload(@AuthenticationPrincipal AuthUser me, @RequestParam("file") MultipartFile file,
			@RequestParam(required = false) String source) {
		String id = contracts.upload(me.id(), file, source); // 커밋된 뒤에 분석 시작
		analyzer.analyze(id);
		return ApiResponse.ok(new UploadResponse(id, "PROCESSING"));
	}

	@GetMapping
	ApiResponse<ListResponse> list(@AuthenticationPrincipal AuthUser me, @RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size) {
		return ApiResponse.ok(contracts.list(me.id(), me.language(), page, size));
	}

	@GetMapping("/{contractId}")
	ApiResponse<DetailResponse> detail(@AuthenticationPrincipal AuthUser me, @PathVariable String contractId) {
		return ApiResponse.ok(contracts.detail(me.id(), contractId, me.language()));
	}

	@PatchMapping("/{contractId}/terms")
	ApiResponse<TermsUpdateResponse> terms(@AuthenticationPrincipal AuthUser me, @PathVariable String contractId,
			@Valid @RequestBody TermsRequest r) {
		return ApiResponse.ok(contracts.updateTerms(me.id(), contractId, me.language(), r));
	}

	@PostMapping("/{contractId}/confirm")
	ApiResponse<ConfirmResponse> confirm(@AuthenticationPrincipal AuthUser me, @PathVariable String contractId) {
		return ApiResponse.ok(contracts.confirm(me.id(), contractId));
	}

	@PostMapping("/{contractId}/reanalyze")
	@ResponseStatus(HttpStatus.ACCEPTED)
	ApiResponse<UploadResponse> reanalyze(@AuthenticationPrincipal AuthUser me, @PathVariable String contractId,
			@RequestBody(required = false) ReanalyzeRequest r) {
		String id = contracts.reanalyze(me.id(), contractId, r == null ? null : r.language());
		analyzer.analyze(id);
		return ApiResponse.ok(new UploadResponse(id, "PROCESSING"));
	}

	@DeleteMapping("/{contractId}")
	ApiResponse<Void> delete(@AuthenticationPrincipal AuthUser me, @PathVariable String contractId) {
		contracts.delete(me.id(), contractId);
		return ApiResponse.ok();
	}
}
