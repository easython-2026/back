package com.easython.payroll;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@NoArgsConstructor
public class PayStatementItem {
	@Id
	@Column(length = 36)
	private String id = UUID.randomUUID().toString();

	@Column(nullable = false, length = 36)
	private String statementId;

	private int ord;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 24)
	private StatementItemType type;

	@Column(nullable = false)
	private String label;

	@Column(nullable = false)
	private int amount;
}
