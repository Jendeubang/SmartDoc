#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  echo "Usage: ./deploy/quickstart.sh [--no-build]"
  echo "  --no-build  Reuse existing images instead of rebuilding them."
  exit 0
fi

command -v docker >/dev/null 2>&1 || { echo "Docker command not found." >&2; exit 1; }
command -v openssl >/dev/null 2>&1 || { echo "openssl is required to generate local secrets." >&2; exit 1; }

created_env=false
if [[ ! -f .env ]]; then
  cp .env.example .env
  created_env=true
  echo "Created .env from .env.example"
else
  echo "Using existing .env; non-empty values will be preserved."
fi

get_env() {
  local key="$1"
  grep -E "^${key}=" .env 2>/dev/null | head -n 1 | cut -d= -f2- || true
}

set_env() {
  local key="$1" value="$2" force="${3:-false}" current
  current="$(get_env "$key")"
  if [[ "$force" != "true" && -n "$current" && "$current" != *replace-with-* && "$current" != change-me* ]]; then
    return
  fi
  if grep -qE "^${key}=" .env; then
    sed -i.bak "s|^${key}=.*|${key}=${value}|" .env
    rm -f .env.bak
  else
    printf '%s=%s\n' "$key" "$value" >> .env
  fi
}

secret() { openssl rand -hex 32; }
web_port="$(get_env SMARTDOC_WEB_PORT)"; [[ -n "$web_port" ]] || web_port=8088
mysql_password="$(get_env MYSQL_PASSWORD)"; [[ -n "$mysql_password" && "$mysql_password" != replace-with-* ]] || mysql_password="$(secret)"
root_password="$(get_env MYSQL_ROOT_PASSWORD)"; [[ -n "$root_password" && "$root_password" != replace-with-* ]] || root_password="$(secret)"
rabbit_password="$(get_env RABBITMQ_PASSWORD)"; [[ -n "$rabbit_password" && "$rabbit_password" != replace-with-* ]] || rabbit_password="$(secret)"
minio_password="$(get_env MINIO_SECRET_KEY)"; [[ -n "$minio_password" && "$minio_password" != replace-with-* ]] || minio_password="$(secret)"
admin_password="$(get_env SMARTDOC_BOOTSTRAP_ADMIN_PASSWORD)"; admin_generated=false
if [[ -z "$admin_password" || "$admin_password" == replace-with-* ]]; then admin_password="$(secret)"; admin_generated=true; fi

set_env SMARTDOC_WEB_PORT "$web_port" "$created_env"
set_env MYSQL_HOST mysql "$created_env"
set_env MYSQL_USERNAME smartdoc "$created_env"
set_env MYSQL_PASSWORD "$mysql_password"
set_env MYSQL_ROOT_PASSWORD "$root_password"
set_env REDIS_HOST redis "$created_env"
set_env RABBITMQ_HOST rabbitmq "$created_env"
set_env RABBITMQ_USERNAME smartdoc "$created_env"
set_env RABBITMQ_PASSWORD "$rabbit_password"
set_env RABBITMQ_DEFAULT_USER smartdoc "$created_env"
set_env RABBITMQ_DEFAULT_PASS "$rabbit_password"
set_env MINIO_ENDPOINT http://minio:9000 "$created_env"
set_env MINIO_ACCESS_KEY smartdoc "$created_env"
set_env MINIO_SECRET_KEY "$minio_password"
set_env MINIO_ROOT_USER smartdoc "$created_env"
set_env MINIO_ROOT_PASSWORD "$minio_password"
set_env JWT_KEYS "current:$(secret)"
set_env JWT_ACTIVE_KID current "$created_env"
set_env INTERNAL_SERVICE_TOKEN "$(secret)"
quickstart_origin="http://localhost:${web_port}"
cors_origins="$(get_env CORS_ALLOWED_ORIGINS)"
if [[ -z "$cors_origins" ]]; then
  set_env CORS_ALLOWED_ORIGINS "$quickstart_origin" true
elif [[ ",$cors_origins," != *",$quickstart_origin,"* ]]; then
  set_env CORS_ALLOWED_ORIGINS "$cors_origins,$quickstart_origin" true
fi
set_env SMARTDOC_BOOTSTRAP_ADMIN_USERNAME admin "$created_env"
set_env SMARTDOC_BOOTSTRAP_ADMIN_PASSWORD "$admin_password"
set_env SMARTDOC_BOOTSTRAP_ADMIN_EMAIL admin@example.com "$created_env"
set_env SMARTDOC_BOOTSTRAP_ADMIN_PHONE 13800000000 "$created_env"
set_env SMARTDOC_AI_CREDENTIAL_MASTER_KEY "$(secret)"

compose_args=(--env-file .env -f docker-compose.yml -f docker-compose.quickstart.yml up -d)
if [[ "${1:-}" != "--no-build" ]]; then compose_args+=(--build); fi
echo "Starting SmartDoc services. The first build may take several minutes."
docker compose "${compose_args[@]}"
docker compose --env-file .env -f docker-compose.yml -f docker-compose.quickstart.yml ps
echo "SmartDoc URL: http://localhost:${web_port}"
echo "Administrator username: admin"
if [[ "$admin_generated" == true ]]; then echo "Generated administrator password: $admin_password"; fi
echo "To follow logs: docker compose --env-file .env -f docker-compose.yml -f docker-compose.quickstart.yml logs -f"
