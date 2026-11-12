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

public interface WorkRecordRepository extends JpaRepository<WorkRecord, String> {
	Optional<WorkRecord> findByIdAndUserId(String id, String userId);

	Optional<WorkRecord> findByUserIdAndWorkDate(String userId, LocalDate workDate);

	List<WorkRecord> findByUserIdAndWorkDateBetweenOrderByWorkDateAsc(String userId, LocalDate from, LocalDate to);

	/** 퇴근 전이고 MAX_SHIFT 안인(=현재 근무 중인) 기록 */
	Optional<WorkRecord> findFirstByUserIdAndClockOutAtIsNullAndClockInAtAfterOrderByClockInAtDesc(String userId, Instant after);

	boolean existsByUserIdAndWorkDateBetween(String userId, LocalDate from, LocalDate to);

	List<WorkRecord> findByUserId(String userId);

	/** 퇴근 기록 없이 cutoff 이전에 출근한 기록(퇴근 누락 후보) */
	List<WorkRecord> findByUserIdAndClockOutAtIsNullAndClockInAtBefore(String userId, Instant cutoff);
}
