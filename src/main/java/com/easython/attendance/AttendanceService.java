package com.easython.attendance;

import com.easython.attendance.AttendanceDtos.*;
import com.easython.attendance.WorkRecordHistory.Field;
import com.easython.common.ApiException;
import com.easython.common.ErrorCode;
import com.easython.common.Geo;
import com.easython.common.Times;
import com.easython.storage.FileStorage;
import com.easython.user.User;
import com.easython.user.UserRepository;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** 출퇴근 쓰기 쪽: 위치 인증, 출근/퇴근, 휴게시간, 기록 수정, 수동 추가. */
@Service
@RequiredArgsConstructor
public class AttendanceService {
	private static final long MAX_PHOTO_BYTES = 5L * 1024 * 1024;
	private static final Duration TOKEN_TTL = Duration.ofMinutes(5);
	private static final Duration PHOTO_URL_TTL = Duration.ofMinutes(30);
	private static final Duration CLOCK_SKEW = Duration.ofSeconds(60);

	private final UserRepository users;
	private final WorkRecordRepository records;
	private final AttendanceEventRepository events;
	private final WorkRecordHistoryRepository histories;
	private final LocationTokenRepository tokens;
	private final FileStorage storage;
	private final Clock clock;
	private final com.easython.consultation.ConsultationShares shares;

	@Value("${app.gps.max-accuracy-meters}")
	private double maxAccuracy;

	// ---------------------------------------------------------------- 위치 인증

	@Transactional
	public LocationCheckResponse locationCheck(String userId, LocationCheckRequest r) {
		User u = user(userId);
		if (!u.isConsentLocation()) throw new ApiException(ErrorCode.LOCATION_CONSENT_REQUIRED);
		Instant now = now();
		ensureState(userId, r.type(), now);

		if (!u.hasWorkplace()) return fail("NO_WORKPLACE", null, null);
		int radius = u.getWorkplaceRadiusMeters();
		int distance = distance(u, r.latitude(), r.longitude());
		if (r.accuracyMeters() > maxAccuracy) return fail("LOW_ACCURACY", distance, radius);
		if (distance > radius) return fail("OUT_OF_RANGE", distance, radius);

		LocationToken t = new LocationToken();
		t.setToken("vt_" + UUID.randomUUID().toString().replace("-", ""));
		t.setUserId(userId);
		t.setType(r.type() == CheckType.CLOCK_IN ? LocationToken.Type.CLOCK_IN : LocationToken.Type.CLOCK_OUT);
		t.setExpiresAt(now.plus(TOKEN_TTL));
		tokens.save(t);
		return new LocationCheckResponse(true, null, distance, radius, t.getToken(), Times.kst(t.getExpiresAt()));
	}

	private static LocationCheckResponse fail(String reason, Integer distance, Integer radius) {
		return new LocationCheckResponse(false, reason, distance, radius, null, null);
	}

	// ---------------------------------------------------------------- 출근 / 퇴근

	@Transactional
	public ClockInResponse clockIn(String userId, String token, MultipartFile photo, double lat, double lng, double accuracy,
			String clientCapturedAt) {
		User u = user(userId);
		validateCapture(u, photo, lat, lng, accuracy);
		byte[] bytes = bytes(photo);
		String ext = imageExt(bytes);
		Instant now = now();
		consumeToken(userId, token, LocationToken.Type.CLOCK_IN, now);
		ensureState(userId, CheckType.CLOCK_IN, now);

		WorkRecord rec = new WorkRecord();
		rec.setUserId(userId);
		rec.setWorkDate(LocalDate.now(clock));
		rec.setSource(WorkRecord.Source.DEVICE);
		rec.setClockInAt(now);
		rec.setOriginalClockInAt(now);
		try {
			records.saveAndFlush(rec);
		} catch (DataIntegrityViolationException e) { // 동시 출근 경쟁
			throw new ApiException(ErrorCode.ALREADY_CLOCKED_IN);
		}
		AttendanceEvent ev = saveEvent(u, rec, AttendanceEvent.Type.IN, now, bytes, ext, lat, lng, accuracy, clientCapturedAt);
		var url = storage.signedUrl(ev.getPhotoKey(), PHOTO_URL_TTL);
		return new ClockInResponse(rec.getId(), Times.kst(now), "WORKING", new ClockPhoto(url.url(), ev.getPhotoHash()));
	}

