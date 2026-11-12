package com.easython.payroll;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PayStatementRepository extends JpaRepository<PayStatement, String> {
	Optional<PayStatement> findByIdAndUserId(String id, String userId);

	boolean existsByUserIdAndMonthAndConfirmedTrue(String userId, String month);

	java.util.List<PayStatement> findByUserIdAndConfirmedTrue(String userId);

	/** 해당 월에 확정된 최신 명세서 */
	Optional<PayStatement> findFirstByUserIdAndMonthAndConfirmedTrueOrderByCreatedAtDesc(String userId, String month);

	java.util.List<PayStatement> findByUserId(String userId);
}
