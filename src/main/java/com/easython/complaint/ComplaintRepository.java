package com.easython.complaint;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ComplaintRepository extends JpaRepository<Complaint, String> {
	Optional<Complaint> findByIdAndUserId(String id, String userId);

	Page<Complaint> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

	java.util.List<Complaint> findByUserId(String userId);
}
