---
description: 'Compile backend+app, build the debug APK, deploy the backend to AWS EC2, and validate health.'
---
Run the repo's ops build command and report the result back to the user.

Steps:
1. Run `./infra/ops.sh build` in a terminal at the repo root (this wraps `infra/deploy.sh`).
2. Stream/show the output as it runs — it compiles the backend Docker image
   (`--platform linux/arm64`), builds the Android debug APK (copies it to
   `jagadhabi-app-latest.apk`), opens SSH to the prod EC2 instance only if not
   already allow-listed, transfers + loads the image, restarts the docker compose
   stack with `--no-build`, polls `/health` until it reports healthy, and revokes
   the SSH rule again only if it added it.
3. If any step fails, show the actual error output — do not guess or fabricate a
   result. On failure, also run `docker ps` on the instance (via the same SSH key)
   to help diagnose, if the failure happened after the SSH step.
4. On success, confirm: backend Docker image built OK, APK built OK, `/health`
   returned `{"status":"ok"}`, and `docker ps` shows `construction-backend` as
   `healthy`.

Accepts optional flags the user may mention, passed straight through, e.g.:
- "skip the app build" / "backend only" -> `./infra/ops.sh build --skip-app`
- "don't deploy" / "just compile" -> `./infra/ops.sh build --skip-backend-deploy`

See `infra/deploy.sh` and `AGENT_NOTES.md` ("Redeploy workflow") for full context.

