package com.easython.consultation;

import com.easython.user.Role;
import com.easython.user.User;
import com.easython.user.UserRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 데모용 상담기관·담당자 계정 시드(DEMO_ENABLED=true 일 때만, 비어 있을 때 한 번).
 * 기관명·전화번호는 실제 기관이 아닌 표시용 자리표시자. 운영에서는 DEMO_ENABLED=false 로 끄고 실제 기관·담당자를 DB 에 등록한다.
 */
@Component
@ConditionalOnProperty(name = "app.demo.enabled", havingValue = "true")
@RequiredArgsConstructor
public class DemoDataSeeder implements ApplicationRunner {
	private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

	private final AgencyRepository agencies;
	private final UserRepository users;
	private final PasswordEncoder encoder;

	@Value("${app.demo.staff-password}")
	private String staffPassword;

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		if (agencies.count() > 0) return;
		Agency center = agency("OO이주민지원센터(데모)", "SUPPORT_CENTER", "ko,vi,en", "031-000-0000", "경기도 포천시 샘플로 10");
		agency("OO지방고용노동청(데모)", "LABOR_OFFICE", "ko,en", "031-000-0001", "경기도 의정부시 샘플로 20");
		agency("OO이주노동자 인권단체(데모)", "NGO", "ko,vi", "02-000-0002", "서울특별시 샘플로 30");
		staff("staff@agency.org", "박담당", center, "ko,vi");
		staff("staff2@agency.org", "이담당", center, "ko,en");
		log.warn("데모 상담기관·담당자 계정을 생성했습니다(staff@agency.org). 배포 환경에서는 DEMO_ENABLED=false 로 끄세요.");
	}

	private Agency agency(String name, String type, String languages, String phone, String address) {
		Agency a = new Agency();
		a.setName(name);
		a.setType(type);
		a.setLanguages(languages);
		a.setPhone(phone);
		a.setAddress(address);
		return agencies.save(a);
	}

	private void staff(String email, String name, Agency agency, String languages) {
		if (users.existsByEmail(email)) return;
		User u = new User();
		u.setEmail(email);
		u.setPasswordHash(encoder.encode(staffPassword));
		u.setName(name);
		u.setLanguage("ko");
		u.setRole(Role.ADMIN);
		u.setAgencyId(agency.getId());
		u.setStaffLanguages(languages);
		u.setConsentPrivacy(true);
		u.setConsentAgreedAt(Instant.now());
		users.save(u);
	}
}
