package com.easython.contract;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContractClauseRepository extends JpaRepository<ContractClause, String> {
	List<ContractClause> findByContractIdOrderByOrd(String contractId);

	void deleteByContractId(String contractId);
}
