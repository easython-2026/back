package com.easython.consultation;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConsultationEventRepository extends JpaRepository<ConsultationEvent, String> {
	List<ConsultationEvent> findByConsultationIdOrderByAtAsc(String consultationId);
}
