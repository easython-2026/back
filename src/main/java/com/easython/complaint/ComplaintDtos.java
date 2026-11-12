package com.easython.complaint;

import com.easython.common.PageInfo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public final class ComplaintDtos {
	private ComplaintDtos() {}

	public record CreateRequest(@NotBlank String comparisonId, String periodStart, String periodEnd) {}

	public record CreateResponse(String complaintId, String status) {}

	public record Claim(@NotBlank String label, @Min(0) int amount) {}

	public record Party(String name) {}

	public record Respondent(String name, String address, String ownerName) {}

	public record Summary(String ko, String translated) {}

	public record KoDraft(String title, String body, Integer unpaidAmount, List<Claim> claims) {}

	public record TranslatedDraft(String language, String title, String body) {}

	public record Draft(KoDraft ko, TranslatedDraft translated) {}

	public record EvidenceBrief(String id, String type, String title, OffsetDateTime occurredAt) {}

	public record Contact(String name, String phone) {}

	public record OfficialGuide(String agencyName, List<String> steps, List<Contact> contacts) {}

	public record Detail(String id, String status, String translationStatus, String failReason, String comparisonId, boolean reviewed,
			LocalDate periodStart, LocalDate periodEnd, Party complainant, Respondent respondent, Summary summary, Draft draft,
			List<EvidenceBrief> evidences, OfficialGuide officialGuide, String disclaimer, OffsetDateTime createdAt, OffsetDateTime updatedAt) {}

	public record ListItem(String id, String status, boolean reviewed, LocalDate periodStart, LocalDate periodEnd, Integer unpaidAmount, OffsetDateTime createdAt) {}

	public record ListResponse(List<ListItem> complaints, PageInfo pageInfo) {}

	/** 한국어 초안 수정(보낸 필드만). */
	public record DraftPatch(@Size(min = 1, max = 200) String title, @Size(min = 1, max = 20000) String body, @Min(0) Integer unpaidAmount, @Valid List<Claim> claims) {}

	public record RespondentPatch(@Size(min = 1, max = 200) String name, @Size(min = 1, max = 300) String address, @Size(min = 1, max = 100) String ownerName) {}

	public record PatchRequest(@Valid DraftPatch draft, String periodStart, String periodEnd, @Valid RespondentPatch respondent,
			Boolean retranslate, Boolean confirm) {}

	public record EvidencesRequest(@NotEmpty List<@NotBlank String> evidenceIds) {}

	public record EvidencesResponse(List<EvidenceBrief> evidences) {}
}
