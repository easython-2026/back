package com.easython.consultation;

import java.time.YearMonth;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 진행 중(미종결·동의 유지)인 상담에 공유된 자료인지 판단. 계약서 삭제·근무기록 수정 제한에 쓴다.
 * 다른 도메인이 가볍게 의존할 수 있도록 리포지토리 하나만 쓴다.
 */
@Component
@RequiredArgsConstructor
public class ConsultationShares {
	private final ConsultationRepository consultations;

	@Transactional(readOnly = true)
	public boolean sharesContract(String workerId, String contractId) {
		String evId = "CONTRACT-" + contractId;
		return consultations.findByWorkerIdAndStatusNotAndRevokedAtIsNull(workerId, Consultation.CLOSED).stream()
				.anyMatch(c -> (c.scope().contains("CONTRACT") && contractId.equals(c.getContractId()))
						|| c.evidenceIdList().contains(evId) || c.extraEvidenceIdList().contains(evId));
	}

	@Transactional(readOnly = true)
	public boolean sharesAttendance(String workerId, YearMonth month) {
		return consultations.findByWorkerIdAndStatusNotAndRevokedAtIsNull(workerId, Consultation.CLOSED).stream()
				.anyMatch(c -> c.scope().contains("ATTENDANCE") && month.toString().equals(c.getMonth()));
	}
}
