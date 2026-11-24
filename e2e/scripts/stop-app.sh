#!/usr/bin/env bash
# Graceful shutdown of the QA instance (also stops the embedded DB).
curl -fsS -m 10 -X POST http://127.0.0.1:8778/api/system/shutdown >/dev/null 2>&1 || true
for _ in $(seq 1 30); do curl -fsS -m 2 -o /dev/null http://127.0.0.1:8778/api/system/status 2>/dev/null || exit 0; sleep 1; done
exit 1
