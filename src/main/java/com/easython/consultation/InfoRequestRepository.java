package com.easython.consultation;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InfoRequestRepository extends JpaRepository<InfoRequest, String> {
	List<InfoRequest> findByConsultationIdOrderByCreatedAtAsc(String consultationId);
}
