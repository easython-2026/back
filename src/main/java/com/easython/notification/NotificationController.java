package com.easython.notification;

import com.easython.common.ApiResponse;
import com.easython.notification.NotificationService.ListResponse;
import com.easython.notification.NotificationService.ReadResponse;
import com.easython.security.AuthUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {
	private final NotificationService notifications;

	@GetMapping
	ApiResponse<ListResponse> list(@AuthenticationPrincipal AuthUser me, @RequestParam(defaultValue = "false") boolean unreadOnly,
			@RequestParam(defaultValue = "false") boolean popupOnly, @RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size) {
		return ApiResponse.ok(notifications.list(me.id(), me.language(), unreadOnly, popupOnly, page, size));
	}

	@PatchMapping("/{notificationId}/read")
	ApiResponse<ReadResponse> read(@AuthenticationPrincipal AuthUser me, @PathVariable String notificationId) {
		return ApiResponse.ok(notifications.read(me.id(), notificationId));
	}
}
