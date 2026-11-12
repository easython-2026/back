package com.easython.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class User {
	@Id
	@Column(length = 36)
	private String id = UUID.randomUUID().toString();

	@Column(nullable = false, unique = true)
	private String email;

	@Column(nullable = false)
	private String passwordHash;

	@Column(nullable = false, length = 100)
	private String name;

	@Column(nullable = false, length = 8)
	private String language;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private Role role = Role.WORKER;

	/** 월급날(1~31), 미설정 null */
	private Integer payday;
	private boolean paydayAlertEnabled = true;

	private boolean consentPrivacy;
	private boolean consentLocation;
	private boolean consentCamera;
	private Instant consentAgreedAt;
	/** 선택 동의를 마지막으로 바꾼 시각(없으면 가입 동의 시각) */
	private Instant consentUpdatedAt;
	/** 탈퇴(익명화) 시각. 값이 있으면 로그인·토큰 사용 불가 */
	private Instant deletedAt;

	// 근무장소 (GPS 인증 기준). 미등록이면 workplaceName == null
	private String workplaceName;
	private String workplaceAddress;
	private Double workplaceLatitude;
	private Double workplaceLongitude;
	private Integer workplaceRadiusMeters;

	// 사업장 (진정서 피진정인 정보)
	private String businessName;
	private String businessAddress;
	private String businessOwnerName;
	private boolean businessFromContract;

	/** 상담기관 담당자(role=ADMIN)일 때 소속 기관과 응대 가능 언어(CSV) */
	@Column(length = 36)
	private String agencyId;
	private String staffLanguages;

	@Column(nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	public boolean hasWorkplace() {
		return workplaceName != null;
	}

	public boolean hasBusiness() {
		return businessName != null;
	}
}
