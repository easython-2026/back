package com.easython.storage;

import com.easython.common.ApiException;
import com.easython.common.ErrorCode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 증거 파일 저장소(로컬 디스크). 원본은 덮어쓰기 불가(CREATE_NEW), 조회는 만료되는 서명 URL로만.
 * ponytail: 로컬 디스크 1대 전용. AWS S3로 옮길 때 이 클래스를 인터페이스로 빼고 S3 구현(presigned URL)을 추가.
 */
@Component
public class FileStorage {
	public record SignedUrl(String url, Instant expiresAt) {}

	private final Path dir;
	private final byte[] secret;
	private final String baseUrl;

	public FileStorage(@Value("${app.storage.local-dir}") String dir, @Value("${app.storage.url-secret}") String secret,
			@Value("${app.storage.public-base-url}") String baseUrl) throws IOException {
		this.dir = Path.of(dir).toAbsolutePath().normalize();
		Files.createDirectories(this.dir);
		this.secret = secret.getBytes(StandardCharsets.UTF_8);
		this.baseUrl = baseUrl;
	}

	public void save(String key, byte[] bytes) {
		try {
			Files.write(resolve(key), bytes, StandardOpenOption.CREATE_NEW);
		} catch (IOException e) {
			throw new IllegalStateException("file save failed: " + key, e);
		}
	}

	/** 서버 내부용 읽기(서명 불필요). AI 분석 등. */
	public byte[] load(String key) {
		try {
			return Files.readAllBytes(resolve(key));
		} catch (IOException e) {
			throw new IllegalStateException("file load failed: " + key, e);
		}
	}

	/** 사용자 삭제 요청 시에만 사용. */
	public void delete(String key) {
		try {
			Files.deleteIfExists(resolve(key));
		} catch (IOException e) {
			throw new IllegalStateException("file delete failed: " + key, e);
		}
	}

	public SignedUrl signedUrl(String key, Duration ttl) {
		Instant exp = Instant.now().plus(ttl);
		long exp1 = exp.getEpochSecond();
		return new SignedUrl(baseUrl + "/api/v1/files/" + key + "?exp=" + exp1 + "&sig=" + sign(key, exp1), Instant.ofEpochSecond(exp1));
	}

	/** 서명·만료 확인 후 파일 내용. 서명 불일치/만료는 403, 파일 없음은 404. */
	public byte[] read(String key, long exp, String sig) {
		if (!MessageDigest.isEqual(sign(key, exp).getBytes(StandardCharsets.UTF_8), sig.getBytes(StandardCharsets.UTF_8))
				|| Instant.now().getEpochSecond() > exp) {
			throw new ApiException(ErrorCode.FORBIDDEN);
		}
		try {
			Path p = resolve(key);
			if (!Files.exists(p)) throw new ApiException(ErrorCode.NOT_FOUND);
			return Files.readAllBytes(p);
		} catch (IOException e) {
			throw new IllegalStateException("file read failed: " + key, e);
		}
	}

	public static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	public static Optional<String> contentType(String key) {
		return key.endsWith(".png") ? Optional.of("image/png") : key.endsWith(".jpg") ? Optional.of("image/jpeg") : Optional.empty();
	}

	private Path resolve(String key) {
		if (!key.matches("[A-Za-z0-9_.-]+")) throw new ApiException(ErrorCode.NOT_FOUND); // 경로 탈출 방지
		Path p = dir.resolve(key).normalize();
		if (!p.startsWith(dir)) throw new ApiException(ErrorCode.NOT_FOUND);
		return p;
	}

	private String sign(String key, long exp) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret, "HmacSHA256"));
			return HexFormat.of().formatHex(mac.doFinal((key + "." + exp).getBytes(StandardCharsets.UTF_8)));
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}
}
