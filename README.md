# Shipping Verifier — consolidated project

This project combines the uploaded working project with audit export, corrected
AI-review pages, and a password-protected cloud profile. It is a hackathon
prototype, not a completed deployment or full-dataset submission.

## Install on Windows

1. Stop Spring Boot with Ctrl+C and make a backup copy of the current project.
2. Copy this ZIP's contents into the existing shipping-verifier folder. Merge
   folders and replace matching files. Keep the existing data folder: it contains
   inbox, attachments, saved AI responses and reviews. This ZIP excludes data.
3. In PowerShell in that folder:

```powershell
$env:GEMINI_API_KEY = [Environment]::GetEnvironmentVariable("GEMINI_API_KEY", "User")
.\mvnw.cmd spring-boot:run
```

4. Open http://localhost:8080 and refresh with Ctrl+F5. Leave the terminal open.
5. Open /review.html, load email_055, and click Download audit report (JSON).
   Your earlier saved review should be in the download. This uses no AI quota.

Default local mode binds to 127.0.0.1 and requires no login. Do not set a public
bind address in local mode. The cloud profile below is required for hosting.

## What changed

- New ReportController exports saved AI and human-review records as a JSON
  download, explicitly labelled an audit report. It is NOT submission.json.
- The uploaded review.html was the earlier manual-only version. It now includes
  the AI prefill controls supported by the uploaded Java backend.
- The inbox review link carries the currently selected email ID.
- Spring Security adds HTTP Basic authentication and CSRF protection in cloud mode.
  session.js obtains a token before same-origin browser writes. Local mode keeps
  the previous unauthenticated workflow. /health provides a minimal health probe.
- Dockerfile builds Java 21 code in a separate stage and runs as UID/GID 10001.
  The build context excludes data, caches and credentials.

## Deployment preparation — not deployed yet

Build, when Docker is available:

```text
docker build -t shipping-verifier .
```

The runtime image selects SPRING_PROFILES_ACTIVE=cloud. Configure through the
hosting provider's secrets/environment panel, never in source or image layers:

- APP_USERNAME: the demo login name.
- APP_PASSWORD: a unique password of at least 16 characters. Startup fails if
  cloud credentials are missing or too short.
- GEMINI_API_KEY: the existing Gemini key.
- PORT: hosting HTTP port if different from 8080.

The host must supply HTTPS in front of the container. Cloud session cookies are
Secure and will not work over plain HTTP. Configure forwarded headers at the
trusted proxy; do not expose the container's raw HTTP port to the internet.

Mount persistent storage at /app/data. Populate inbox/ and attachments/ from the
participant bundle. ai-cache/, ai-reports/ and reviews/ must remain writable and
persist across restarts. The volume needs permissions for UID/GID 10001. Use one
application instance while cache pacing and persistence are file-based.
Do not put data under src/main/resources/static. Never include ground-truth files.

Health check: GET /health (no credentials). All other routes require cloud login.
Browser POSTs obtain a session CSRF token through /api/session/csrf automatically.
PowerShell POST tests in cloud mode require credentials, a retained cookie session
and the token header. The old plain localhost test commands remain valid locally.

A host/account has not been selected. Do not claim cloud deployment until a live
HTTPS URL has been tested for login, preview, review persistence, export and AI.

## Current limitations and remaining work

1. Cloud deployment: host/account, persistent storage, HTTPS and live smoke test.
2. Gemini capacity: demonstrated quota is insufficient for full-dataset processing.
   Cache and pacing reduce waste but do not create quota.
3. Evaluator export: implement the participant schema for all 520 IDs. Track missing
   predictions explicitly and block final export until all required results exist.
   Audit JSON cannot be renamed submission.json to satisfy this requirement.
4. Wider accuracy testing, malformed inputs and comparison edge cases. A few working
   examples and simulated tests are not an accuracy benchmark.
5. OCR: scanned/image-bearing inputs currently escalate to review. Word/Excel/PDF
   previews cover readable text only. No OCR was added in this update.
6. Demo/pitch and actual submission instructions. Confirm whether the preliminary
   round requires full-inbox submission or only the semi-working prototype.

Manual reviewer names remain self-reported; the cloud login is shared prototype
access control, not per-person identity verification. Saved JSON is not tamper-proof.
No shipment approval is issued. Quoted evidence proves textual presence, not full
semantic correctness or that the AI selected the right total/complete party block.

## Validation performed here

All main Java files compiled against real dependencies using an independent Java
compiler. 12 configuration/export checks, 6 Spring Security mock HTTP checks,
7 session-client checks and 15 simulated review UI checks passed.
No Gemini requests were made. A full Maven/Java 21 build, Docker image build,
Windows startup and live cloud deployment have NOT been run here.

Official implementation references:
- https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html
- https://docs.spring.io/spring-boot/reference/packaging/container-images/dockerfiles.html
