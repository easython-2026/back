package com.easython.common;

import com.easython.security.AuthUser;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {
	private final MessageSource messages;

	@ExceptionHandler(ApiException.class)
	ResponseEntity<ApiResponse<Void>> api(ApiException e, HttpServletRequest req) {
		return build(e.getCode(), e.getMessageKey(), req);
	}

	/** Spring이 던지는 400/404/405/415 등 표준 예외. 검증·파싱 오류는 VALIDATION_ERROR로 통일. */
	@ExceptionHandler(Exception.class)
	ResponseEntity<ApiResponse<Void>> other(Exception e, HttpServletRequest req) {
		if (e instanceof ErrorResponse er && er.getStatusCode().is4xxClientError()) {
			int s = er.getStatusCode().value();
			ErrorCode code = s == 404 ? ErrorCode.NOT_FOUND : s == 413 ? ErrorCode.FILE_TOO_LARGE
					: s == 415 ? ErrorCode.UNSUPPORTED_FILE_TYPE : ErrorCode.VALIDATION_ERROR;
			return build(code, code.messageKey, req);
		}
		if (e instanceof org.springframework.http.converter.HttpMessageNotReadableException
				|| e instanceof org.springframework.web.method.annotation.MethodArgumentTypeMismatchException) {
			return build(ErrorCode.VALIDATION_ERROR, ErrorCode.VALIDATION_ERROR.messageKey, req);
		}
		log.error("Unhandled exception", e);
		return build(ErrorCode.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_SERVER_ERROR.messageKey, req);
	}

	private ResponseEntity<ApiResponse<Void>> build(ErrorCode code, String key, HttpServletRequest req) {
		String msg = messages.getMessage(key, null, locale(req));
		return ResponseEntity.status(code.status).body(ApiResponse.fail(code.name(), msg));
	}

	/** 로그인 사용자는 선택 언어, 아니면 Accept-Language 중 지원 언어, 없으면 ko. */
	public static Locale locale(HttpServletRequest req) {
		Authentication a = SecurityContextHolder.getContext().getAuthentication();
		if (a != null && a.getPrincipal() instanceof AuthUser u && Languages.isSupported(u.language())) {
			return Locale.forLanguageTag(u.language());
		}
		// 헤더가 없으면 서버 기본 로케일이 끼어들지 않도록 바로 ko
		var locales = req.getHeader("Accept-Language") == null ? null : req.getLocales();
		while (locales != null && locales.hasMoreElements()) {
			String lang = locales.nextElement().getLanguage();
			if (Languages.isSupported(lang)) return Locale.forLanguageTag(lang);
		}
		return Locale.forLanguageTag(Languages.DEFAULT);
	}
}
