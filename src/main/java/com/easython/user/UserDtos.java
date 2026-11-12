package com.easython.user;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

public final class UserDtos {
	private UserDtos() {}

	public record ConsentsView(boolean privacy, boolean location, boolean camera) {}

	public record MeResponse(String id, String email, String name, String language, Integer payday,
			boolean paydayAlertEnabled, boolean hasWorkplace, ConsentsView consents, OffsetDateTime createdAt) {}

	/** 보낸 필드만 수정. */
	public record UpdateMeRequest(@Size(min = 1, max = 100) String name, String language) {}

	public record UpdateMeResponse(String name, String language) {}

	public record SettingsRequest(@Min(1) @Max(31) Integer payday, Boolean paydayAlertEnabled) {}

	public record SettingsResponse(Integer payday, boolean paydayAlertEnabled) {}

	public record WorkplaceRequest(
			@NotBlank String name,
			@NotBlank String address,
			@NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
			@NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
			@Positive @Max(5000) Integer radiusMeters) {}

	public record BusinessRequest(@NotBlank String name, @NotBlank String address, @NotBlank String ownerName) {}

	public record WorkplaceUpdateRequest(@Valid WorkplaceRequest workplace, @Valid BusinessRequest business) {}

	public record WorkplaceView(String name, String address, double latitude, double longitude, int radiusMeters) {}

	public record BusinessView(String name, String address, String ownerName, boolean fromContract) {}

	public record WorkplaceResponse(WorkplaceView workplace, BusinessView business) {}
}
