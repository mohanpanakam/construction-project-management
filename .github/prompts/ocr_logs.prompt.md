---
description: 'Fetch logs from the ocr-service container on the prod EC2 instance.'
---
Run the repo's ops OCR-logs command and show the user the result.

Steps:
1. Run `./infra/ocr-logs.sh` in a terminal at the repo root (or `./infra/ops.sh ocr_logs`
   — they're equivalent). This handles opening SSH to the prod EC2 instance only if
   not already allow-listed, fetches `docker logs` from the `ocr-service` container,
   and revokes the SSH rule again only if it added it.
2. Default is the last 200 lines. If the user asks for something different, pass the
   corresponding flag straight through instead, e.g.:
   - "more lines" / "last 500" -> `./infra/ocr-logs.sh -n 500`
   - "follow" / "tail it live" -> `./infra/ocr-logs.sh -f` (note: this blocks/streams
     until interrupted — tell the user how to stop it, Ctrl+C, before running it)
   - "last hour" / "since 10 minutes ago" -> `./infra/ocr-logs.sh --since 1h` /
     `./infra/ocr-logs.sh --since 10m`
3. Show the actual log output returned — do not summarize away real error lines the
   user might need to see (e.g. OCR failures, Tesseract/PaddleOCR errors).

See `infra/ocr-logs.sh` and `AGENT_NOTES.md` for full context.

