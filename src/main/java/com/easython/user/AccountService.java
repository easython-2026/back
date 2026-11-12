package com.easython.user;

import com.easython.auth.PasswordResetCodeRepository;
import com.easython.auth.RefreshTokenRepository;
import com.easython.common.ApiException;
import com.easython.common.ErrorCode;
import com.easython.common.Times;
import com.easython.consultation.Agency;
import com.easython.consultation.AgencyRepository;
import com.easython.consultation.ConsultationRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 내 정보의 개인정보 관리: 동의 내역·선택 동의 변경·회원 탈퇴·저장 기록 삭제 요청. */
@Service
@RequiredArgsConstructor
public class AccountService {
	private static final Logger log = LoggerFactory.getLogger(AccountService.class);

	public record Share(String consultationId, String agencyName, OffsetDateTime sharedAt, OffsetDateTime revokedAt) {}

	public record ConsentsResponse(boolean privacy, boolean location, boolean camera, OffsetDateTime updatedAt, List<Share> shares) {}

	public record ConsentPatch(Boolean privacy, Boolean location, Boolean camera) {}

	public record ConsentPatchResponse(boolean location, boolean camera, OffsetDateTime updatedAt) {}

	public record DeleteMeResponse(OffsetDateTime deletedAt, String retentionNotice) {}

	public record DeletionResponse(String requestId, String status) {}

	private final UserRepository users;
	private final ConsultationRepository consultations;
	private final AgencyRepository agencies;
	private final RefreshTokenRepository refreshTokens;
	private final PasswordResetCodeRepository resetCodes;
	private final DataDeletionRequestRepository deletionRequests;
	private final DataPurgeService purge;
	private final PasswordEncoder encoder;
	private final MessageSource messages;

	@Transactional(readOnly = true)
	public ConsentsResponse consents(String userId) {
		User u = user(userId);
		Map<String, String> names = agencies.findAll().stream().collect(Collectors.toMap(Agency::getId, Agency::getName));
		var shares = consultations.findByWorkerId(userId).stream()
				.map(c -> new Share(c.getId(), names.get(c.getAgencyId()), Times.kst(c.getAgreedAt()), Times.kst(c.getRevokedAt()))).toList();
		return new ConsentsResponse(u.isConsentPrivacy(), u.isConsentLocation(), u.isConsentCamera(), Times.kst(updatedAt(u)), shares);
	}

	/** 선택 동의(위치·사진)만 변경. 필수 동의(privacy)는 바꿀 수 없고 철회는 회원 탈퇴로. */
	@Transactional
	public ConsentPatchResponse patchConsents(String userId, ConsentPatch r) {
		if (r.privacy() != null || (r.location() == null && r.camera() == null)) throw new ApiException(ErrorCode.VALIDATION_ERROR);
		User u = user(userId);
		if (r.location() != null) u.setConsentLocation(r.location());
		if (r.camera() != null) u.setConsentCamera(r.camera());
		u.setConsentUpdatedAt(Instant.now());
		return new ConsentPatchResponse(u.isConsentLocation(), u.isConsentCamera(), Times.kst(u.getConsentUpdatedAt()));
	}

	/**
	 * 회원 탈퇴: 자료(원본 파일 포함)를 모두 삭제하고 계정은 익명화한다(행은 남겨 기관 측 기록과의 참조가 깨지지 않게).
	 * 진행 중인 상담이 있으면 먼저 공유를 철회해야 한다.
	 */
	@Transactional
	public DeleteMeResponse deleteMe(String userId, String password, String language) {
		User u = user(userId);
		if (password == null || !encoder.matches(password, u.getPasswordHash())) throw new ApiException(ErrorCode.INVALID_CREDENTIALS, "error.password.wrong");
		if (!consultations.findByWorkerIdAndStatusNotAndRevokedAtIsNull(userId, com.easython.consultation.Consultation.CLOSED).isEmpty()) {
			throw new ApiException(ErrorCode.ACTIVE_CONSULTATION, "error.account.active");
		}
		purge.purge(userId, Set.of("ALL"));
		refreshTokens.findByUserIdAndRevokedFalse(userId).forEach(t -> t.setRevoked(true));
		resetCodes.deleteByUserId(userId);

		u.setEmail("deleted-" + u.getId() + "@deleted.invalid");
		u.setName("탈퇴한 사용자");
		u.setPasswordHash(encoder.encode(UUID.randomUUID().toString()));
		u.setPayday(null);
		u.setConsentLocation(false);
		u.setConsentCamera(false);
		u.setWorkplaceName(null);
		u.setWorkplaceAddress(null);
		u.setWorkplaceLatitude(null);
		u.setWorkplaceLongitude(null);
		u.setWorkplaceRadiusMeters(null);
		u.setBusinessName(null);
		u.setBusinessAddress(null);
		u.setBusinessOwnerName(null);
		u.setDeletedAt(Instant.now());
		return new DeleteMeResponse(Times.kst(u.getDeletedAt()), messages.getMessage("account.retention", null, Locale.forLanguageTag(language)));
	}

	/** 저장 기록 삭제 요청 접수(처리는 커밋 후 processDeletion 이 비동기로). */
	@Transactional
	public DeletionResponse requestDeletion(String userId, List<String> targets) {
		Set<String> t = new LinkedHashSet<>(targets);
		if (t.isEmpty() || !DataPurgeService.TARGETS.containsAll(t)) throw new ApiException(ErrorCode.VALIDATION_ERROR);
		if (purge.conflictsWithActiveConsultation(userId, t)) throw new ApiException(ErrorCode.ACTIVE_CONSULTATION);
		DataDeletionRequest r = new DataDeletionRequest();
		r.setUserId(userId);
		r.setTargets(String.join(",", t));
		deletionRequests.save(r);
		return new DeletionResponse(r.getId(), r.getStatus());
	}

	@Async
	@Transactional
	public void processDeletion(String requestId) {
		DataDeletionRequest r = deletionRequests.findById(requestId).orElse(null);
		if (r == null) return;
		try {
			purge.purge(r.getUserId(), Set.of(r.getTargets().split(",")));
			r.setStatus("DONE");
		} catch (Exception e) {
			log.error("저장 기록 삭제 실패 request={}", requestId, e);
			r.setStatus("FAILED");
		}
		r.setCompletedAt(Instant.now());
	}

	private static Instant updatedAt(User u) {
		return u.getConsentUpdatedAt() != null ? u.getConsentUpdatedAt() : u.getConsentAgreedAt();
	}

	private User user(String userId) {
		return users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
	}
}
