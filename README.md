# Star Platinum — Shipping Verifier

AI-assisted review of shipping instructions (SI) against draft bills of lading (BL).
Built for the Averis x Monash Hackathon 2026.

**Live prototype:** https://shipping-verifier.onrender.com  
**Repository:** https://github.com/sihen-bot/shipping-verifier

The homepage and [public demo](https://shipping-verifier.onrender.com/demo.html) require no login. The demo compares three clearly labelled fictional examples using the same Java rules as the workspace. Its extracted fields are preset; it makes no Gemini calls and does not save reviews. The AI-powered inbox, human reviews, imports and backups remain password-protected. Do not publish workspace credentials.

## Problem and solution

Shipping documentation staff need to compare instructions with draft shipment documents whose labels and layouts vary. Star Platinum groups the email, extracted document text, field comparison, and human review in one workspace. It flags missing or uncertain information instead of treating it as agreement.

The system supports review decisions. It does not approve a shipment.

## Current functionality

- Browse and search a supplied JSON inbox. This prototype does not connect to a live email account.
- Classify emails into `BL_COMPARISON`, `SI_REQUEST`, `INVOICE_QUERY`, `GENERAL`, or `SPAM` using Gemini.
- Preview readable TXT, PDF, DOCX and XLSX attachments.
- For comparison requests, identify an SI and a BL, extract seven fields independently, and compare them with Java rules.
- Show source quotations beside extracted values.
- Record manual reviews, optionally starting with a saved AI comparison, and download an audit JSON report.
- Persist imported files, successful AI response caches, completed AI comparisons and human reviews in PostgreSQL in cloud mode.

The seven fields are shipper, consignee, notify party, port of loading, port of discharge, container count and gross weight in kilograms. Vessel, voyage, commodity, booking reference, HS code and freight terms are outside the current comparison scope.

## How it works

1. A user selects an email and chooses **Verify shipping documents**. A separate classification click is unnecessary.
2. Gemini classifies the request. Non-comparison emails receive `NOT_APPLICABLE` for comparison.
3. The reader extracts local document text. Missing, unreadable or ambiguous inputs require human review.
4. Gemini extracts the two documents independently with values and source quotations.
5. Java checks quotation presence and applies field-specific normalization and comparison rules.
6. The UI displays `MATCH`, `MISMATCH` or `NEEDS_REVIEW` per field. Uncertainty can make the overall result `NEEDS_REVIEW` even when another field has a confirmed mismatch.
7. A reviewer can record findings and export the saved audit history.

Cloud data flow: browser over HTTPS to the Spring Boot container on Render. The backend calls Google Gemini over HTTPS and PostgreSQL over SSL. PostgreSQL holds durable records. The container restores a local working copy at startup for document reading.

## Technology and architecture

| Component | Implementation |
|---|---|
| UI | HTML, CSS and JavaScript, including the Star Platinum homepage |
| Backend | Java 21 target, Spring Boot 4.1.1, Maven wrapper |
| AI | Gemini REST API, current configured model `gemini-3.1-flash-lite` |
| JSON | Jackson 3 supplied by Spring Boot |
| Document readers | Apache PDFBox 3.0.8 and Apache POI 5.5.1 |
| Cloud runtime | Docker on Render, non-root runtime user |
| Persistence | PostgreSQL through JDBC and a single-instance working-copy adapter |
| Access | Public fictional demo; private workspace uses HTTP Basic login and CSRF protection |

`VerificationController` coordinates the workflow. `GeminiClient` handles calls, caching and pacing. `DocumentReader` reads supported formats. `ShipmentComparison` validates evidence and compares fields. `ReviewController` and `ReportController` handle human records and audit downloads. `DurableData` writes cloud records to PostgreSQL before updating the working copy.

## Run locally on Windows

Install a JDK compatible with Java 21 and Git. Java 21 is the Docker runtime and project compilation target. The team has also run the project with JDK 25. Maven is supplied by the wrapper.

```powershell
git clone https://github.com/sihen-bot/shipping-verifier.git
cd shipping-verifier
```

Place authorized participant input files under these paths, preserving their relative references:

```text
data/inbox/email_001.json
data/attachments/email_001_SI.txt
data/attachments/email_001_BL.txt
```

The participant data is intentionally absent from this repository. Obtain it through the organizer's authorized channel. Do not include evaluator answer files. Without input data the inbox will be empty.

If you already saved `GEMINI_API_KEY` as a Windows user environment variable, load it into the current terminal:

```powershell
$env:GEMINI_API_KEY = [Environment]::GetEnvironmentVariable("GEMINI_API_KEY", "User")
if ($env:GEMINI_API_KEY) { "API key available" } else { "Set your Gemini API key before using AI features" }
.\mvnw.cmd test
.\mvnw.cmd spring-boot:run
```

Open http://localhost:8080. Keep the terminal running. Ctrl+C stops the app. Local mode binds to `127.0.0.1`, uses files under `data/`, and does not require login or PostgreSQL. Do not expose local mode publicly.

No AI key is required for the homepage, text previews or manual review. AI features need an available model and sufficient quota in the caller's Google project.

## Pages

| Path | Purpose |
|---|---|
| `/` | Homepage |
| `/verifier.html` | Inbox and AI verification |
| `/review.html` | Manual review and audit report download |
| `/storage.html` | Protected cloud import, status and database backup |
| `/health` | Minimal public health endpoint |

The health endpoint alone does not establish that a verification request will succeed or that AI quota is available.

## Deploy on Render

Create a Docker web service from the repository and leave Root Directory empty. Create PostgreSQL in the same region. Use the supplied Dockerfile and configure these values in Render's environment settings:

| Variable | Value |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `cloud` |
| `APP_USERNAME` | Chosen demo username |
| `APP_PASSWORD` | Unique demo password, at least 16 characters |
| `GEMINI_API_KEY` | Your Google Gemini API key |
| `DB_HOST` | PostgreSQL internal hostname |
| `DB_NAME` | Actual database name, not its display name |
| `DB_USER` | PostgreSQL username |
| `DB_PASSWORD` | PostgreSQL password |

The cloud profile binds to `0.0.0.0`. The application reads Render's `PORT`, falling back to 8080 locally. Set the health check path to `/health`. Use HTTPS for cloud access and secure session cookies. Keep a single application instance with this persistence design.

For the initial data import, stop local writes and run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Export-Data.ps1
```

Upload the generated ZIP through the signed-in `/storage.html` page. Import only works with an empty cloud database. It cannot overwrite existing records. Upload limits are 30 MB compressed, 50 MB expanded, 10 MB per file and 5,000 entries. Import does not call Gemini. Download regular backups from that page and keep original local data.

The container filesystem is a working copy, not durable storage. PostgreSQL restores it on startup. Check your hosting plan's current sleep, quota and database-retention limits. A startup/port-scan timeout occurred during one deployment and a later deployment succeeded. The root cause has not been established.

## Demonstrated cases

The following results were observed on the deployed prototype during preparation. They are examples, not a dataset-wide accuracy measurement.

| Email | Observed behavior |
|---|---|
| `email_001` | All seven fields matched, with “No mismatch detected.” |
| `email_182` | Container count 5 vs 6 flagged as a mismatch. APAPA, NIGERIA vs BALTIMORE, US with the same location code required review. |
| `email_507` | SI present but draft BL missing. Comparison required human review. |

The team also demonstrated document previews, a partial manual review, cloud data import and persistence across restart. `mvnw.cmd test` passes the existing Spring context test, but this is not comprehensive test coverage.

## Limitations

- OCR is not implemented. Scanned or unsupported content requires human review.
- The automatic path expects exactly two attachments. Extra attachments require manual role selection/review.
- Container comparison currently focuses on counts. A matching count does not establish that container types agree.
- A shared port code with conflicting names requires review. No authoritative port-alias registry is integrated.
- Literal source checks establish text presence, not semantic correctness or complete extraction.
- Up to three AI requests may be needed for a new comparison. Successful response caching and pacing reduce repeat calls but do not increase quota. Failures produce explicit errors.
- Successful seven-field comparisons are saved. Early outcomes such as missing attachments and non-comparison cases are not saved as completed AI comparison reports.
- The shared cloud account is not individual reviewer identity verification. Review names are self-reported. Stored records are not tamper-proof.
- Cloud storage restores all managed files on startup. This is a single-instance prototype, not a horizontally scaled service.
- Audit JSON is not the participant bundle's evaluator submission format. Batch processing and evaluator export are not implemented.
- Dataset-wide accuracy, latency, time savings and user impact have not yet been measured.

## Data handling and AI assistance

Classification sends selected email content and attachment metadata to Google Gemini. Document verification sends extracted document text. API credentials remain in environment variables. Use only data authorized for this purpose.

ChatGPT/Codex assisted with implementation, debugging, design and documentation. The team must review the generated work, accurately state member contributions, and comply with the organizer's originality and AI-use rules. Frameworks and libraries remain subject to their respective licenses. Organizer-supplied data is not redistributed with the source.

## Roadmap

Next priorities are a broader labeled evaluation, OCR with source locations, stronger container-type and port normalization, and a quota-aware job queue. Longer-term work includes individual reviewer accounts, stronger audit controls, email integration, and measured performance under load.
