package com.easython.attendance;

import com.easython.attendance.AttendanceDtos.*;
import com.easython.common.ApiResponse;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/attendance")
@RequiredArgsConstructor
public class AttendanceController {
	private final AttendanceService attendance;
	private final AttendanceQueryService query;

	@PostMapping("/location-check")
	ApiResponse<LocationCheckResponse> locationCheck(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody LocationCheckRequest r) {
		return ApiResponse.ok(attendance.locationCheck(me.id(), r));
	}

	@PostMapping(value = "/clock-in", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@ResponseStatus(HttpStatus.CREATED)
	ApiResponse<ClockInResponse> clockIn(@AuthenticationPrincipal AuthUser me, @RequestParam String verificationToken,
			@RequestParam("photo") MultipartFile photo, @RequestParam double latitude, @RequestParam double longitude,
			@RequestParam double accuracyMeters, @RequestParam(required = false) String clientCapturedAt) {
		return ApiResponse.ok(attendance.clockIn(me.id(), verificationToken, photo, latitude, longitude, accuracyMeters, clientCapturedAt));
	}

	@PostMapping(value = "/clock-out", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@ResponseStatus(HttpStatus.CREATED)
	ApiResponse<ClockOutResponse> clockOut(@AuthenticationPrincipal AuthUser me, @RequestParam String verificationToken,
			@RequestParam("photo") MultipartFile photo, @RequestParam double latitude, @RequestParam double longitude,
			@RequestParam double accuracyMeters, @RequestParam(required = false) String clientCapturedAt) {
		return ApiResponse.ok(attendance.clockOut(me.id(), verificationToken, photo, latitude, longitude, accuracyMeters, clientCapturedAt));
	}

	@GetMapping("/today")
	ApiResponse<TodayResponse> today(@AuthenticationPrincipal AuthUser me) {
		return ApiResponse.ok(query.today(me.id()));
	}

	@GetMapping("/weekly")
	ApiResponse<WeeklyResponse> weekly(@AuthenticationPrincipal AuthUser me, @RequestParam(required = false) String weekStart) {
		return ApiResponse.ok(query.weekly(me.id(), weekStart));
	}

	@GetMapping("/monthly")
	ApiResponse<MonthlyResponse> monthly(@AuthenticationPrincipal AuthUser me, @RequestParam(required = false) String month) {
		return ApiResponse.ok(query.monthly(me.id(), month));
	}

	@GetMapping("/issues")
	ApiResponse<IssuesResponse> issues(@AuthenticationPrincipal AuthUser me, @RequestParam(required = false) String month) {
		return ApiResponse.ok(query.issues(me.id(), me.language(), month));
	}

	@GetMapping("/anomalies")
	ApiResponse<AnomaliesResponse> anomalies(@AuthenticationPrincipal AuthUser me, @RequestParam(required = false) String month) {
		return ApiResponse.ok(query.anomalies(me.id(), me.language(), month));
	}

	@GetMapping("/records/{recordId}")
	ApiResponse<DetailResponse> detail(@AuthenticationPrincipal AuthUser me, @PathVariable String recordId) {
		return ApiResponse.ok(query.detail(me.id(), recordId));
	}

	@PutMapping("/records/{recordId}/break")
	ApiResponse<BreakResponse> putBreak(@AuthenticationPrincipal AuthUser me, @PathVariable String recordId,
			@Valid @RequestBody BreakRequest r) {
		return ApiResponse.ok(attendance.putBreak(me.id(), recordId, r.breakMinutes()));
	}

	@PatchMapping("/records/{recordId}")
	ApiResponse<PatchResponse> patch(@AuthenticationPrincipal AuthUser me, @PathVariable String recordId,
			@Valid @RequestBody PatchRequest r) {
		return ApiResponse.ok(attendance.patch(me.id(), recordId, r));
	}

	@PostMapping("/records")
	@ResponseStatus(HttpStatus.CREATED)
	ApiResponse<ManualResponse> addManual(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody ManualRequest r) {
		return ApiResponse.ok(attendance.addManual(me.id(), r));
	}
}
