package com.easython.notification;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, String> {
	Optional<Notification> findByIdAndUserId(String id, String userId);

	boolean existsByUserIdAndDedupKey(String userId, String dedupKey);

	long countByUserIdAndReadFalse(String userId);

	Page<Notification> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

	Page<Notification> findByUserIdAndReadFalseOrderByCreatedAtDesc(String userId, Pageable pageable);

	Page<Notification> findByUserIdAndTypeAndReadFalseOrderByCreatedAtDesc(String userId, String type, Pageable pageable);

	void deleteByUserId(String userId);
}
