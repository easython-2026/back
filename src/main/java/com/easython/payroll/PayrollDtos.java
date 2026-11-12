package com.easython.payroll;

import com.easython.contract.ContractDtos.FileView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public final class PayrollDtos {
	private PayrollDtos() {}

	// ---- 명세서 ----
	public record UploadResponse(String statementId, String status) {}

	public record ItemView(String type, String label, int amount) {}

	public record StatementDetail(String id, String status, String failReason, String source, String month, boolean confirmed,
			FileView file, List<ItemView> items, Integer grossAmount, Integer deductionTotal, Integer netAmount, Integer receivedAmount) {}

	public record ItemRequest(@NotNull StatementItemType type, @NotBlank String label, @NotNull @Min(0) Integer amount) {}

	/** 보낸 필드만 수정. items 는 전체 교체. */
	public record StatementPatchRequest(String month, @Valid List<ItemRequest> items, @Min(0) Integer netAmount, Boolean confirm) {}

	public record ManualRequest(@NotBlank String month, @NotNull @Min(0) Integer receivedAmount, LocalDate paidAt) {}

	public record ManualResponse(String statementId, String source, String status, boolean confirmed) {}

	// ---- 비교 ----
	public record ComparisonRequest(@NotBlank String month, @NotBlank String statementId) {}

	public record RuleView(String code, String label) {}

	public record AppliedConditions(String wageType, Integer wageAmount, int workedMinutes, int workDays, Integer breakApplied,
			List<RuleView> appliedRules, List<String> unsupportedRules) {}

	public record ComparisonItem(String type, String label, int expected, int actual, int difference, String basis) {}

	public record Uncertainty(String code, String message) {}

	public record Explanation(String language, String text) {}

	public record ComparisonDetail(String id, String month, String status, int expectedAmount, int actualAmount, int differenceAmount,
			AppliedConditions appliedConditions, List<ComparisonItem> items, List<Uncertainty> uncertainties, Explanation explanation,
			List<String> actions, String disclaimer, OffsetDateTime createdAt) {}

	public record ComparisonSummary(String id, String status, int expectedAmount, int actualAmount, int differenceAmount) {}

	public record MonthState(String month, String state, String statementId, LocalDate payday, ComparisonSummary comparison) {}
}
