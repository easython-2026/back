package com.easython.user;

import com.easython.common.ApiException;
import com.easython.common.ErrorCode;
import com.easython.common.Languages;
import com.easython.user.UserDtos.*;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private final UserRepository users;

	@Value("${app.gps.default-radius-meters}")
	private int defaultRadius;

	@Transactional(readOnly = true)
	public MeResponse me(String userId) {
		User u = get(userId);
		return new MeResponse(u.getId(), u.getEmail(), u.getName(), u.getLanguage(), u.getPayday(),
				u.isPaydayAlertEnabled(), u.hasWorkplace(),
				new ConsentsView(u.isConsentPrivacy(), u.isConsentLocation(), u.isConsentCamera()),
				u.getCreatedAt().atZone(KST).toOffsetDateTime());
	}

	@Transactional
	public UpdateMeResponse update(String userId, UpdateMeRequest r) {
		User u = get(userId);
		if (r.name() != null) {
			if (r.name().isBlank()) throw new ApiException(ErrorCode.VALIDATION_ERROR);
			u.setName(r.name().trim());
		}
		if (r.language() != null) {
			if (!Languages.isSupported(r.language())) throw new ApiException(ErrorCode.VALIDATION_ERROR);
			u.setLanguage(r.language());
		}
		return new UpdateMeResponse(u.getName(), u.getLanguage());
	}

	@Transactional
	public SettingsResponse settings(String userId, SettingsRequest r) {
		User u = get(userId);
		if (r.payday() != null) u.setPayday(r.payday());
		if (r.paydayAlertEnabled() != null) u.setPaydayAlertEnabled(r.paydayAlertEnabled());
		return new SettingsResponse(u.getPayday(), u.isPaydayAlertEnabled());
	}

	@Transactional(readOnly = true)
	public WorkplaceResponse workplace(String userId) {
		return toView(get(userId));
	}

	/** 보낸 블록(workplace/business)만 통째로 교체. 둘 다 없으면 400. */
	@Transactional
	public WorkplaceResponse putWorkplace(String userId, WorkplaceUpdateRequest r) {
		if (r.workplace() == null && r.business() == null) throw new ApiException(ErrorCode.VALIDATION_ERROR);
		User u = get(userId);
		if (r.workplace() != null) {
			var w = r.workplace();
			u.setWorkplaceName(w.name().trim());
			u.setWorkplaceAddress(w.address().trim());
			u.setWorkplaceLatitude(w.latitude());
			u.setWorkplaceLongitude(w.longitude());
			u.setWorkplaceRadiusMeters(w.radiusMeters() != null ? w.radiusMeters() : defaultRadius);
		}
		if (r.business() != null) {
			var b = r.business();
			u.setBusinessName(b.name().trim());
			u.setBusinessAddress(b.address().trim());
			u.setBusinessOwnerName(b.ownerName().trim());
			u.setBusinessFromContract(false); // 사용자가 직접 입력·수정한 값
		}
		return toView(u);
	}

	private WorkplaceResponse toView(User u) {
		var w = u.hasWorkplace()
				? new WorkplaceView(u.getWorkplaceName(), u.getWorkplaceAddress(), u.getWorkplaceLatitude(),
						u.getWorkplaceLongitude(), u.getWorkplaceRadiusMeters())
				: null;
		var b = u.hasBusiness()
				? new BusinessView(u.getBusinessName(), u.getBusinessAddress(), u.getBusinessOwnerName(),
						u.isBusinessFromContract())
				: null;
		return new WorkplaceResponse(w, b);
	}

	private User get(String userId) {
		return users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
	}
}
