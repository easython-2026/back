#!/bin/bash
# Auto Scaling 그룹 EC2 시작 스크립트(시작 템플릿 user-data).
# ECR 이미지(앱)와 nginx 를 컨테이너로 띄운다. ALB 가 TLS 를 끝내고 80 으로 보낸다.
# 비밀값은 SSM Parameter Store(SecureString) /easython/env, 이미지 태그는 /easython/image-tag.
set -euo pipefail
REGION=ap-northeast-2
REGISTRY=424051028041.dkr.ecr.$REGION.amazonaws.com
export AWS_DEFAULT_REGION=$REGION

dnf install -y docker
systemctl enable --now docker

install -d -m 0700 /etc/easython
umask 077
aws ssm get-parameter --name /easython/env --with-decryption --query Parameter.Value --output text > /etc/easython/app.env
TAG=$(aws ssm get-parameter --name /easython/image-tag --query Parameter.Value --output text)

cat > /etc/easython/nginx.conf <<'EOF'
resolver 127.0.0.11 valid=60s ipv6=off;  # Docker 내장 DNS — Vercel IP가 바뀌어도 따라감

server {
    listen 80;
    client_max_body_size 20m;

    location /api/v1/ {
        proxy_pass http://app:8080;
        proxy_http_version 1.1;
        proxy_read_timeout 120s;
        proxy_set_header Host $host;
        proxy_set_header Forwarded "";
        proxy_set_header X-Forwarded-Host $host;
        proxy_set_header X-Forwarded-Port 443;
        proxy_set_header X-Forwarded-Proto $http_x_forwarded_proto;
        proxy_set_header X-Forwarded-For $http_x_forwarded_for;
        proxy_set_header X-Forwarded-Prefix "";
    }

    # 프론트(Vercel)를 같은 도메인으로: /admin/ → 관리자 웹, 그 외 → 사용자 앱. 같은 출처라 CORS 불필요.
    # 관리자 웹은 Vite base '/admin/' + BrowserRouter basename '/admin' 으로 빌드돼야 한다(Vercel 쪽은 /admin 없이 서빙되므로 접두사를 떼고 전달).
    location = /admin {
        return 301 /admin/;
    }

    location /admin/ {
        set $admin_upstream front-ten-ochre.vercel.app;
        rewrite ^/admin/(.*)$ /$1 break;
        proxy_pass https://$admin_upstream;
        proxy_ssl_server_name on;
        proxy_ssl_name $admin_upstream;
        proxy_set_header Host $admin_upstream;
        proxy_set_header X-Forwarded-For $http_x_forwarded_for;
    }

    location / {
        set $user_upstream front-user-app-ochre.vercel.app;
        proxy_pass https://$user_upstream;
        proxy_ssl_server_name on;
        proxy_ssl_name $user_upstream;
        proxy_set_header Host $user_upstream;
        proxy_set_header X-Forwarded-For $http_x_forwarded_for;
    }
}
EOF
chmod 0644 /etc/easython/nginx.conf

aws ecr get-login-password | docker login --username AWS --password-stdin "$REGISTRY"
docker network create easython
docker run -d --name app --network easython --restart unless-stopped \
  --env-file /etc/easython/app.env --log-opt max-size=10m --log-opt max-file=3 \
  "$REGISTRY/easython:$TAG"
docker run -d --name web --network easython --restart unless-stopped -p 80:80 \
  -v /etc/easython/nginx.conf:/etc/nginx/conf.d/default.conf:ro --log-opt max-size=10m \
  public.ecr.aws/nginx/nginx:1.27-alpine
