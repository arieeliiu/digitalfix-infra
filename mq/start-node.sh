#!/bin/sh
set -eu

# Never bootstrap with an empty/example password, cookie, or the guest account.
case "${RABBITMQ_DEFAULT_USER:-}" in
  ''|guest) echo 'Configure a non-guest RabbitMQ administrator.' >&2; exit 1 ;;
esac
case "${RABBITMQ_DEFAULT_PASS:-}" in
  ''|CHANGE_ME*) echo 'Configure a unique RabbitMQ password.' >&2; exit 1 ;;
esac
case "${RABBITMQ_ERLANG_COOKIE:-}" in
  ''|CHANGE_ME*) echo 'Configure a shared random Erlang cookie.' >&2; exit 1 ;;
esac

# Only a blank second node waits for the seed. Gating every restart on the seed's
# health would deadlock when the seed needs this persisted peer to recover.
if [ "$RABBITMQ_NODENAME" = 'rabbit@rabbitmq2' ] &&
   [ ! -d "$RABBITMQ_MNESIA_DIR" ]; then
  echo 'First boot: waiting for rabbit@rabbitmq1 before peer discovery.'
  attempt=0
  until rabbitmq-diagnostics -q --timeout 10 -n rabbit@rabbitmq1 \
      --erlang-cookie "$RABBITMQ_ERLANG_COOKIE" check_running >/dev/null 2>&1; do
    attempt=$((attempt + 1))
    if [ "$attempt" -ge 60 ]; then
      echo 'Seed unavailable; refusing to bootstrap a separate second cluster.' >&2
      exit 1
    fi
    sleep 5
  done
fi

# Preserve the official image's permissions, privilege drop and signal handling.
exec /usr/local/bin/docker-entrypoint.sh rabbitmq-server
