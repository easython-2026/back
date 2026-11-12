package com.easython.auth;

import com.easython.auth.AuthDtos.*;
import com.easython.common.ApiException;
import com.easython.common.ErrorCode;
import com.easython.common.Languages;
import com.easython.security.JwtService;
import com.easython.user.User;
import com.easython.user.UserRepository;
import java.time.Instant;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {
	private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

	private final UserRepository users;
	private final RefreshTokenRepository refreshTokens;
	private final PasswordEncoder encoder;
	private final JwtService jwt;

	@Transactional(readOnly = true)
	public boolean emailAvailable(String email) {
		if (email == null || !EMAIL.matcher(email.trim()).matches()) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, "error.email.format");
		}
		return !users.existsByEmail(normalize(email));
	}

	@Transactional
	public SignupResponse signup(SignupRequest r) {
		if (!Languages.isSupported(r.language())) throw new ApiException(ErrorCode.VALIDATION_ERROR);
		if (!r.consents().privacy()) throw new ApiException(ErrorCode.CONSENT_REQUIRED);
		String email = normalize(r.email());
		if (users.existsByEmail(email)) throw new ApiException(ErrorCode.DUPLICATED_EMAIL);

		User u = new User();
		u.setEmail(email);
		u.setPasswordHash(encoder.encode(r.password()));
		u.setName(r.name().trim());
		u.setLanguage(r.language());
		u.setConsentPrivacy(true);
		u.setConsentLocation(r.consents().location());
		u.setConsentCamera(r.consents().camera());
		u.setConsentAgreedAt(r.agreedAt().toInstant());
		try {
			users.saveAndFlush(u);
		} catch (DataIntegrityViolationException e) { // 동시 가입 경쟁
			throw new ApiException(ErrorCode.DUPLICATED_EMAIL);
		}
		TokenResponse t = issue(u);
		return new SignupResponse(u.getId(), t.accessToken(), t.refreshToken());
	}

	@Transactional
	public LoginResponse login(LoginRequest r) {
		User u = users.findByEmail(normalize(r.email())).orElseThrow(() -> new ApiException(ErrorCode.INVALID_CREDENTIALS));
		if (!encoder.matches(r.password(), u.getPasswordHash()) || u.getRole() != com.easython.user.Role.WORKER) {
			throw new ApiException(ErrorCode.INVALID_CREDENTIALS); // 담당자 계정은 /admin/auth/login 으로
		}
		TokenResponse t = issue(u);
		return new LoginResponse(t.accessToken(), t.refreshToken(),
				new LoginUser(u.getId(), u.getName(), u.getLanguage(), u.hasWorkplace()));
	}

	/** 리프레시 토큰 회전: 한 번 쓴 토큰은 폐기하고 새 쌍을 발급. */
	@Transactional
	public TokenResponse refresh(String token) {
		var invalid = new ApiException(ErrorCode.INVALID_REFRESH_TOKEN);
		var parsed = jwt.parse(token).filter(p -> JwtService.REFRESH.equals(p.type())).orElseThrow(() -> invalid);
		RefreshToken saved = refreshTokens.findById(parsed.jti()).orElseThrow(() -> invalid);
		if (saved.isRevoked() || saved.getExpiresAt().isBefore(Instant.now())) throw invalid;
		User u = users.findById(saved.getUserId()).orElseThrow(() -> invalid);
		saved.setRevoked(true);
		return issue(u);
	}

	/** 본인 소유 리프레시 토큰만 폐기. 이미 무효/없는 토큰이어도 성공 처리(멱등). */
	@Transactional
	public void logout(String userId, String token) {
		jwt.parse(token).filter(p -> JwtService.REFRESH.equals(p.type()) && userId.equals(p.userId()))
				.flatMap(p -> refreshTokens.findById(p.jti()))
				.ifPresent(rt -> rt.setRevoked(true));
	}

	public TokenResponse issue(User u) {
		JwtService.Refresh rt = jwt.refreshToken(u);
		refreshTokens.save(new RefreshToken(rt.jti(), u.getId(), rt.expiresAt()));
		return new TokenResponse(jwt.accessToken(u), rt.token());
	}

	private static String normalize(String email) {
		return email.trim().toLowerCase();
	}
}
