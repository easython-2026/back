package com.easython.payroll;

import com.easython.common.ApiResponse;
import com.easython.payroll.PayrollDtos.*;
import com.easython.security.AuthUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
@RequestMapping("/api/v1/payroll")
@RequiredArgsConstructor
public class PayrollController {
	private final StatementService statements;
	private final StatementAnalyzer analyzer;
	private final ComparisonService comparisons;

	@PostMapping(value = "/statements", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@ResponseStatus(HttpStatus.ACCEPTED)
	ApiResponse<UploadResponse> upload(@AuthenticationPrincipal AuthUser me, @RequestParam("file") MultipartFile file,
			@RequestParam(required = false) String month) {
		String id = statements.upload(me.id(), file, month); // 커밋된 뒤에 분석 시작
		analyzer.analyze(id);
		return ApiResponse.ok(new UploadResponse(id, "PROCESSING"));
	}

	@PostMapping("/statements/manual")
	@ResponseStatus(HttpStatus.CREATED)
	ApiResponse<ManualResponse> manual(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody ManualRequest r) {
		return ApiResponse.ok(statements.manual(me.id(), r));
	}

	@GetMapping("/statements/{statementId}")
	ApiResponse<StatementDetail> statement(@AuthenticationPrincipal AuthUser me, @PathVariable String statementId) {
		return ApiResponse.ok(statements.detail(me.id(), statementId));
	}

	@PatchMapping("/statements/{statementId}")
	ApiResponse<StatementDetail> patch(@AuthenticationPrincipal AuthUser me, @PathVariable String statementId,
			@Valid @RequestBody StatementPatchRequest r) {
		return ApiResponse.ok(statements.patch(me.id(), statementId, r));
	}

	@PostMapping("/comparisons")
	@ResponseStatus(HttpStatus.CREATED)
	ApiResponse<ComparisonDetail> compare(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody ComparisonRequest r) {
		return ApiResponse.ok(comparisons.compare(me.id(), me.language(), r));
	}

	@GetMapping("/comparisons")
	ApiResponse<MonthState> state(@AuthenticationPrincipal AuthUser me, @RequestParam(required = false) String month) {
		return ApiResponse.ok(comparisons.state(me.id(), month == null ? "" : month));
	}

	@GetMapping("/comparisons/{comparisonId}")
	ApiResponse<ComparisonDetail> comparison(@AuthenticationPrincipal AuthUser me, @PathVariable String comparisonId) {
		return ApiResponse.ok(comparisons.detail(me.id(), comparisonId, me.language()));
	}
}
