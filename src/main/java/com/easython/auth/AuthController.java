package com.easython.auth;

import com.easython.auth.AuthDtos.*;
import com.easython.common.ApiResponse;
import com.easython.security.AuthUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {
	private final AuthService auth;
	private final PasswordResetService resets;
	private final org.springframework.context.MessageSource messages;

	@PostMapping("/signup")
	ApiResponse<SignupResponse> signup(@Valid @RequestBody SignupRequest r) {
		return ApiResponse.ok(auth.signup(r));
	}

	@GetMapping("/email/check")
	ApiResponse<EmailCheckResponse> emailCheck(@RequestParam(required = false) String email) {
		return ApiResponse.ok(new EmailCheckResponse(auth.emailAvailable(email)));
	}

	@PostMapping("/login")
	ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest r) {
		return ApiResponse.ok(auth.login(r));
	}

	@PostMapping("/refresh")
	ApiResponse<TokenResponse> refresh(@Valid @RequestBody RefreshRequest r) {
		return ApiResponse.ok(auth.refresh(r.refreshToken()));
	}

	@PostMapping("/password/reset-request")
	ApiResponse<Void> resetRequest(@Valid @RequestBody ResetRequest r, jakarta.servlet.http.HttpServletRequest req) {
		resets.request(r.email());
		return ApiResponse.okMessage(messages.getMessage("password.reset.sent", null, com.easython.common.GlobalExceptionHandler.locale(req)));
	}

	@PostMapping("/password/reset")
	ApiResponse<Void> reset(@Valid @RequestBody ResetConfirm r, jakarta.servlet.http.HttpServletRequest req) {
		resets.reset(r.email(), r.code(), r.newPassword());
		return ApiResponse.okMessage(messages.getMessage("password.reset.done", null, com.easython.common.GlobalExceptionHandler.locale(req)));
	}

	public record ResetRequest(@jakarta.validation.constraints.NotBlank String email) {}

	public record ResetConfirm(@jakarta.validation.constraints.NotBlank String email, @jakarta.validation.constraints.NotBlank String code,
			@jakarta.validation.constraints.NotBlank String newPassword) {}

	@PostMapping("/logout")
	ApiResponse<Void> logout(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody RefreshRequest r) {
		auth.logout(me.id(), r.refreshToken());
		return ApiResponse.ok();
	}
}
