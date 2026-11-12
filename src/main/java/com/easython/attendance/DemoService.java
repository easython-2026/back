package com.easython.attendance;

import com.easython.attendance.AttendanceDtos.SeedRequest;
import com.easython.attendance.AttendanceDtos.SeedResponse;
import com.easython.common.ApiException;
import com.easython.common.ErrorCode;
import com.easython.ai.MockAiClient;
import com.easython.common.Times;
import com.easython.contract.Contract;
import com.easython.contract.ContractClause;
import com.easython.contract.ContractClauseRepository;
import com.easython.contract.ContractRepository;
import com.easython.payroll.PayStatement;
import com.easython.payroll.PayStatementItem;
import com.easython.payroll.PayStatementItemRepository;
import com.easython.payroll.PayStatementRepository;
import com.easython.payroll.StatementItemType;
import com.easython.user.User;
import com.easython.user.UserRepository;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [데모] 시연 계정에 1개월치 근무기록을 채운다. 하루 약 10시간 근무(휴게 미입력), 일요일 휴무.
 * 샘플 계약서는 함께 생성, 급여명세서 샘플은 급여 기능이 생기면 추가한다.
 */
@Service
@RequiredArgsConstructor
public class DemoService {
	private final UserRepository users;
	private final WorkRecordRepository records;
	private final AttendanceEventRepository events;
	private final ContractRepository contracts;
	private final ContractClauseRepository contractClauses;
	private final PayStatementRepository payStatements;
	private final PayStatementItemRepository payStatementItems;
	private final Clock clock;

	@Value("${app.payroll.min-wage-hourly}")
	private int minWage;

	@Value("${app.demo.enabled}")
	private boolean enabled;

