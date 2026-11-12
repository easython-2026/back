package com.easython.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 발급된 리프레시 토큰(jti). 회전·로그아웃 시 revoked 처리. */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class RefreshToken {
	@Id
	@Column(length = 36)
	private String jti;

	@Column(nullable = false, length = 36)
	private String userId;

	@Column(nullable = false)
	private Instant expiresAt;

	private boolean revoked;

	public RefreshToken(String jti, String userId, Instant expiresAt) {
		this.jti = jti;
		this.userId = userId;
		this.expiresAt = expiresAt;
	}
}
