package com.easython.consultation;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 담당자의 사건 열람 감사 로그(누가 언제 무엇을 열람했는지). */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class AccessLog {
	@Id
	@Column(length = 36)
	private String id = UUID.randomUUID().toString();
	@Column(nullable = false, length = 36)
	private String consultationId;
	@Column(nullable = false, length = 36)
	private String staffId;
	@Column(nullable = false, length = 40)
	private String action;
	@Column(nullable = false)
	private Instant at = Instant.now();
}
