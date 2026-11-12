package com.easython.contract;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContractRepository extends JpaRepository<Contract, String> {
	Optional<Contract> findByIdAndUserId(String id, String userId);

	java.util.List<Contract> findByUserId(String userId);

	Page<Contract> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

	/** 급여 계산 기준: 확정된 계약서 중 최신 */
	Optional<Contract> findFirstByUserIdAndConfirmedTrueOrderByConfirmedAtDesc(String userId);
}
