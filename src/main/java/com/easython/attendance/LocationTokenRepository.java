package com.easython.attendance;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LocationTokenRepository extends JpaRepository<LocationToken, String> {
	/** 원자적으로 1회만 소모. 이미 쓰였으면 0. */
	@Modifying
	@Query("update LocationToken t set t.used = true where t.token = :token and t.used = false")
	int consume(@Param("token") String token);

	void deleteByUserId(String userId);
}
