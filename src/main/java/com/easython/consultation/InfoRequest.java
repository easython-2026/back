package com.easython.consultation;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 담당자의 추가 자료 요청(한국어 원문 + 노동자 언어 번역). */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class InfoRequest {
	@Id
	@Column(length = 36)
	private String id = UUID.randomUUID().toString();
	@Column(nullable = false, length = 36)
	private String consultationId;
	@Column(columnDefinition = "TEXT", nullable = false)
	private String messageKo;
	@Column(columnDefinition = "TEXT")
	private String messageTranslated;
	/** CONTRACT / PAYSLIP / ATTENDANCE / OTHER CSV */
	private String requestedItems;
	@Column(nullable = false)
	private Instant createdAt = Instant.now();
}
