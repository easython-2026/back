package com.easython.auth;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PasswordResetCodeRepository extends JpaRepository<PasswordResetCode, String> {
	Optional<PasswordResetCode> findFirstByUserIdAndUsedFalseOrderByCreatedAtDesc(String userId);

	void deleteByUserId(String userId);
}
