package com.easython.consultation;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 노무사·통역 연계 기록. */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class Referral {
	@Id
	@Column(length = 36)
	private String id = UUID.randomUUID().toString();
	@Column(nullable = false, length = 36)
	private String consultationId;
	@Column(nullable = false, length = 20)
	private String type;
	@Column(nullable = false)
	private String organization;
	private String contact;
	@Column(columnDefinition = "TEXT")
	private String note;
	@Column(nullable = false)
	private Instant createdAt = Instant.now();
}
