# Easython 배포와 발표 리허설

## 1. AWS와 HTTPS

1. 같은 VPC에 EC2(t3.micro, Java 21)와 비공개 RDS MySQL을 만든다. RDS 보안 그룹의 TCP 3306 소스는 EC2 보안 그룹으로 지정한다. EC2는 80/443을 열고 SSH는 관리자 IP로 제한하거나 SSM을 사용한다. 애플리케이션은 loopback 8080에서 수신한다.
2. 비공개 S3 버킷을 생성하고 Block Public Access 전체를 켠다. 전용 버킷에 대해 EC2 인스턴스 역할에 `s3:GetObject`, `s3:PutObject`, `s3:DeleteObject`를 `arn:aws:s3:::버킷이름/*` 범위로 부여한다. 버전 관리를 켜면 삭제된 원본 버전이 남으므로 보존·파기 정책을 먼저 정한다. 프론트가 서명 URL을 fetch/XHR로 읽는다면 S3 CORS에서 실제 프론트 origin의 GET/HEAD를 허용한다.
3. AWS RDS 공식 CA 번들을 받아 각 인증서를 Java PKCS12 truststore에 넣고 `/etc/easython/rds-truststore.p12`에 둔다. JDBC에는 RDS가 발급한 호스트명을 사용한다. 새 DB에는 빈 `easython` 데이터베이스를 만들고 초기 기동에 한해 `DDL_AUTO=update`로 스키마를 만든 후 `validate`로 복귀한다. 기존 DB에서 스키마 변경이 필요하면 백업과 스테이징 검증을 먼저 한다.
4. `./gradlew test bootJar`로 빌드한다. 전용 시스템 계정 `easython`을 만들고 실행용 JAR를 `/opt/easython/easython.jar`에 설치한다. 정적 Noto Sans KR TTF를 서비스 계정이 읽을 수 있는 경로에 두고, 환경변수 예시를 `/etc/easython/easython.env`에 복사해 실제 값을 입력한다. 파일 소유자는 root, 권한은 0600으로 설정한다.
5. `easython.service`를 `/etc/systemd/system/`에 설치한 뒤 `sudo systemctl daemon-reload`, `sudo systemctl enable --now easython`을 실행한다. `journalctl -u easython -n 100`과 `curl -fsS http://127.0.0.1:8080/api/v1/languages`로 확인한다.
6. 내도메인.한국에서 확보한 도메인의 A 레코드를 EC2의 고정 공인 IPv4로 연결한다. 한글 도메인은 nginx와 Certbot에 DNS의 ASCII 표기(Punycode)를 사용한다. nginx 설정의 `api.example.com`을 교체해 설치하고 `sudo nginx -t`를 통과시킨다. `sudo certbot --nginx -d 실제도메인`으로 인증서를 발급하고 HTTPS 리다이렉트와 `sudo certbot renew --dry-run`을 확인한다. 인증서 발급 한도에 걸리면 재시도 시점을 확인하거나 ZeroSSL ACME 계정과 EAB 설정으로 발급한다.
7. HTTPS에서 언어 목록, 프론트 origin의 CORS preflight, 파일 업로드와 서명 URL 다운로드를 확인한다. 서명 URL은 만료 전까지 전달받은 사람이 열 수 있으므로 로그나 공유 문서에 남기지 않는다.

기존 로컬 증거 파일을 운영 DB와 함께 이전하는 경우, 쓰기를 잠시 멈추고 파일명(객체 키)을 그대로 유지해 S3 버킷에 복사한다. 건수와 SHA-256을 대조한 뒤 저장소를 전환한다. DB와 파일 백업은 같은 시점으로 맞춘다.

참고: [RDS 비공개 접근](https://docs.aws.amazon.com/AmazonRDS/latest/gettingstartedguide/security-public-private.html) · [S3 서명 URL](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/examples-s3-presign.html)

## 2. 3분 데모

별도 스테이징 DB·버킷 또는 로컬에서 테스트한다. 공개 운영 프로필에서는 데모 시드를 끈다.

```bash
AI_PROVIDER=mock DEMO_ENABLED=true STORAGE_DIR="$(mktemp -d)" PDF_FONT_PATH=/path/to/font.ttf ./gradlew bootRun
python3 scripts/demo-smoke.py http://127.0.0.1:8080 /tmp/easython-demo.pdf
```

스크립트는 매번 새 투안 테스트 계정을 생성한다. 데모 시드 → 계약서 조회 → 임금 불일치 비교 → 베트남어 진정서 생성·확정 → PDF 다운로드를 확인한다. 발표에서는 PDF의 한국어·베트남어, 차액과 증거 목록을 화면에서 확인한다. 스테이징에서 `AI_PROVIDER=gateway`와 실제 키로도 리허설하고, 장애 때는 **스테이징** 환경 파일에서 `AI_PROVIDER=mock`으로 바꾼 뒤 서비스를 재시작한다. 시드 계약서·명세서는 미리 채운 데이터이므로 실제 OCR은 아래에서 별도로 검증한다.

## 3. 인수 전 검증

| 항목 | 검증 방법 |
| --- | --- |
| OCR | 비식별화한 계약서·명세서를 정상 사진, 작은 글씨, 사선 사진, PDF로 업로드한다. 날짜·시간·금액·공제 항목을 정답표와 비교하고 실패 유형을 기록한다. |
| 번역 | 베트남어·영어 원어민이 계약 조항, 알림, 진정서의 금액·날짜·용어와 표현을 검수한다. |
| 임금 | 노무 검수자가 비교 결과와 계산 근거를 대조한다. 주휴·야간·휴일 가산은 현재 미지원이며 차액이 최종 체불액으로 보이지 않는지 화면도 검수한다. |
| 실기기 | iOS Safari와 Android Chrome에서 HTTPS 프론트로 로그인해 위치 승인/거부, 카메라 업로드, CORS, 서명 URL의 접근/만료와 PDF 다운로드를 확인한다. |
