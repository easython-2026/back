package com.easython.storage;

import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

public interface FileStorage {
	record SignedUrl(String url, Instant expiresAt) {}

	void save(String key, byte[] bytes);
	byte[] load(String key);
	void delete(String key);
	SignedUrl signedUrl(String key, Duration ttl);

	public static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	public static Optional<String> contentType(String key) {
		if (key.endsWith(".png")) return Optional.of("image/png");
		if (key.endsWith(".jpg")) return Optional.of("image/jpeg");
		if (key.endsWith(".pdf")) return Optional.of("application/pdf");
		return Optional.empty();
	}
}
