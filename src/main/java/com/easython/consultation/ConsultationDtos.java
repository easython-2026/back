package com.easython.consultation;

import com.easython.common.PageInfo;
import com.easython.contract.ContractDtos.ReviewView;
import com.easython.contract.ContractDtos.TermsView;
import com.easython.payroll.PayrollDtos.AppliedConditions;
import com.easython.payroll.PayrollDtos.ComparisonItem;
import com.easython.payroll.PayrollDtos.Uncertainty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public final class ConsultationDtos {
	private ConsultationDtos() {}

	// ---- 노동자 ----
	public record AgencyView(String id, String name, String type, List<String> languages, String phone, String address) {}

	public record AgenciesResponse(List<AgencyView> agencies) {}

	public record ConsentRequest(@NotEmpty List<String> sharedScope, List<String> evidenceIds, @NotNull OffsetDateTime agreedAt) {}

	public record CreateRequest(@NotBlank String agencyId, @NotBlank String comparisonId, String complaintId,
			@NotNull @Valid ConsentRequest consent, @Size(max = 2000) String memo) {}

	public record CreateResponse(String consultationId, String status, String summaryStatus) {}

	public record AgencyBrief(String id, String name) {}

	public record SummaryView(String ko, String translated) {}

	public record ConsentView(List<String> sharedScope, OffsetDateTime agreedAt, OffsetDateTime revokedAt) {}

	public record TimelineView(String status, OffsetDateTime at, String note) {}

	public record InfoRequestView(String id, String message, List<String> requestedItems, OffsetDateTime createdAt) {}

	public record ReferralView(String type, String organization, String note) {}

	public record Detail(String id, AgencyBrief agency, String status, String summaryStatus, SummaryView summary, ConsentView consent,
			List<TimelineView> timeline, List<InfoRequestView> infoRequests, List<ReferralView> referrals, String resultSummary,
			OffsetDateTime createdAt, OffsetDateTime updatedAt) {}

	public record ListItem(String id, String agencyName, String status, boolean shared, long unreadMessages,
			OffsetDateTime createdAt, OffsetDateTime updatedAt) {}

	public record ListResponse(List<ListItem> consultations, PageInfo pageInfo) {}

	public record RevokeResponse(String consultationId, OffsetDateTime revokedAt) {}

	public record EvidenceRef(String id, String title) {}

	public record MessageView(String id, String sender, String text, String koText, String translationStatus,
			List<EvidenceRef> attachedEvidences, OffsetDateTime createdAt) {}

	public record MessagesResponse(List<MessageView> messages, PageInfo pageInfo) {}

	public record SendMessageRequest(@NotBlank @Size(max = 2000) String text, List<String> attachEvidenceIds) {}

	public record SendMessageResponse(String id, String sender, String text, String koText, String translationStatus, OffsetDateTime createdAt) {}

	// ---- 관리자 ----
	public record StaffLoginRequest(@NotBlank String email, @NotBlank String password) {}

	public record StaffView(String id, String name, String agencyId, String agencyName, String role) {}

	public record StaffLoginResponse(String accessToken, String refreshToken, StaffView staff) {}

	public record PersonBrief(String id, String name) {}

	public record AdminListItem(String id, String workerName, String workerLanguage, String headline, int differenceAmount, String status,
			String urgencyLevel, String aiSuggestedUrgency, PersonBrief assignee, boolean hasNewMessage, OffsetDateTime receivedAt) {}

	public record AdminListResponse(List<AdminListItem> consultations, PageInfo pageInfo) {}

	public record Dashboard(long newCount, long inProgressCount, long closedCount, long highUrgencyCount, long unreadMessageCount) {}

	public record WorkerBrief(String name, String language) {}

	public record Edit(String field, String before, String after, String reason) {}

	public record TimelineDay(LocalDate date, OffsetDateTime clockInAt, OffsetDateTime clockOutAt, Integer breakMinutes, int workedMinutes,
			String source, List<String> flags, List<Edit> edits) {}

	public record WageComparisonView(int expectedAmount, int actualAmount, int differenceAmount, List<ComparisonItem> items,
			List<Uncertainty> uncertainties, AppliedConditions appliedConditions) {}

	public record SharedEvidence(String id, String type, String title, OffsetDateTime occurredAt, String fileUrl, String hash) {}

	public record ComplaintDraftView(String title, String body, Integer unpaidAmount) {}

	public record AdminInfoRequest(String id, String message, List<String> requestedItems, OffsetDateTime createdAt) {}

	public record AdminReferral(String id, String type, String organization, String contact, String note, OffsetDateTime createdAt) {}

	public record ResultView(String content, String actionTaken, String closeReason, OffsetDateTime closedAt) {}

	public record AdminConsent(List<String> sharedScope, OffsetDateTime agreedAt) {}

	public record SummaryOnly(String ko) {}

	public record ContractView(TermsView terms, List<ReviewView> reviews) {}

	public record AdminDetail(String id, String status, WorkerBrief worker, SummaryOnly summary, List<TimelineDay> timeline,
			WageComparisonView wageComparison, ContractView contract, List<SharedEvidence> evidences, ComplaintDraftView complaintDraft,
			String urgencyLevel, String aiSuggestedUrgency, PersonBrief assignee, List<AdminInfoRequest> infoRequests,
			List<AdminReferral> referrals, ResultView result, AdminConsent consent, String disclaimer) {}

	public record TriageRequest(@NotBlank @Pattern(regexp = "HIGH|MEDIUM|LOW") String urgencyLevel, @Size(max = 500) String note) {}

	public record TriageResponse(String id, String urgencyLevel, OffsetDateTime updatedAt) {}

	public record StaffBrief(String id, String name, List<String> languages) {}

	public record StaffListResponse(List<StaffBrief> staff) {}

	public record AssigneeRequest(@NotBlank String staffId) {}

	public record AssigneeResponse(String id, PersonBrief assignee) {}

	public record InfoRequestBody(@NotBlank @Size(max = 2000) String message,
			List<@Pattern(regexp = "CONTRACT|PAYSLIP|ATTENDANCE|OTHER") String> requestedItems) {}

	public record CreatedResponse(String id, String status, OffsetDateTime createdAt) {}

	public record ReferralRequest(@NotBlank @Pattern(regexp = "LABOR_ATTORNEY|INTERPRETER") String type,
			@NotBlank @Size(max = 200) String organization, @Size(max = 200) String contact, @Size(max = 1000) String note) {}

	public record StatusRequest(@NotBlank String status, @Size(max = 500) String note) {}

	public record StatusResponse(String id, String status, OffsetDateTime updatedAt) {}

	public record ResultRequest(@NotBlank @Size(max = 4000) String content, @NotBlank @Size(max = 2000) String actionTaken,
			@NotBlank @Size(max = 1000) String closeReason, Boolean shareWithWorker) {}

	public record ResultResponse(String id, String status, OffsetDateTime closedAt) {}

	public record StaffMessageRequest(@NotBlank @Size(max = 2000) String text) {}
}
