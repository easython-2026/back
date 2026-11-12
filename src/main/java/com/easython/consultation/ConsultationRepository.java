package com.easython.consultation;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConsultationRepository extends JpaRepository<Consultation, String> {
	Optional<Consultation> findByIdAndWorkerId(String id, String workerId);

	Page<Consultation> findByWorkerIdOrderByCreatedAtDesc(String workerId, Pageable pageable);

	Page<Consultation> findByWorkerIdAndStatusOrderByCreatedAtDesc(String workerId, String status, Pageable pageable);

	/** 같은 비교+기관으로 아직 끝나지 않았고 동의가 유지 중인 상담(중복 신청 방지) */
	boolean existsByWorkerIdAndComparisonIdAndAgencyIdAndStatusNotAndRevokedAtIsNull(String workerId, String comparisonId, String agencyId, String status);

	/** 기관의 사건 중 동의가 유지 중인 것 */
	List<Consultation> findByAgencyIdAndRevokedAtIsNull(String agencyId);

	/** 노동자의 진행 중(미종결·동의 유지) 상담 - 공유 중인 자료의 수정·삭제 제한 판단용 */
	List<Consultation> findByWorkerIdAndStatusNotAndRevokedAtIsNull(String workerId, String status);

	List<Consultation> findByWorkerId(String workerId);
}
