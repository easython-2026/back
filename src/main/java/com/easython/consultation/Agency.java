package com.easython.consultation;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 상담기관(노동청·지원센터·시민단체). 데모에서는 사전 등록 데이터, 운영에서는 DB 에 실제 기관을 넣는다. */
@Entity
@Table(name = "agencies")
@Getter
@Setter
@NoArgsConstructor
public class Agency {
	@Id
	@Column(length = 36)
	private String id = UUID.randomUUID().toString();
	@Column(nullable = false)
	private String name;
	/** LABOR_OFFICE / SUPPORT_CENTER / NGO */
	@Column(nullable = false, length = 20)
	private String type;
	/** 지원 언어 코드 CSV */
	private String languages;
	private String phone;
	private String address;
}
