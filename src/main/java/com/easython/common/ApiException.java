package com.easython.common;

import lombok.Getter;

@Getter
public class ApiException extends RuntimeException {
	private final ErrorCode code;
	private final String messageKey;

	public ApiException(ErrorCode code) {
		this(code, code.messageKey);
	}

	/** 같은 errorCode에 다른 메시지를 쓰고 싶을 때(예: 이메일 형식 오류). */
	public ApiException(ErrorCode code, String messageKey) {
		super(code.name());
		this.code = code;
		this.messageKey = messageKey;
	}
}
