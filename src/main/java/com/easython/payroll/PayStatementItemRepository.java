package com.easython.payroll;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PayStatementItemRepository extends JpaRepository<PayStatementItem, String> {
	List<PayStatementItem> findByStatementIdOrderByOrd(String statementId);

	void deleteByStatementId(String statementId);
}
