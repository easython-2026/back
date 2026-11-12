package com.easython.attendance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 출근/퇴근 한 번의 증거(서버 수신 시각, GPS, 사진 해시). 생성 후 수정하지 않는다. */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class AttendanceEvent {
	public enum Type { IN, OUT }

	@Id
	@Column(length = 36)
	private String id = UUID.randomUUID().toString();

	@Column(nullable = false, length = 36)
	private String recordId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 4)
	private Type type;

	@Column(nullable = false)
	private Instant serverAt;
	private String clientCapturedAt;

	private String photoKey;
	@Column(length = 64)
	private String photoHash;

	private double latitude;
	private double longitude;
	private double accuracyMeters;
	private int distanceMeters;
	private boolean verified;
}
