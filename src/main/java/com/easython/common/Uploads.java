package com.easython.common;

/** 업로드 파일 형식 판별(확장자가 아니라 내용의 매직 바이트). */
public final class Uploads {
	public static final long MAX_DOCUMENT_BYTES = 10L * 1024 * 1024;

	public record Type(String contentType, String ext) {}

	private Uploads() {}

	/** jpg/png/pdf 중 하나가 아니면 415. */
	public static Type detectDocument(byte[] b) {
		if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return new Type("image/jpeg", "jpg");
		if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return new Type("image/png", "png");
		if (b.length >= 5 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F' && b[4] == '-') return new Type("application/pdf", "pdf");
		throw new ApiException(ErrorCode.UNSUPPORTED_FILE_TYPE);
	}
}
