package com.easython.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 앱 안 알림(푸시 아님). 제목·본문은 저장하지 않고 type+payload 로 조회 시 사용자 언어로 만든다. */
@Entity
@Table(name = "notifications", uniqueConstraints = @UniqueConstraint(columnNames = { "userId", "dedupKey" }))
@Getter
@Setter
@NoArgsConstructor
public class Notification {
	public static final String PAYDAY = "PAYDAY";
	public static final String MISSING_RECORD = "MISSING_RECORD";
	public static final String MISMATCH = "MISMATCH";
	public static final String CONSULTATION_UPDATE = "CONSULTATION_UPDATE";
	public static final String INFO_REQUEST = "INFO_REQUEST";

	@Id
	@Column(length = 36)
	private String id = UUID.randomUUID().toString();

	@Column(nullable = false, length = 36)
	private String userId;

	@Column(nullable = false, length = 24)
	private String type;

	/** 이동 대상 정보(JSON: month, comparisonId, consultationId ...) */
	@Column(columnDefinition = "TEXT", nullable = false)
	private String payloadJson;

	/** 같은 알림이 중복 생성되지 않게 하는 키(예: PAYDAY:2026-10) */
	@Column(nullable = false, length = 100)
	private String dedupKey;

	@Column(name = "is_read")
	private boolean read;

	@Column(nullable = false, updatable = false)
	private Instant createdAt = Instant.now();
}
