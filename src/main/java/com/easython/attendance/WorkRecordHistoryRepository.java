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

public interface WorkRecordHistoryRepository extends JpaRepository<WorkRecordHistory, String> {
	List<WorkRecordHistory> findByRecordIdOrderByEditedAtDescFieldAsc(String recordId);

	List<WorkRecordHistory> findByRecordIdIn(Collection<String> recordIds);
}
