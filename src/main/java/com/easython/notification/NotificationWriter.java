package com.easython.notification;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 별도 트랜잭션으로 알림 한 건 저장. 동시 요청이 같은 알림을 만들어 유니크 위반이 나도 호출 측 트랜잭션은 영향받지 않는다. */
@Component
class NotificationWriter {
	private final NotificationRepository repo;

	NotificationWriter(NotificationRepository repo) {
		this.repo = repo;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	void insert(Notification n) {
		repo.saveAndFlush(n);
	}
}
