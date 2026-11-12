package com.easython.auth;

import com.easython.common.ApiException;
import com.easython.common.ErrorCode;
import com.easython.user.Role;
import com.easython.user.User;
import com.easython.user.UserRepository;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 비밀번호 재설정: 이메일로 6자리 코드 발송 → 코드 확인 후 변경.
 * 계정 존재 여부를 드러내지 않도록 요청은 항상 같은 응답. 같은 이메일은 15분에 5번까지.
 */
@Service
@RequiredArgsConstructor
public class PasswordResetService {
	private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);
	private static final Duration TTL = Duration.ofMinutes(10);
	private static final Duration WINDOW = Duration.ofMinutes(15);
	private static final int MAX_REQUESTS = 5, MAX_ATTEMPTS = 5;
	private static final SecureRandom RANDOM = new SecureRandom();

	/** ponytail: 서버 1대 기준 메모리 요청 제한. 여러 대로 늘리면 Redis 등 공유 저장소로. */
	private final Map<String, Deque<Instant>> requests = new ConcurrentHashMap<>();

	private final UserRepository users;
	private final PasswordResetCodeRepository codes;
	private final RefreshTokenRepository refreshTokens;
	private final PasswordEncoder encoder;
	private final MessageSource messages;
	private final ObjectProvider<JavaMailSender> mail;

	@Value("${spring.mail.username:}")
	private String mailFrom;
	@Value("${app.demo.enabled}")
	private boolean demo;

	@Transactional
	public void request(String rawEmail) {
		String email = rawEmail.trim().toLowerCase();
		if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) throw new ApiException(ErrorCode.VALIDATION_ERROR, "error.email.format");
		rateLimit(email);
		User u = users.findByEmail(email).filter(x -> x.getRole() == Role.WORKER && x.getDeletedAt() == null).orElse(null);
		if (u == null) return; // 없는 계정이어도 같은 응답

		String code = String.format("%06d", RANDOM.nextInt(1_000_000));
		PasswordResetCode c = new PasswordResetCode();
		c.setUserId(u.getId());
		c.setCodeHash(encoder.encode(code));
		c.setExpiresAt(Instant.now().plus(TTL));
		codes.save(c);
		send(u, code);
	}

	@Transactional(noRollbackFor = ApiException.class) // 틀린 시도 횟수는 실패해도 남긴다
	public void reset(String rawEmail, String code, String newPassword) {
		if (newPassword == null || !newPassword.matches("^(?=.*[A-Za-z])(?=.*\\d).{8,}$")) throw new ApiException(ErrorCode.VALIDATION_ERROR);
		User u = users.findByEmail(rawEmail.trim().toLowerCase()).filter(x -> x.getRole() == Role.WORKER && x.getDeletedAt() == null)
				.orElseThrow(() -> new ApiException(ErrorCode.INVALID_CODE));
		PasswordResetCode c = codes.findFirstByUserIdAndUsedFalseOrderByCreatedAtDesc(u.getId()).orElseThrow(() -> new ApiException(ErrorCode.INVALID_CODE));
		if (c.getExpiresAt().isBefore(Instant.now()) || c.getAttempts() >= MAX_ATTEMPTS) throw new ApiException(ErrorCode.INVALID_CODE);
		if (code == null || !encoder.matches(code, c.getCodeHash())) {
			c.setAttempts(c.getAttempts() + 1);
			throw new ApiException(ErrorCode.INVALID_CODE);
		}
		c.setUsed(true);
		u.setPasswordHash(encoder.encode(newPassword));
		refreshTokens.findByUserIdAndRevokedFalse(u.getId()).forEach(t -> t.setRevoked(true)); // 다른 기기 로그인 끊기
	}

	private void rateLimit(String email) {
		Instant now = Instant.now();
		Deque<Instant> q = requests.computeIfAbsent(email, k -> new ArrayDeque<>());
		synchronized (q) {
			while (!q.isEmpty() && q.peekFirst().isBefore(now.minus(WINDOW))) q.pollFirst();
			if (q.size() >= MAX_REQUESTS) throw new ApiException(ErrorCode.TOO_MANY_REQUESTS);
			q.addLast(now);
		}
	}

	private void send(User u, String code) {
		Locale l = Locale.forLanguageTag(u.getLanguage());
		JavaMailSender sender = mail.getIfAvailable();
		if (sender == null || mailFrom.isBlank()) {
			// 메일 미설정: 데모 환경에서만 개발 확인용으로 로그에 남긴다(운영에서는 절대 남기지 않음)
			if (demo) log.warn("[데모] 메일 미설정 - 비밀번호 재설정 코드 {} (user={})", code, u.getId());
			else log.error("메일 설정(MAIL_USERNAME/MAIL_PASSWORD)이 없어 재설정 코드를 보낼 수 없습니다.");
			return;
		}
		try {
			SimpleMailMessage m = new SimpleMailMessage();
			m.setFrom(mailFrom);
			m.setTo(u.getEmail());
			m.setSubject(messages.getMessage("password.reset.mail.subject", null, l));
			m.setText(messages.getMessage("password.reset.mail.body", new Object[] { code }, l));
			sender.send(m);
		} catch (Exception e) {
			log.error("재설정 메일 발송 실패 user={}", u.getId(), e);
		}
	}
}
