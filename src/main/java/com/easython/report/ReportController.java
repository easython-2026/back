package com.easython.report;

import com.easython.common.ApiResponse;
import com.easython.security.AuthUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ReportController {
	private final ReportService reports;
	private final EvidenceService evidences;

	@GetMapping("/reports/monthly")
	ApiResponse<ReportService.MonthlyReport> monthly(@AuthenticationPrincipal AuthUser me, @RequestParam(required = false) String month) {
		return ApiResponse.ok(reports.monthly(me.id(), me.language(), month == null ? "" : month));
	}

	@GetMapping("/evidences")
	ApiResponse<EvidenceService.ListResponse> evidences(@AuthenticationPrincipal AuthUser me, @RequestParam(required = false) String month,
			@RequestParam(required = false) String from, @RequestParam(required = false) String to, @RequestParam(required = false) String type,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
		return ApiResponse.ok(evidences.list(me.id(), me.language(), month, from, to, type, page, size));
	}
}
