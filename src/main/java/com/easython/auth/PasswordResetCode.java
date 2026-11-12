package com.easython.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 비밀번호 재설정 코드. 원문은 저장하지 않고 해시만 보관, 10분 만료·5회 시도 제한. */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class PasswordResetCode {
	@Id
	@Column(length = 36)
	private String id = UUID.randomUUID().toString();
	@Column(nullable = false, length = 36)
	private String userId;
	@Column(nullable = false, length = 100)
	private String codeHash;
	@Column(nullable = false)
	private Instant expiresAt;
	private int attempts;
	private boolean used;
	@Column(nullable = false)
	private Instant createdAt = Instant.now();
}
