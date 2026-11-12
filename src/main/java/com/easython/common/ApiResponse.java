package com.easython.common;

import com.fasterxml.jackson.annotation.JsonInclude;

/** success/data/message는 항상 포함, errorCode는 실패 시에만. */
public record ApiResponse<T>(boolean success, T data, String message, @JsonInclude(JsonInclude.Include.NON_NULL) String errorCode) {

	public static <T> ApiResponse<T> ok(T data) {
		return new ApiResponse<>(true, data, null, null);
	}

	public static ApiResponse<Void> ok() {
		return new ApiResponse<>(true, null, null, null);
	}

	/** 데이터 없이 안내 메시지를 함께 주는 성공 응답(예: 비밀번호 재설정). */
	public static ApiResponse<Void> okMessage(String message) {
		return new ApiResponse<>(true, null, message, null);
	}

	public static ApiResponse<Void> fail(String errorCode, String message) {
		return new ApiResponse<>(false, null, message, errorCode);
	}
}
