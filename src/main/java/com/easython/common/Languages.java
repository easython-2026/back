package com.easython.common;

import java.util.List;

/** 지원 언어. 확정 전까지 서버 설정값(여기)으로 관리. 추가하려면 한 줄 + messages_xx.properties. */
public final class Languages {
	public static final String DEFAULT = "ko";

	public record Language(String code, String nameNative, String nameKo) {}

	public static final List<Language> ALL = List.of(
			new Language("ko", "한국어", "한국어"),
			new Language("en", "English", "영어"),
			new Language("vi", "Tiếng Việt", "베트남어"));

	private Languages() {}

	public static boolean isSupported(String code) {
		return code != null && ALL.stream().anyMatch(l -> l.code().equals(code));
	}
}
