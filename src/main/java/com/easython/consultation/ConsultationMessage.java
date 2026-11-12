package com.easython.consultation;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 노동자-담당자 번역 메시지. WORKER 작성문은 koText 가 번역, STAFF 작성문은 workerText 가 번역. */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class ConsultationMessage {
	@Id
	@Column(length = 36)
	private String id = UUID.randomUUID().toString();
	@Column(nullable = false, length = 36)
	private String consultationId;
	/** WORKER / STAFF */
	@Column(nullable = false, length = 8)
	private String sender;
	@Column(columnDefinition = "TEXT")
	private String koText;
	@Column(columnDefinition = "TEXT")
	private String workerText;
	/** DONE / FAILED */
	@Column(nullable = false, length = 8)
	private String translationStatus = "DONE";
	@Column(columnDefinition = "TEXT")
	private String attachedEvidenceIds = "";
	private boolean readByWorker;
	private boolean readByStaff;
	@Column(nullable = false, updatable = false)
	private Instant createdAt = Instant.now();
}
