package com.easython.contract;

import com.easython.common.PageInfo;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public final class ContractDtos {
	private ContractDtos() {}

	public record UploadResponse(String contractId, String status) {}

	public record FileView(String url, OffsetDateTime expiresAt, String hash) {}

	public record TermsView(String wageType, Integer wageAmount, String workStartTime, String workEndTime,
			Integer breakMinutesPerDay, List<String> workDays, Integer mealDeduction, Integer payday,
			LocalDate contractStart, LocalDate contractEnd, String employerName, String employerAddress, String ownerName) {}

	public record TranslationView(String language, String summary) {}

	public record PositionView(Integer page, Double x, Double y, Double width, Double height) {}

	public record ClauseView(String id, String originalText, String translatedText, PositionView position) {}

	public record ReviewView(String type, String level, String clauseId, String message) {}

	public record DetailResponse(String id, String status, String failReason, boolean confirmed, FileView file, TermsView terms,
			TranslationView translation, List<ClauseView> clauses, List<ReviewView> reviews, String disclaimer,
			OffsetDateTime createdAt) {}

	/** 보낸 필드만 수정. */
	public record TermsRequest(
			@Pattern(regexp = "HOURLY|MONTHLY") String wageType,
			@Min(0) Integer wageAmount,
			@Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$") String workStartTime,
			@Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$") String workEndTime,
			@Min(0) Integer breakMinutesPerDay,
			List<@Pattern(regexp = "MON|TUE|WED|THU|FRI|SAT|SUN") String> workDays,
			@Min(0) Integer mealDeduction,
			@Min(1) @jakarta.validation.constraints.Max(31) Integer payday,
			LocalDate contractStart, LocalDate contractEnd,
			String employerName, String employerAddress, String ownerName) {}

	public record TermsUpdateResponse(TermsView terms, List<ReviewView> reviews) {}

	public record ReanalyzeRequest(String language) {}

	public record ConfirmResponse(boolean confirmed, boolean businessAutoFilled) {}

	public record ListItem(String id, String status, boolean confirmed, String employerName, long checkCount, OffsetDateTime createdAt) {}

	public record ListResponse(List<ListItem> contracts, PageInfo pageInfo) {}
}
