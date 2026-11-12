package com.easython.contract;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@NoArgsConstructor
public class ContractClause {
	@Id
	@Column(length = 36)
	private String id = UUID.randomUUID().toString();

	@Column(nullable = false, length = 36)
	private String contractId;

	private int ord;

	@Column(columnDefinition = "TEXT")
	private String originalText;
	@Column(columnDefinition = "TEXT")
	private String translatedText;

	// 원본 이미지 내 위치(0~1 비율), 모르면 null
	private Integer posPage;
	private Double posX;
	private Double posY;
	private Double posWidth;
	private Double posHeight;
}
