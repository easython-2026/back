#!/bin/bash
# EC2 에서 SSM 으로 실행되는 배포 스크립트(GitHub Actions 가 S3 에 올리고 실행).
# 새 JAR·환경 파일을 설치하고 재시작한다. 재시작 또는 상태 확인에 실패하면 이전 버전으로 되돌린다.
# 사용법: remote-deploy.sh <배포 버킷> <커밋 SHA>
set -euo pipefail

BUCKET="$1"
SHA="$2"
APP=/opt/easython/easython.jar
ENVF=/etc/easython/easython.env
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
umask 077

health() {
	for _ in $(seq 1 60); do
		curl -fsS http://127.0.0.1:8080/api/v1/languages >/dev/null 2>&1 && return 0
		sleep 2
	done
	return 1
}

aws s3 cp --only-show-errors "s3://$BUCKET/releases/$SHA.jar" "$WORK/app.jar"
aws s3 cp --only-show-errors "s3://$BUCKET/env/$SHA.env" "$WORK/app.env"
aws s3 rm --only-show-errors "s3://$BUCKET/env/$SHA.env" # 비밀값이 든 파일은 받자마자 지운다

# 되돌리기용 백업
cp -p "$APP" "$WORK/prev.jar"
cp -p "$ENVF" "$WORK/prev.env"

install -o easython -g easython -m 0644 "$WORK/app.jar" "$APP"
install -o root -g root -m 0600 "$WORK/app.env" "$ENVF"
if ! systemctl restart easython; then
	echo "새 버전 재시작 실패" >&2
elif health; then
	echo "DEPLOYED $SHA"
	exit 0
else
	echo "새 버전 상태 확인 실패" >&2
fi

echo "이전 버전으로 되돌립니다" >&2
journalctl -u easython -n 40 --no-pager | grep -vE "PASSWORD|SECRET|API_KEY" >&2 || true
install -o easython -g easython -m 0644 "$WORK/prev.jar" "$APP"
install -o root -g root -m 0600 "$WORK/prev.env" "$ENVF"
if systemctl restart easython && health; then
	echo "ROLLED BACK" >&2
else
	echo "이전 버전 복구 후 서비스 재시작 또는 상태 확인 실패" >&2
fi
exit 1
