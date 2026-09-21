# Connect the existing Render database

This update uses PostgreSQL as durable storage in the `cloud` profile. The local
profile continues to use your existing data folder without needing a database.
The cloud process restores a working copy from the database before startup
completes. Saves reach PostgreSQL before being reported successful.

## 1. Set environment variables BEFORE pushing this code

Open Render's existing shipping-verifier-db database, then its Info / connection
information. On the shipping-verifier WEB SERVICE, Environment, add:

| Variable | Value from the database |
|---|---|
| DB_HOST | Internal Hostname only (not the full connection URL) |
| DB_NAME | Database name |
| DB_USER | Username |
| DB_PASSWORD | Password |

Keep SPRING_PROFILES_ACTIVE=cloud, APP_USERNAME, APP_PASSWORD and GEMINI_API_KEY.
Use the internal hostname because the app and database are both in Singapore.
No credentials should be pasted into source files or committed to GitHub.
Use Save only if offered; otherwise save/redeploy the existing app, then push
this update. The old code does not use the new DB variables.

## 2. Install and push

Stop your local app with Ctrl+C. Back up the project folder. Extract this ZIP
and merge its contents into the existing shipping-verifier project, replacing
matching source/config files. Preserve your data folder and Git repository.

In PowerShell from that project:

    .\mvnw.cmd test
    git add src pom.xml scripts .gitignore README.md CLOUD-SETUP.md
    git commit -m "Persist cloud data in PostgreSQL and add data import"
    git push

If tests fail, stop and share the error. Render's automatic deployment should
build the pushed commit. If automatic deployment is disabled, use Manual Deploy
for the latest commit. Startup fails clearly if DB settings are missing or the
database is unreachable. Health check remains /health.

## 3. Export local data and import it

Keep the local app stopped during export so the archive is consistent.

    powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Export-Data.ps1

This runs this project's export script for one process; it does not change the
machine's execution-policy setting. It creates shipping-data-TIMESTAMP.zip in
the project root. The ZIP is ignored by Git. Do not upload it to GitHub.

Open https://shipping-verifier.onrender.com/storage.html, sign in using your app
login, and check ready=true, storage=postgresql, files=0. Choose the exported ZIP
and click Import data. Keep the page open until it reports success.

Import accepts only inbox JSON, supported attachments, review/AI report JSON and
AI cache files, with bounded size and entry count. It requires at least one inbox
email. Missing attachment references are allowed deliberately so the verification
workflow can flag supplied missing-document examples. Duplicate/unsafe paths,
unsupported files and malformed JSON are rejected before database changes.
Import is transactional and only allowed when the database is empty. It never
replaces existing cloud data. It does not use Gemini or classify new emails.
Imported review identities remain self-reported and unverified.

Limits: ZIP 30 MB, expanded total 50 MB, individual file 10 MB, 5,000 entries.
If import reports an error, refresh status before retrying. If records exist but
the working copy failed, restart the service; PostgreSQL holds the saved data.

## 4. Verify deployment and persistence

Open / and check that your expected 520 emails appear. Open email_055 and check
its SI and BL previews and existing review history. Save a clearly labelled test
review if needed; this does not call Gemini. Download an audit report.
Download a database backup ZIP from /storage.html and keep it privately.
Restart the Render web service, then confirm email count and that same saved
review still appear. This is the real cloud persistence acceptance check.
Do not infer AI success from a saved result: check its timestamp and source.
Gemini quota remains a separate constraint.

## Prototype operating limits

Run ONE web-service instance. Working copies and API pacing are process-local;
this version is not suitable for multiple active replicas. A future version
should replace the working-copy adapter with direct repository queries and
coordinate rate limits across instances.

The shared app login permits data import/export as well as reviews. It is for
a controlled hackathon demo, not multi-user production access control. The
import is intentionally one-time. There is no remote deletion/replacement API.

Render Free Postgres expires 30 days after creation and has no automatic
backups. Download backups after important work, and retain original local data.
Free web-service sleeping does not remove the database records; they restore
when the application starts. The original project Docker image contains no data.

## Validation performed for this update

All application sources compiled against the project's Spring/Jackson APIs.
Storage tests exercised local writes, cloud write ordering, failure propagation,
transaction rollback, restart restoration, backup roundtrip and ZIP validation
using a JDBC test double. They are not a live PostgreSQL integration test.
Existing review tests were run against the injected storage adapter.
Live Render database connectivity, full Maven build on your Java installation,
and the restart test above remain deployment acceptance checks.

References:
- https://render.com/docs/postgresql-creating-connecting
- https://render.com/docs/free
- https://jdbc.postgresql.org/documentation/use/
