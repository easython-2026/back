# API 요청·응답 예시

해커톤 운영 서버(현재 종료)에 실제로 요청해 받은 응답입니다(2026-11, 시연용 가상 데이터).

- 토큰·서명 URL 파라미터는 `<redacted>`로 가렸습니다. 긴 배열은 앞 3~4건만 남기고 "외 N건 생략"으로 줄였습니다.
- 모든 응답은 `{ success, data, message, errorCode }` 공통 형식이며, `message`는 사용자 언어(ko/en/vi)로 옵니다.

| 파일 | 요청 | 상태 | 내용 |
| --- | --- | --- | --- |
| [`01_auth_login.json`](01_auth_login.json) | `POST /api/v1/auth/login` | 200 | 로그인 — JWT 액세스·리프레시 토큰 발급 |
| [`02_users_me.json`](02_users_me.json) | `GET /api/v1/users/me` | 200 | 내 정보 |
| [`03_attendance_location_check.json`](03_attendance_location_check.json) | `POST /api/v1/attendance/location-check` | 200 | 출근 전 GPS 위치 인증(근무지 반경 100m) — 성공 시 1회용 인증 토큰 |
| [`04_attendance_location_check_fail.json`](04_attendance_location_check_fail.json) | `POST /api/v1/attendance/location-check` | 200 | GPS 위치 인증 실패(근무지에서 1.8km) |
| [`05_attendance_monthly.json`](05_attendance_monthly.json) | `GET /api/v1/attendance/monthly` | 200 | 월간 근무기록(일별 출퇴근·휴게·실근무, 기록 출처 DEVICE/MANUAL) |
| [`06_attendance_anomalies.json`](06_attendance_anomalies.json) | `GET /api/v1/attendance/anomalies` | 200 | 이상 근무 패턴 탐지(무휴게 장시간·사후 시간 변경) |
| [`07_contract_detail.json`](07_contract_detail.json) | `GET /api/v1/contracts/617a496a-99c4-4c7f-acbf-c2a3410206f0` | 200 | 근로계약서 AI 분석 결과(조건 추출·검토 항목·조항 원문/번역·요약) |
| [`08_payroll_month_state.json`](08_payroll_month_state.json) | `GET /api/v1/payroll/comparisons` | 200 | 월별 급여 비교 상태 |
| [`09_payroll_comparison_detail.json`](09_payroll_comparison_detail.json) | `GET /api/v1/payroll/comparisons/73dc42f2-21a8-4148-93dd-372a9fbecd95` | 200 | 급여 비교 상세(적용 조건·규칙·설명·면책) |
| [`10_complaint_detail.json`](10_complaint_detail.json) | `GET /api/v1/complaints/4355ed1a-d447-4551-a90b-f4b1066b3e99` | 200 | 진정서 초안(한국어 템플릿 + 모국어 번역, 청구 항목, 증거) |
| [`11_report_monthly.json`](11_report_monthly.json) | `GET /api/v1/reports/monthly` | 200 | 월간 근무·임금 리포트 |
| [`12_evidences.json`](12_evidences.json) | `GET /api/v1/evidences` | 200 | 증거 목록(사진·수동 기록·계약서 등, 해시 포함) |
| [`13_consultation_detail.json`](13_consultation_detail.json) | `GET /api/v1/consultations/df0b76f8-c5be-461e-9a69-10f23946fd00` | 200 | 상담 상세(근로자 측: 상태·요약·공유 범위·추가 자료 요청·이력) |
| [`14_consultation_messages.json`](14_consultation_messages.json) | `GET /api/v1/consultations/df0b76f8-c5be-461e-9a69-10f23946fd00/messages` | 200 | 상담 메시지(원문 + 자동 번역) |
| [`20_admin_dashboard.json`](20_admin_dashboard.json) | `GET /api/v1/admin/dashboard` | 200 | 관리자 대시보드 집계 |
| [`21_admin_consultations.json`](21_admin_consultations.json) | `GET /api/v1/admin/consultations` | 200 | 관리자 사건 목록(긴급도·담당자·차액) |
| [`22_admin_consultation_detail.json`](22_admin_consultation_detail.json) | `GET /api/v1/admin/consultations/df0b76f8-c5be-461e-9a69-10f23946fd00` | 200 | 관리자 사건 상세(동의한 범위의 자료만, AI 제안 긴급도) |
| [`30_error_validation.json`](30_error_validation.json) | `GET /api/v1/payroll/comparisons` | 400 | 오류 형식 — 필수 파라미터 누락(400) |
| [`31_error_unauthorized.json`](31_error_unauthorized.json) | `GET /api/v1/users/me` | 401 | 오류 형식 — 인증 없음(401) |
| [`32_error_forbidden.json`](32_error_forbidden.json) | `GET /api/v1/admin/dashboard` | 403 | 오류 형식 — 근로자 토큰으로 관리자 API(403) |
