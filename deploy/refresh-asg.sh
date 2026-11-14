#!/usr/bin/env bash
set -euo pipefail

status_of() {
  aws autoscaling describe-instance-refreshes --auto-scaling-group-name "$ASG" \
    --instance-refresh-ids "$1" --query 'InstanceRefreshes[0].Status' --output text
}

wait_for_refresh() {
  local id=$1 goal=$2 status
  for ((attempt=0; attempt<120; attempt++)); do
    status=$(status_of "$id")
    echo "Instance refresh $id: $status"
    case "$status" in
      Successful|Failed|Cancelled|RollbackSuccessful|RollbackFailed)
        [[ "$goal" == stopped || "$status" == Successful ]]
        return ;;
    esac
    sleep 10
  done
  echo "Timed out waiting for instance refresh $id" >&2
  return 1
}

start_refresh() {
  aws autoscaling start-instance-refresh --auto-scaling-group-name "$ASG" \
    --preferences '{"MinHealthyPercentage":100,"MaxHealthyPercentage":200,"InstanceWarmup":60,"SkipMatching":false}' \
    --query InstanceRefreshId --output text
}

check_health() {
  curl -fsS --connect-timeout 5 --max-time 10 --retry 5 --retry-delay 3 -- "$HEALTH_URL" > /dev/null
}

case "${1:-}" in
  deploy)
    : "${HEALTH_URL:?Missing production health URL}"
    id=$(start_refresh)
    echo "DEPLOY_REFRESH_ID=$id" >> "$GITHUB_ENV"
    wait_for_refresh "$id" healthy
    check_health
    ;;
  rollback)
    : "${PREV_ENV_VERSION:?Missing previous environment version}" "${PREV_TAG:?Missing previous image tag}"
    if [[ -n "${DEPLOY_REFRESH_ID:-}" ]]; then
      case "$(status_of "$DEPLOY_REFRESH_ID")" in
        Pending|InProgress|Baking)
          # A refresh can finish between the status lookup and cancellation.
          aws autoscaling cancel-instance-refresh --auto-scaling-group-name "$ASG" ||
            echo "Cancellation request failed; checking whether the refresh stops" >&2
          ;;
      esac
      wait_for_refresh "$DEPLOY_REFRESH_ID" stopped
    fi

    umask 077
    previous_env=$(mktemp "$RUNNER_TEMP/previous-env.XXXXXX")
    trap 'unlink "$previous_env"' EXIT
    aws ssm get-parameter --name "/easython/env:$PREV_ENV_VERSION" --with-decryption \
      --query Parameter.Value --output text > "$previous_env"
    aws ssm put-parameter --name /easython/env --type SecureString --overwrite \
      --value "file://$previous_env" > /dev/null
    aws ssm put-parameter --name /easython/image-tag --type String --overwrite \
      --value "$PREV_TAG" > /dev/null

    if [[ -n "${DEPLOY_REFRESH_ID:-}" ]]; then
      # The launch template is unchanged. Replace every instance to reload SSM values.
      id=$(start_refresh)
      wait_for_refresh "$id" healthy
      check_health
      echo "Rolled back ASG to image $PREV_TAG"
    fi
    ;;
  *)
    echo "Usage: $0 deploy|rollback" >&2
    exit 2
    ;;
esac