	@Transactional
	public ClockOutResponse clockOut(String userId, String token, MultipartFile photo, double lat, double lng, double accuracy,
			String clientCapturedAt) {
		User u = user(userId);
		validateCapture(u, photo, lat, lng, accuracy);
		byte[] bytes = bytes(photo);
		String ext = imageExt(bytes);
		Instant now = now();
		consumeToken(userId, token, LocationToken.Type.CLOCK_OUT, now);
		WorkRecord rec = ensureState(userId, CheckType.CLOCK_OUT, now);

		rec.setClockOutAt(now);
		rec.setOriginalClockOutAt(now);
		saveEvent(u, rec, AttendanceEvent.Type.OUT, now, bytes, ext, lat, lng, accuracy, clientCapturedAt);
		return new ClockOutResponse(rec.getId(), Times.kst(now), "FINISHED", rec.workedMinutes(now), rec.getBreakMinutes() == null);
	}

	private void validateCapture(User u, MultipartFile photo, double lat, double lng, double accuracy) {
		if (photo == null || photo.isEmpty() || lat < -90 || lat > 90 || lng < -180 || lng > 180 || accuracy < 0) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR);
		}
		if (!u.isConsentCamera()) throw new ApiException(ErrorCode.CAMERA_CONSENT_REQUIRED);
		if (photo.getSize() > MAX_PHOTO_BYTES) throw new ApiException(ErrorCode.FILE_TOO_LARGE);
	}

	/** 토큰 검증 + 원자적 1회 소모. 트랜잭션이 롤백되면(후속 검증 실패) 소모도 취소된다. */
	private void consumeToken(String userId, String token, LocationToken.Type type, Instant now) {
		LocationToken t = tokens.findById(token == null ? "" : token).orElseThrow(() -> new ApiException(ErrorCode.GPS_NOT_VERIFIED));
		if (!t.getUserId().equals(userId) || t.getType() != type || t.isUsed() || t.getExpiresAt().isBefore(now)
				|| tokens.consume(token) != 1) {
			throw new ApiException(ErrorCode.GPS_NOT_VERIFIED);
		}
	}

	private AttendanceEvent saveEvent(User u, WorkRecord rec, AttendanceEvent.Type type, Instant now, byte[] bytes, String ext,
			double lat, double lng, double accuracy, String clientCapturedAt) {
		String key = "att_" + rec.getId() + "_" + (type == AttendanceEvent.Type.IN ? "in" : "out") + "." + ext;
		storage.save(key, bytes);
		int dist = distance(u, lat, lng);
		AttendanceEvent ev = new AttendanceEvent();
		ev.setRecordId(rec.getId());
		ev.setType(type);
		ev.setServerAt(now);
		ev.setClientCapturedAt(clientCapturedAt);
		ev.setPhotoKey(key);
		ev.setPhotoHash(FileStorage.sha256(bytes));
		ev.setLatitude(lat);
		ev.setLongitude(lng);
		ev.setAccuracyMeters(accuracy);
		ev.setDistanceMeters(dist);
		ev.setVerified(u.hasWorkplace() && dist <= u.getWorkplaceRadiusMeters());
		return events.save(ev);
	}

	/**
	 * 출근/퇴근이 가능한 상태인지 확인(아니면 409). 퇴근이면 대상(현재 근무 중) 기록을 돌려준다.
	 * 퇴근을 안 찍고 MAX_SHIFT(24h)를 넘긴 기록은 '근무 중'이 아니라 '퇴근 누락'으로 보고 새 출근을 막지 않는다.
	 */
	private WorkRecord ensureState(String userId, CheckType type, Instant now) {
		var open = records.findFirstByUserIdAndClockOutAtIsNullAndClockInAtAfterOrderByClockInAtDesc(userId, now.minus(WorkRecord.MAX_SHIFT));
		LocalDate today = LocalDate.now(clock);
		if (type == CheckType.CLOCK_IN) {
			if (open.isPresent()) throw new ApiException(ErrorCode.ALREADY_CLOCKED_IN);
			if (records.findByUserIdAndWorkDate(userId, today).isPresent()) throw new ApiException(ErrorCode.DUPLICATED_RECORD);
			return null;
		}
		return open.orElseThrow(() -> new ApiException(
				records.findByUserIdAndWorkDate(userId, today).filter(r -> r.getClockOutAt() != null).isPresent()
						? ErrorCode.ALREADY_CLOCKED_OUT : ErrorCode.NOT_CLOCKED_IN));
	}

	// ---------------------------------------------------------------- 휴게시간 / 수정 / 수동 추가

	@Transactional
	public BreakResponse putBreak(String userId, String recordId, int breakMinutes) {
		WorkRecord rec = record(userId, recordId);
		if (rec.getClockOutAt() == null) throw new ApiException(ErrorCode.CONFLICT, "error.break.beforeclockout");
		if (breakMinutes > Duration.between(rec.getClockInAt(), rec.getClockOutAt()).toMinutes()) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR);
		}
		Integer before = rec.getBreakMinutes();
		if (before == null || before != breakMinutes) {
			histories.save(new WorkRecordHistory(rec.getId(), now(), Field.BREAK, str(before), String.valueOf(breakMinutes),
					before == null ? "휴게시간 입력" : "휴게시간 변경"));
			rec.setBreakMinutes(breakMinutes);
		}
		return new BreakResponse(rec.getId(), breakMinutes, rec.workedMinutes(now()));
	}

	/** 보낸 필드만 수정. 원본(original*)은 유지하고 변경 필드마다 이력을 추가한다. */
	@Transactional
	public PatchResponse patch(String userId, String recordId, PatchRequest r) {
		WorkRecord rec = record(userId, recordId);
		if (shares.sharesAttendance(userId, java.time.YearMonth.from(rec.getWorkDate()))) {
			throw new ApiException(ErrorCode.CONFLICT, "error.record.shared");
		}
		Instant now = now();
		Instant in = r.clockInAt() != null ? sec(r.clockInAt().toInstant()) : rec.getClockInAt();
		Instant out = r.clockOutAt() != null ? sec(r.clockOutAt().toInstant()) : rec.getClockOutAt();
		Integer br = r.breakMinutes() != null ? r.breakMinutes() : rec.getBreakMinutes();
		validateTimes(in, out, br, now);

		String reason = r.reason().trim();
		List<WorkRecordHistory> changes = new ArrayList<>();
		if (!in.equals(rec.getClockInAt())) {
			changes.add(new WorkRecordHistory(rec.getId(), now, Field.CLOCK_IN, Times.iso(rec.getClockInAt()), Times.iso(in), reason));
		}
		if (out != null && !out.equals(rec.getClockOutAt())) {
			changes.add(new WorkRecordHistory(rec.getId(), now, Field.CLOCK_OUT, Times.iso(rec.getClockOutAt()), Times.iso(out), reason));
		}
		if (br != null && !br.equals(rec.getBreakMinutes())) {
			changes.add(new WorkRecordHistory(rec.getId(), now, Field.BREAK, str(rec.getBreakMinutes()), String.valueOf(br), reason));
		}
		if (changes.isEmpty()) throw new ApiException(ErrorCode.VALIDATION_ERROR); // 바뀐 값이 없음
		histories.saveAll(changes);
		rec.setClockInAt(in);
		rec.setClockOutAt(out);
		rec.setBreakMinutes(br);
		return new PatchResponse(rec.getId(), rec.workedMinutes(now), historyViews(rec.getId()));
	}

	/** 기록 못 한 날짜를 사후 입력. GPS·사진 없이 source=MANUAL 로 저장. */
	@Transactional
	public ManualResponse addManual(String userId, ManualRequest r) {
		Instant now = now();
		Instant in = sec(r.clockInAt().toInstant());
		Instant out = sec(r.clockOutAt().toInstant());
		if (r.workDate().isAfter(LocalDate.now(clock))) throw new ApiException(ErrorCode.VALIDATION_ERROR);
		validateTimes(in, out, r.breakMinutes(), now);
		if (records.findByUserIdAndWorkDate(userId, r.workDate()).isPresent()) throw new ApiException(ErrorCode.DUPLICATED_RECORD);

		WorkRecord rec = new WorkRecord();
		rec.setUserId(userId);
		rec.setWorkDate(r.workDate());
		rec.setSource(WorkRecord.Source.MANUAL);
		rec.setClockInAt(in);
		rec.setClockOutAt(out);
		rec.setBreakMinutes(r.breakMinutes());
		rec.setOriginalClockInAt(in);
		rec.setOriginalClockOutAt(out);
		rec.setOriginalBreakMinutes(r.breakMinutes());
		rec.setManualReason(r.reason().trim());
		try {
			records.saveAndFlush(rec);
		} catch (DataIntegrityViolationException e) {
			throw new ApiException(ErrorCode.DUPLICATED_RECORD);
		}
		return new ManualResponse(rec.getId(), "MANUAL", rec.workedMinutes(now), Times.kst(rec.getCreatedAt()));
	}

	/** 시간 순서·미래 시각·휴게시간(총 근무시간 이하) 검증. 위반이면 400. */
	private void validateTimes(Instant in, Instant out, Integer br, Instant now) {
		Instant limit = now.plus(CLOCK_SKEW);
		if (in.isAfter(limit)) throw new ApiException(ErrorCode.VALIDATION_ERROR);
		if (out != null) {
			if (out.isAfter(limit) || !out.isAfter(in)) throw new ApiException(ErrorCode.VALIDATION_ERROR);
			if (br != null && br > Duration.between(in, out).toMinutes()) throw new ApiException(ErrorCode.VALIDATION_ERROR);
		}
	}

	// ---------------------------------------------------------------- helpers

	List<HistoryView> historyViews(String recordId) {
		return histories.findByRecordIdOrderByEditedAtDescFieldAsc(recordId).stream()
				.map(h -> new HistoryView(h.getId(), Times.kst(h.getEditedAt()), h.getField().name(), h.getBefore(), h.getAfter(), h.getReason()))
				.toList();
	}

	private int distance(User u, double lat, double lng) {
		if (!u.hasWorkplace()) return 0;
		return (int) Math.round(Geo.distanceMeters(lat, lng, u.getWorkplaceLatitude(), u.getWorkplaceLongitude()));
	}

	private WorkRecord record(String userId, String recordId) {
		return records.findByIdAndUserId(recordId, userId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "error.record.notfound"));
	}

	private User user(String userId) {
		return users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
	}

	private Instant now() {
		return clock.instant().truncatedTo(ChronoUnit.SECONDS);
	}

	private static Instant sec(Instant i) {
		return i.truncatedTo(ChronoUnit.SECONDS);
	}

	private static String str(Integer i) {
		return i == null ? null : i.toString();
	}

	private static byte[] bytes(MultipartFile f) {
		try {
			return f.getBytes();
		} catch (IOException e) {
			throw new IllegalStateException("upload read failed", e);
		}
	}

	/** 확장자가 아니라 파일 내용(매직 바이트)으로 jpg/png만 허용. */
	private static String imageExt(byte[] b) {
		if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "jpg";
		if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "png";
		throw new ApiException(ErrorCode.UNSUPPORTED_FILE_TYPE);
	}
}
