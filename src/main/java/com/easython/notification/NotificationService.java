package com.easython.notification;

import com.easython.attendance.WorkRecordRepository;
import com.easython.common.ApiException;
import com.easython.common.ErrorCode;
import com.easython.common.PageInfo;
import com.easython.common.Times;
import com.easython.contract.Contract;
import com.easython.contract.ContractRepository;
import com.easython.payroll.PayStatementRepository;
import com.easython.user.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
@RequiredArgsConstructor
public class NotificationService {
	private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
	/** 퇴근 누락 알림은 이 기간 안의 기록만(오래된 건 수정 안내가 의미 없음) */
	private static final Duration MISSING_LOOKBACK = Duration.ofDays(60);

	public record View(String id, String type, String title, String body, boolean popup, Map<String, Object> payload, boolean read, OffsetDateTime createdAt) {}

	public record ListResponse(long unreadCount, List<View> notifications, PageInfo pageInfo) {}

	public record ReadResponse(String id, boolean read) {}

	private final NotificationRepository repo;
	private final NotificationWriter writer;
	private final UserRepository users;
	private final ContractRepository contracts;
	private final PayStatementRepository statements;
	private final WorkRecordRepository records;
	private final MessageSource messages;
	private final JsonMapper json;
	private final Clock clock;

	/** 다른 기능(비교, 상담 등)이 알림을 남길 때 사용. dedupKey 가 같으면 한 번만 생성. */
	public void add(String userId, String type, Map<String, Object> payload, String dedupKey) {
		if (repo.existsByUserIdAndDedupKey(userId, dedupKey)) return;
		Notification n = new Notification();
		n.setUserId(userId);
		n.setType(type);
		n.setPayloadJson(json.writeValueAsString(payload));
		n.setDedupKey(dedupKey);
		try {
			writer.insert(n);
		} catch (DataIntegrityViolationException e) {
			log.debug("알림 중복 생성 무시 key={}", dedupKey); // 동시 요청이 먼저 만들었음
		}
	}

	/**
	 * 앱 진입 시 호출. 월급날·기록 누락 알림은 이때 생성한다(조회 시점 생성).
	 * 트랜잭션을 걸지 않는다: 알림은 각각 별도 트랜잭션(NotificationWriter)으로 커밋되는데, 바깥에 트랜잭션이 있으면
	 * MySQL(REPEATABLE READ)에서는 바깥 스냅샷이 새 알림을 못 보기 때문. 생성이 끝난 뒤 조회한다.
	 */
	public ListResponse list(String userId, String language, boolean unreadOnly, boolean popupOnly, Integer page, Integer size) {
		generate(userId);
		var pageable = PageInfo.request(page, size);
		var p = popupOnly ? repo.findByUserIdAndTypeAndReadFalseOrderByCreatedAtDesc(userId, Notification.PAYDAY, pageable)
				: unreadOnly ? repo.findByUserIdAndReadFalseOrderByCreatedAtDesc(userId, pageable)
				: repo.findByUserIdOrderByCreatedAtDesc(userId, pageable);
		Locale locale = Locale.forLanguageTag(language);
		return new ListResponse(repo.countByUserIdAndReadFalse(userId), p.getContent().stream().map(n -> view(n, locale)).toList(), PageInfo.of(p));
	}

	/** 이미 읽은 알림도 200. 읽음 처리하면 팝업은 다시 노출되지 않는다. */
	@Transactional
	public ReadResponse read(String userId, String id) {
		Notification n = repo.findByIdAndUserId(id, userId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "error.notification.notfound"));
		n.setRead(true);
		return new ReadResponse(n.getId(), true);
	}

	// ---------------------------------------------------------------- 생성 규칙

	private void generate(String userId) {
		LocalDate today = LocalDate.now(clock);
		var u = users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));

		// 월급날: 지급일 이후인데 전월분 실수령액(확정 명세서·수령액)이 아직 없으면 1회 생성
		Integer payday = u.getPayday() != null ? u.getPayday()
				: contracts.findFirstByUserIdAndConfirmedTrueOrderByConfirmedAtDesc(userId).map(Contract::getPayday).orElse(null);
		if (payday != null && u.isPaydayAlertEnabled()) {
			YearMonth thisMonth = YearMonth.from(today);
			if (!today.isBefore(thisMonth.atDay(Math.min(payday, thisMonth.lengthOfMonth())))) {
				String target = thisMonth.minusMonths(1).toString();
				if (!statements.existsByUserIdAndMonthAndConfirmedTrue(userId, target)) {
					add(userId, Notification.PAYDAY, Map.of("month", target), "PAYDAY:" + target);
				}
			}
		}

		// 퇴근 기록 누락
		Instant now = clock.instant();
		for (var r : records.findByUserIdAndClockOutAtIsNullAndClockInAtBefore(userId, now.minus(com.easython.attendance.WorkRecord.MAX_SHIFT))) {
			if (r.getClockInAt().isAfter(now.minus(MISSING_LOOKBACK))) {
				Map<String, Object> payload = new LinkedHashMap<>();
				payload.put("date", r.getWorkDate().toString());
				payload.put("recordId", r.getId());
				add(userId, Notification.MISSING_RECORD, payload, "MISSING_RECORD:" + r.getId());
			}
		}
	}

	// ---------------------------------------------------------------- 표시

	@SuppressWarnings("unchecked")
	private View view(Notification n, Locale locale) {
		Map<String, Object> payload = json.readValue(n.getPayloadJson(), Map.class);
		Object arg = payload.getOrDefault("month", payload.getOrDefault("date", ""));
		if (payload.get("status") != null) arg = messages.getMessage("consult.status." + payload.get("status"), null, String.valueOf(payload.get("status")), locale);
		String title = messages.getMessage("notif." + n.getType() + ".title", null, n.getType(), locale);
		String body = messages.getMessage("notif." + n.getType() + ".body", new Object[] { String.valueOf(arg) }, "", locale);
		return new View(n.getId(), n.getType(), title, body, Notification.PAYDAY.equals(n.getType()) && !n.isRead(), payload, n.isRead(), Times.kst(n.getCreatedAt()));
	}
}
