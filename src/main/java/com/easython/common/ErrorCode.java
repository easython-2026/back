package com.easython.common;

import org.springframework.http.HttpStatus;

/** API 명세 공통 에러 코드. 이름이 곧 응답의 errorCode. */
public enum ErrorCode {
	VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "error.validation"),
	CONSENT_REQUIRED(HttpStatus.BAD_REQUEST, "error.consent"),
	UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "error.unauthorized"),
	INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "error.credentials"),
	INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "error.refresh"),
	FORBIDDEN(HttpStatus.FORBIDDEN, "error.forbidden"),
	NOT_FOUND(HttpStatus.NOT_FOUND, "error.notfound"),
	CONFLICT(HttpStatus.CONFLICT, "error.conflict"),
	DUPLICATED_EMAIL(HttpStatus.CONFLICT, "error.email.duplicated"),
	FILE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "error.file.toolarge"),
	UNSUPPORTED_FILE_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "error.file.type"),
	LOCATION_CONSENT_REQUIRED(HttpStatus.FORBIDDEN, "error.location.consent"),
	CAMERA_CONSENT_REQUIRED(HttpStatus.FORBIDDEN, "error.camera.consent"),
	GPS_NOT_VERIFIED(HttpStatus.FORBIDDEN, "error.gps.notverified"),
	ALREADY_CLOCKED_IN(HttpStatus.CONFLICT, "error.clockin.already"),
	NOT_CLOCKED_IN(HttpStatus.CONFLICT, "error.clockin.none"),
	ALREADY_CLOCKED_OUT(HttpStatus.CONFLICT, "error.clockout.already"),
	DUPLICATED_RECORD(HttpStatus.CONFLICT, "error.record.duplicated"),
	CONTRACT_NOT_CONFIRMED(HttpStatus.CONFLICT, "error.contract.notconfirmed"),
	NO_ATTENDANCE_DATA(HttpStatus.CONFLICT, "error.attendance.nodata"),
	STATEMENT_NOT_CONFIRMED(HttpStatus.CONFLICT, "error.statement.notconfirmed"),
	NOT_MISMATCH(HttpStatus.CONFLICT, "error.complaint.notmismatch"),
	BUSINESS_INFO_REQUIRED(HttpStatus.CONFLICT, "error.complaint.business"),
	NOT_REVIEWED(HttpStatus.CONFLICT, "error.complaint.notreviewed"),
	DUPLICATED_CONSULTATION(HttpStatus.CONFLICT, "error.consultation.duplicated"),
	CONSENT_NOT_GRANTED(HttpStatus.FORBIDDEN, "error.consultation.consent"),
	INVALID_TRANSITION(HttpStatus.CONFLICT, "error.consultation.transition"),
	ACTIVE_CONSULTATION(HttpStatus.CONFLICT, "error.consultation.active"),
	INVALID_CODE(HttpStatus.BAD_REQUEST, "error.code.invalid"),
	TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "error.toomany"),
	INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "error.internal"),
	AI_SERVICE_ERROR(HttpStatus.BAD_GATEWAY, "error.ai");

	public final HttpStatus status;
	public final String messageKey;

	ErrorCode(HttpStatus status, String messageKey) {
		this.status = status;
		this.messageKey = messageKey;
	}
}
