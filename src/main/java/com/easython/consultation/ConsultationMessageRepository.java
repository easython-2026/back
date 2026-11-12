package com.easython.consultation;

import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConsultationMessageRepository extends JpaRepository<ConsultationMessage, String> {
	Page<ConsultationMessage> findByConsultationIdOrderByCreatedAtAsc(String consultationId, Pageable pageable);

	long countByConsultationIdAndSenderAndReadByWorkerFalse(String consultationId, String sender);

	long countByConsultationIdAndSenderAndReadByStaffFalse(String consultationId, String sender);

	List<ConsultationMessage> findByConsultationIdAndSenderAndReadByWorkerFalse(String consultationId, String sender);

	List<ConsultationMessage> findByConsultationIdAndSenderAndReadByStaffFalse(String consultationId, String sender);

	List<ConsultationMessage> findByConsultationIdIn(Collection<String> consultationIds);
}
