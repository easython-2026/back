package com.easython.auth;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, String> {
	java.util.List<RefreshToken> findByUserIdAndRevokedFalse(String userId);
}
