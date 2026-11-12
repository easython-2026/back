package com.easython.consultation;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReferralRepository extends JpaRepository<Referral, String> {
	List<Referral> findByConsultationIdOrderByCreatedAtAsc(String consultationId);
}
