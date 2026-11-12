package com.easython.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

public final class AuthDtos {
	private AuthDtos() {}

	public record Consents(@NotNull Boolean privacy, @NotNull Boolean location, @NotNull Boolean camera) {}

	public record SignupRequest(
			@NotBlank @Email @Size(max = 255) String email,
			@NotBlank @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,}$") String password,
			@NotBlank @Size(max = 100) String name,
			@NotBlank String language,
			@NotNull @Valid Consents consents,
			@NotNull OffsetDateTime agreedAt) {}

	public record LoginRequest(@NotBlank String email, @NotBlank String password) {}

	public record RefreshRequest(@NotBlank String refreshToken) {}

	public record SignupResponse(String userId, String accessToken, String refreshToken) {}

	public record TokenResponse(String accessToken, String refreshToken) {}

	public record LoginUser(String id, String name, String language, boolean hasWorkplace) {}

	public record LoginResponse(String accessToken, String refreshToken, LoginUser user) {}

	public record EmailCheckResponse(boolean available) {}
}
