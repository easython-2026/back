package com.easython.user;

import com.easython.attendance.AttendanceEvent;
import com.easython.attendance.AttendanceEventRepository;
import com.easython.attendance.LocationTokenRepository;
import com.easython.attendance.WorkRecord;
import com.easython.attendance.WorkRecordHistoryRepository;
import com.easython.attendance.WorkRecordRepository;
import com.easython.complaint.ComplaintRepository;
import com.easython.consultation.Consultation;
import com.easython.consultation.ConsultationRepository;
import com.easython.contract.ContractClauseRepository;
import com.easython.contract.ContractRepository;
import com.easython.notification.NotificationRepository;
import com.easython.payroll.PayComparisonRepository;
import com.easython.payroll.PayStatementItemRepository;
import com.easython.payroll.PayStatementRepository;
import com.easython.storage.FileStorage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 사용자 데이터 삭제(원본 파일 포함). 회원 탈퇴와 저장 기록 삭제 요청이 함께 쓴다. */
@Service
@RequiredArgsConstructor
public class DataPurgeService {
	public static final Set<String> TARGETS = Set.of("CONTRACTS", "ATTENDANCE", "PAYROLL", "COMPLAINTS", "ALL");
	private static final Set<String> ATTENDANCE_EVIDENCE = Set.of("ATTENDANCE_PHOTO", "GPS_LOG", "TIMESTAMP", "MANUAL_RECORD");

	private final ContractRepository contracts;
	private final ContractClauseRepository clauses;
	private final WorkRecordRepository records;
	private final AttendanceEventRepository events;
	private final WorkRecordHistoryRepository histories;
	private final LocationTokenRepository tokens;
	private final PayStatementRepository statements;
	private final PayStatementItemRepository statementItems;
	private final PayComparisonRepository comparisons;
	private final ComplaintRepository complaints;
	private final NotificationRepository notifications;
	private final ConsultationRepository consultations;
	private final FileStorage storage;

	/** 진행 중(미종결·동의 유지) 상담에 공유된 자료가 대상에 포함되는지. */
	@Transactional(readOnly = true)
	public boolean conflictsWithActiveConsultation(String userId, Set<String> targets) {
		for (Consultation c : consultations.findByWorkerIdAndStatusNotAndRevokedAtIsNull(userId, Consultation.CLOSED)) {
			if (targets.contains("ALL") || targets.contains("PAYROLL")) return true; // 상담 요약이 급여 비교에 기대고 있음
			List<String> ids = new ArrayList<>(c.evidenceIdList());
			ids.addAll(c.extraEvidenceIdList());
			if (targets.contains("CONTRACTS") && (c.scope().contains("CONTRACT") || ids.stream().anyMatch(i -> i.startsWith("CONTRACT-")))) return true;
			if (targets.contains("ATTENDANCE") && (c.scope().contains("ATTENDANCE")
					|| ids.stream().anyMatch(i -> ATTENDANCE_EVIDENCE.contains(i.substring(0, Math.max(0, i.indexOf('-'))))))) return true;
			if (targets.contains("COMPLAINTS") && c.scope().contains("COMPLAINT_DRAFT")) return true;
		}
		return false;
	}

	@Transactional
	public void purge(String userId, Set<String> targets) {
		boolean all = targets.contains("ALL");
		if (all || targets.contains("CONTRACTS")) {
			contracts.findByUserId(userId).forEach(c -> {
				clauses.deleteByContractId(c.getId());
				if (c.getFileKey() != null) storage.delete(c.getFileKey());
				contracts.delete(c);
			});
		}
		if (all || targets.contains("ATTENDANCE")) {
			List<WorkRecord> recs = records.findByUserId(userId);
			List<String> ids = recs.stream().map(WorkRecord::getId).toList();
			if (!ids.isEmpty()) {
				List<AttendanceEvent> evs = events.findByRecordIdIn(ids);
				evs.stream().filter(e -> e.getPhotoKey() != null).forEach(e -> storage.delete(e.getPhotoKey()));
				events.deleteAll(evs);
				histories.deleteAll(histories.findByRecordIdIn(ids));
				records.deleteAll(recs);
			}
			tokens.deleteByUserId(userId);
		}
		if (all || targets.contains("PAYROLL")) {
			statements.findByUserId(userId).forEach(s -> {
				statementItems.deleteByStatementId(s.getId());
				if (s.getFileKey() != null) storage.delete(s.getFileKey());
				statements.delete(s);
			});
			comparisons.deleteByUserId(userId);
		}
		if (all || targets.contains("COMPLAINTS")) complaints.deleteAll(complaints.findByUserId(userId));
		if (all) notifications.deleteByUserId(userId);
		if (all || targets.contains("PAYROLL")) {
			// 근거 데이터가 사라진 상담은 기관 화면에서도 더 이상 열람되지 않도록 공유 종료 처리
			consultations.findByWorkerId(userId).stream().filter(c -> c.getRevokedAt() == null).forEach(c -> c.setRevokedAt(Instant.now()));
		}
	}
}
