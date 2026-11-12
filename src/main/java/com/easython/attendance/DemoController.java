package com.easython.attendance;

import com.easython.attendance.AttendanceDtos.SeedRequest;
import com.easython.attendance.AttendanceDtos.SeedResponse;
import com.easython.common.ApiResponse;
import com.easython.security.AuthUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/demo")
@RequiredArgsConstructor
public class DemoController {
	private final DemoService demo;

	@PostMapping("/seed")
	@ResponseStatus(HttpStatus.CREATED)
	ApiResponse<SeedResponse> seed(@AuthenticationPrincipal AuthUser me, @RequestBody(required = false) SeedRequest r) {
		return ApiResponse.ok(demo.seed(me.id(), r));
	}
}
