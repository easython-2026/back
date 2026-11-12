package com.easython.security;

import com.easython.user.User;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JwtService {
	public static final String ACCESS = "access";
	public static final String REFRESH = "refresh";

	public record Parsed(String userId, String type, String jti) {}

	public record Refresh(String token, String jti, Instant expiresAt) {}

	private final byte[] key;
	private final Duration accessTtl;
	private final Duration refreshTtl;

	public JwtService(@Value("${app.jwt.secret}") String secret,
			@Value("${app.jwt.access-minutes}") long accessMinutes,
			@Value("${app.jwt.refresh-days}") long refreshDays, @Value("${app.demo.enabled}") boolean demo) {
		this.key = secret.getBytes(StandardCharsets.UTF_8);
		if (key.length < 32) throw new IllegalStateException("app.jwt.secret must be at least 32 bytes");
		// 운영(데모 꺼짐)에서 개발용 기본 키를 쓰면 누구나 토큰을 위조할 수 있으므로 기동 자체를 막는다
		if (!demo && secret.startsWith("dev-only-")) throw new IllegalStateException("운영 환경에서는 JWT_SECRET 환경변수를 반드시 지정하세요.");
		this.accessTtl = Duration.ofMinutes(accessMinutes);
		this.refreshTtl = Duration.ofDays(refreshDays);
	}

	public String accessToken(User u) {
		return sign(u.getId(), ACCESS, UUID.randomUUID().toString(), u.getRole().name(), Instant.now().plus(accessTtl));
	}

	public Refresh refreshToken(User u) {
		String jti = UUID.randomUUID().toString();
		Instant exp = Instant.now().plus(refreshTtl);
		return new Refresh(sign(u.getId(), REFRESH, jti, u.getRole().name(), exp), jti, exp);
	}

	/** 서명·만료가 유효한 토큰만 반환. 잘못된 토큰은 빈 값. */
	public Optional<Parsed> parse(String token) {
		try {
			SignedJWT jwt = SignedJWT.parse(token);
			if (!jwt.verify(new MACVerifier(key))) return Optional.empty();
			JWTClaimsSet c = jwt.getJWTClaimsSet();
			if (c.getExpirationTime() == null || c.getExpirationTime().before(new Date())) return Optional.empty();
			return Optional.of(new Parsed(c.getSubject(), c.getStringClaim("typ"), c.getJWTID()));
		} catch (Exception e) {
			return Optional.empty();
		}
	}

	private String sign(String sub, String typ, String jti, String role, Instant exp) {
		try {
			JWTClaimsSet claims = new JWTClaimsSet.Builder().subject(sub).jwtID(jti).claim("typ", typ).claim("role", role)
					.issueTime(new Date()).expirationTime(Date.from(exp)).build();
			SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
			jwt.sign(new MACSigner(key));
			return jwt.serialize();
		} catch (Exception e) {
			throw new IllegalStateException("JWT signing failed", e);
		}
	}
}
