package com.easython.user;

import com.easython.common.ApiResponse;
import com.easython.security.AuthUser;
import com.easython.user.AccountService.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users/me")
@RequiredArgsConstructor
public class AccountController {
	private final AccountService account;

	public record DeleteMeRequest(@NotBlank String password) {}

	public record DeletionRequest(@NotEmpty List<String> targets) {}

	@GetMapping("/consents")
	ApiResponse<ConsentsResponse> consents(@AuthenticationPrincipal AuthUser me) {
		return ApiResponse.ok(account.consents(me.id()));
	}

	@PatchMapping("/consents")
	ApiResponse<ConsentPatchResponse> patchConsents(@AuthenticationPrincipal AuthUser me, @RequestBody ConsentPatch r) {
		return ApiResponse.ok(account.patchConsents(me.id(), r));
	}

	@DeleteMapping
	ApiResponse<DeleteMeResponse> deleteMe(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody DeleteMeRequest r) {
		return ApiResponse.ok(account.deleteMe(me.id(), r.password(), me.language()));
	}

	@PostMapping("/data-deletion-requests")
	@ResponseStatus(HttpStatus.ACCEPTED)
	ApiResponse<DeletionResponse> requestDeletion(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody DeletionRequest r) {
		DeletionResponse res = account.requestDeletion(me.id(), r.targets()); // 커밋된 뒤에 처리
		account.processDeletion(res.requestId());
		return ApiResponse.ok(res);
	}
}
