# easython backend

이주노동자 임금체불 증거 도우미 백엔드. Spring Boot 4 / Java 21 / MySQL.
API 규격은 노션 "API 명세서"(Base URL `/api/v1`)를 따른다.

## 실행

```bash
./gradlew bootRun          # 기본: 메모리 H2(MySQL 모드), 설치 불필요, 데모 모드
./gradlew test             # 통합·단위 테스트(실제 AI 호출 없음)
```

로컬 MySQL:

```bash
docker compose up -d
DB_URL="jdbc:mysql://localhost:3306/easython?serverTimezone=Asia/Seoul&characterEncoding=UTF-8" DB_USERNAME=easython DB_PASSWORD=easython ./gradlew bootRun
```

실제 AI 호출 확인(유료 크레딧, 평소엔 건너뜀):

```bash
AI_LIVE_TEST=1 AI_API_KEY=... ./gradlew test --tests '*GatewayAiLiveTest'
```

## 환경변수 (`.env.example` 참고, 실제 값은 절대 커밋하지 않기)

| 이름 | 설명 | 기본값 |
|---|---|---|
| `DB_URL` `DB_USERNAME` `DB_PASSWORD` | DB 접속 | H2 메모리 |
| `DDL_AUTO` | Hibernate ddl-auto | `update` |
| `JWT_SECRET` | JWT 서명 키(32바이트 이상). **데모 모드가 아니면 필수**(미지정 시 기동 실패) | 개발용 고정값 |
| `CORS_ORIGINS` | 허용 프론트 주소(쉼표 구분) | `http://localhost:5173,http://localhost:3000` |
| `AI_API_KEY` (또는 `DKU_API_KEY`) | AI 게이트웨이 키. 없으면 데모용 모의 응답 | - |
| `AI_BASE_URL` `AI_MODEL` | OpenAI 호환 게이트웨이 주소·모델 | dku-chat / `anthropic/claude-haiku-4.5` |
| `AI_PROVIDER` | `mock` 이면 키가 있어도 모의 응답 | - |
| `STORAGE_DIR` | 증거 파일 저장 폴더 | `~/easython-uploads` |
| `PUBLIC_BASE_URL` | 파일 서명 URL 앞에 붙일 주소 | (상대경로) |
| `PDF_FONT_PATH` | 진정서 PDF 한글 TTF(Noto Sans KR 권장). 없으면 시스템 폰트 탐색 | - |
| `MAIL_USERNAME` `MAIL_PASSWORD` | Gmail SMTP(앱 비밀번호). 비우면 메일 미발송 | - |
| `DEMO_ENABLED` | 데모 시드 API·데모 기관/담당자 계정. **배포 시 `false`** | `true` |
| `DEMO_STAFF_PASSWORD` | 데모 담당자 계정(`staff@agency.org`) 비밀번호 | `Test1234!` |

## 구현 현황 (노션 명세 73개 전부)

- 공통: 응답 봉투, 에러 코드, 사용자 언어(ko/en/vi) 메시지, JWT(Bearer, refresh 회전), ADMIN 경로 제한
- Auth/User: 가입·로그인·재발급·로그아웃, 비밀번호 재설정(코드 해시·10분·시도/요청 제한), 내 정보, 월급날, 근무장소·사업장,
  동의 내역·선택 동의 변경, 회원 탈퇴(자료 삭제 + 계정 익명화), 저장 기록 삭제 요청
- Attendance: GPS 위치 인증(100m, 1회용 토큰), 출퇴근 사진(SHA-256, 서버 시각), 홈·주간·월간·상세, 휴게·수정(원본 유지+이력)·수동 추가,
  확인 필요 항목(NO_RECORD 포함), 이상 패턴(규칙 기반)
- Contracts: 업로드 → AI 분석(비동기) → 조건 추출·조항 번역·검토 항목(최저임금·숙식비·휴게), 수정·확정·재분석·삭제
- Payroll: 명세서 AI 분석/수령액 입력, 예상 임금 계산(기본급+연장 가산)과 비교, 월 상태
- Notifications / Report: 월급날·기록 누락·불일치·상담 알림, 월간 리포트, 증거 목록
- Complaints: 한국어 초안(코드 템플릿, 금액·날짜 정확) + AI 번역, 검토·확정, 증거 연결, 한·모국어 대조 PDF
- Consultations / Admin: 공유 동의 기반 상담 신청·철회·번역 메시지, 담당자 목록(최근/긴급도 정렬)·대시보드·상세(동의 범위만, 열람 감사 로그)·
  긴급도·배정·자료 요청·연계·상태·종결
- Demo: 1개월 근무기록 + 샘플 계약서 + 샘플 명세서

## 설계 메모

- AI 는 `AiClient` 뒤에 있다. 키가 없으면 `MockAiClient`(투안 시나리오)로 전체 흐름이 동작한다.
- 금액·날짜가 중요한 문서(진정서·상담 요약·임금 설명)는 코드 템플릿으로 만들고 AI 는 번역에만 쓴다.
- 긴급도는 금액·계약 종료일 규칙으로 *제안*만 하고 확정은 담당자가 한다.
- 증거 원본은 덮어쓰기 불가, 조회는 만료되는 서명 URL로만.
- 남은 것: 파일 저장을 S3로 교체(`FileStorage`), 배포(EC2·RDS·HTTPS), 주휴수당·야간·휴일 가산(현재 "미지원"으로 표시).

## 운영 배포 시 체크

1. `DEMO_ENABLED=false`, `JWT_SECRET`, DB, `CORS_ORIGINS`, `PUBLIC_BASE_URL` 지정
2. 상담기관·담당자는 DB 에 직접 등록(담당자: `users.role='ADMIN'`, `agency_id` 지정, 비밀번호는 BCrypt 해시)
3. `PDF_FONT_PATH` 에 한글+베트남어 글리프가 있는 TTF 지정(예: Noto Sans KR)
4. 최저임금 `app.payroll.min-wage-hourly`/`min-wage-year` 를 해마다 갱신