	@Transactional
	public SeedResponse seed(String userId, SeedRequest r) {
		if (!enabled) throw new ApiException(ErrorCode.FORBIDDEN);
		YearMonth ym;
		try {
			ym = r == null || r.month() == null ? YearMonth.now(clock).minusMonths(1) : YearMonth.parse(r.month());
		} catch (Exception e) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR);
		}
		if (records.existsByUserIdAndWorkDateBetween(userId, ym.atDay(1), ym.atEndOfMonth())) {
			throw new ApiException(ErrorCode.DUPLICATED_RECORD, "error.demo.duplicated");
		}
		User u = users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
		if (!u.hasWorkplace()) { // GPS 증거에 쓸 샘플 근무장소·사업장
			u.setWorkplaceName("OO농장");
			u.setWorkplaceAddress("경기도 포천시 샘플로 1");
			u.setWorkplaceLatitude(37.9);
			u.setWorkplaceLongitude(127.2);
			u.setWorkplaceRadiusMeters(100);
			u.setBusinessName("OO농장");
			u.setBusinessAddress("경기도 포천시 샘플로 1");
			u.setBusinessOwnerName("김OO");
			u.setBusinessFromContract(false);
		}

		List<WorkRecord> recs = new ArrayList<>();
		List<AttendanceEvent> evs = new ArrayList<>();
		for (LocalDate d = ym.atDay(1); !d.isAfter(ym.atEndOfMonth()); d = d.plusDays(1)) {
			if (d.getDayOfWeek() == DayOfWeek.SUNDAY) continue;
			var in = d.atTime(LocalTime.of(8, 0, 0)).plusMinutes(d.getDayOfMonth() * 7 % 10).atZone(Times.KST).toInstant();
			var out = d.atTime(LocalTime.of(18, 0, 0)).plusMinutes(d.getDayOfMonth() * 3 % 10).atZone(Times.KST).toInstant();
			WorkRecord rec = new WorkRecord();
			rec.setUserId(userId);
			rec.setWorkDate(d);
			rec.setSource(WorkRecord.Source.DEVICE);
			rec.setClockInAt(in);
			rec.setClockOutAt(out);
			rec.setOriginalClockInAt(in);
			rec.setOriginalClockOutAt(out);
			recs.add(rec);
			evs.add(event(u, rec, AttendanceEvent.Type.IN, in));
			evs.add(event(u, rec, AttendanceEvent.Type.OUT, out));
		}
		records.saveAll(recs);
		events.saveAll(evs);
		String contractId = r == null || !Boolean.FALSE.equals(r.withContract()) ? seedContract(u, ym) : null;
		String statementId = r == null || !Boolean.FALSE.equals(r.withStatement()) ? seedStatement(u, ym) : null;
		return new SeedResponse(ym.toString(), recs.size(), contractId, statementId);
	}

	/** 샘플 계약서(숙식비 35만원 공제, 휴게시간 누락). 파일 없이 분석 완료·확정 상태로 만든다. */
	private String seedContract(User u, YearMonth ym) {
		var ex = MockAiClient.sample(u.getLanguage(), ym.atDay(1), minWage);
		var t = ex.terms();
		Contract c = new Contract();
		c.setUserId(u.getId());
		c.setLanguage(u.getLanguage());
		c.setStatus(Contract.Status.DONE);
		c.setConfirmed(true);
		c.setConfirmedAt(java.time.Instant.now());
		c.setWageType(t.wageType());
		c.setWageAmount(t.wageAmount());
		c.setWorkStartTime(t.workStartTime());
		c.setWorkEndTime(t.workEndTime());
		c.setBreakMinutesPerDay(t.breakMinutesPerDay());
		c.setWorkDays(String.join(",", t.workDays()));
		c.setMealDeduction(t.mealDeduction());
		c.setPayday(t.payday());
		c.setContractStart(LocalDate.parse(t.contractStart()));
		c.setContractEnd(LocalDate.parse(t.contractEnd()));
		c.setEmployerName(u.getBusinessName());
		c.setEmployerAddress(u.getBusinessAddress());
		c.setOwnerName(u.getBusinessOwnerName());
		c.setTranslationSummary(ex.summary());
		contracts.save(c);
		var rows = new ArrayList<ContractClause>();
		for (int i = 0; i < ex.clauses().size(); i++) {
			var cl = ex.clauses().get(i);
			ContractClause row = new ContractClause();
			row.setContractId(c.getId());
			row.setOrd(i);
			row.setOriginalText(cl.originalText());
			row.setTranslatedText(cl.translatedText());
			row.setPosPage(cl.position().page());
			row.setPosX(cl.position().x());
			row.setPosY(cl.position().y());
			row.setPosWidth(cl.position().width());
			row.setPosHeight(cl.position().height());
			rows.add(row);
		}
		contractClauses.saveAll(rows);
		return c.getId();
	}

	/** 샘플 급여명세서: 260시간 근무에 비해 적게 지급(기본급 190만원, 숙식비 35만원 공제 → 실지급 155만원). 확정 상태. */
	private String seedStatement(User u, YearMonth ym) {
		var ex = MockAiClient.sampleStatement(ym.toString());
		PayStatement s = new PayStatement();
		s.setUserId(u.getId());
		s.setStatus(PayStatement.Status.DONE);
		s.setConfirmed(true);
		s.setMonth(ym.toString());
		s.setGrossAmount(ex.grossAmount());
		s.setDeductionTotal(ex.deductionTotal());
		s.setNetAmount(ex.netAmount());
		payStatements.save(s);
		var rows = new ArrayList<PayStatementItem>();
		for (int i = 0; i < ex.items().size(); i++) {
			var it = ex.items().get(i);
			PayStatementItem row = new PayStatementItem();
			row.setStatementId(s.getId());
			row.setOrd(i);
			row.setType(StatementItemType.valueOf(it.type()));
			row.setLabel(it.label());
			row.setAmount(it.amount());
			rows.add(row);
		}
		payStatementItems.saveAll(rows);
		return s.getId();
	}

	/** 사진 없는 GPS 증거(근무장소 근처 좌표). */
	private AttendanceEvent event(User u, WorkRecord rec, AttendanceEvent.Type type, java.time.Instant at) {
		AttendanceEvent e = new AttendanceEvent();
		e.setRecordId(rec.getId());
		e.setType(type);
		e.setServerAt(at);
		e.setLatitude(u.getWorkplaceLatitude() + 0.0003);
		e.setLongitude(u.getWorkplaceLongitude() + 0.0002);
		e.setAccuracyMeters(18.5);
		e.setDistanceMeters(40);
		e.setVerified(true);
		return e;
	}
}
