package com.easython.payroll;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PayComparisonRepository extends JpaRepository<PayComparison, String> {
	Optional<PayComparison> findByIdAndUserId(String id, String userId);

	Optional<PayComparison> findFirstByUserIdAndMonthOrderByCreatedAtDesc(String userId, String month);

	void deleteByUserId(String userId);
}
