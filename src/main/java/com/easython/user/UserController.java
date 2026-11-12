package com.easython.user;

import com.easython.common.ApiResponse;
import com.easython.security.AuthUser;
import com.easython.user.UserDtos.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users/me")
@RequiredArgsConstructor
public class UserController {
	private final UserService users;

	@GetMapping
	ApiResponse<MeResponse> me(@AuthenticationPrincipal AuthUser me) {
		return ApiResponse.ok(users.me(me.id()));
	}

	@PatchMapping
	ApiResponse<UpdateMeResponse> update(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody UpdateMeRequest r) {
		return ApiResponse.ok(users.update(me.id(), r));
	}

	@PatchMapping("/settings")
	ApiResponse<SettingsResponse> settings(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody SettingsRequest r) {
		return ApiResponse.ok(users.settings(me.id(), r));
	}

	@GetMapping("/workplace")
	ApiResponse<WorkplaceResponse> workplace(@AuthenticationPrincipal AuthUser me) {
		return ApiResponse.ok(users.workplace(me.id()));
	}

	@PutMapping("/workplace")
	ApiResponse<WorkplaceResponse> putWorkplace(@AuthenticationPrincipal AuthUser me,
			@Valid @RequestBody WorkplaceUpdateRequest r) {
		return ApiResponse.ok(users.putWorkplace(me.id(), r));
	}
}
