package com.easython.attendance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** GPS 인증 성공 시 발급되는 1회용 토큰(5분). 출퇴근 사진 업로드 때 소모. */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class LocationToken {
	public enum Type { CLOCK_IN, CLOCK_OUT }

	@Id
	@Column(length = 64)
	private String token;

	@Column(nullable = false, length = 36)
	private String userId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private Type type;

	@Column(nullable = false)
	private Instant expiresAt;

	private boolean used;
}
