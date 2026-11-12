package com.easython.consultation;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 상태 변경 이력(타임라인). note 는 담당자 내부 메모라 노동자에게는 노출하지 않는다. */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class ConsultationEvent {
	@Id
	@Column(length = 36)
	private String id = UUID.randomUUID().toString();
	@Column(nullable = false, length = 36)
	private String consultationId;
	@Column(nullable = false, length = 16)
	private String status;
	@Column(nullable = false)
	private Instant at = Instant.now();
	private String note;
}
