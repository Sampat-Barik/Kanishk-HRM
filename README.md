# Kanishk HRM

HR Management portal and REST API for Kanishk Services (`https://sampat-barik.github.io/Kanishk-HRM/`).

System design diagrams live in `design/` ([class diagram](design/HRM_Class_Diagram.mdj), [class diagram image](design/HRM_Class_Diagram.jpg), [use case diagram](design/Use%20Case%20diagram.png)), and the API contract is documented in [integration/API.md](integration/API.md).

## Architecture

- **Authentication (100% Clerk)** — Public self-sign-up is disabled (`sign_up_mode: restricted`). Employees sign in using company-issued email and password credentials provisioned by HR. No local passwords or session tokens are stored in the database.
- **Database (Supabase PostgreSQL)** — 21 normalized `public.hr_*` tables (`supabase/schema.sql`) with Row Level Security enabled and `anon`/`authenticated` Data API access revoked. Every employee account in `hr_user` links directly to Clerk via `clerk_user_id`.
- **API** — Served via the Supabase Edge Function (`api`) for GitHub Pages and mirrored by the Spring Boot 2.7.18 service (`src/main/java/com/kanishk/hrm/`). Both verify Clerk RS256 JWTs against Clerk's JWKS endpoint and sync HR account operations (create user, reset password, update email/role, deactivate/ban, session revocation) directly to the Clerk Backend API.
- **Frontend** — Static single-page app (`src/main/resources/static/hrm/`) using `@clerk/clerk-js@6` headless email/password sign-in.

## Implemented Modules

- **Identity & Access Control** (`Security.java`, `Accounts.java`) — Clerk RS256 JWKS verification, strict HR-provisioned account check, role-based access (`HR_ADMIN`, `EMPLOYEE`), HR-managed account creation, password resets, role updates, and instant session revocation when an employee is deactivated.
- **Employees & Departments** (`Accounts.java`) — Department creation/listing, employee directory, self-service profile updates (with HR-locked company login emails), and live clock/location status.
- **Attendance & Time Sessions** (`Attendance.java`) — Clock-in/clock-out (blocks multiple open sessions), remote/onsite location toggle, and filtering by `employeeId`, `date`, or `from`/`to`.
- **Timesheets & Reports** (`Attendance.java`) — Submit closed sessions for a period, regular/overtime split based on `DAILY_REGULAR_HOURS`, HR approve/reject (rejection releases sessions for resubmission), period report endpoint, and CSV export.
- **Leave Requests** (`Leave.java`) — Submit leave with overlap validation against pending/approved dates, employee cancel, and HR approve/reject.
- **Payroll** (`Attendance.java`) — Generate payroll from approved timesheets (minor currency units, regular/overtime rates, deductions), duplicate payroll prevention per timesheet, period/employee filtering, and HR approval.
- **Goals & Training** (`Development.java`) — Employee goals with progress updates, training modules with completion rates, enrollments (no duplicates), start/complete flow, and HR approval.
- **Meetings & Calendar** (`Office.java`) — Schedule meetings with attendees, restricted join links, office hours, and holidays/events.
- **Recruitment** (`Recruitment.java`) — HR-only job postings (`DRAFT`, `PUBLISHED`, `CLOSED`) and candidate applications with stage tracking and duplicate `(job_id, email)` protection.
- **Dashboard, Approvals & Audit Log** (`Dashboard.java`, `Store.java`) — Employee/HR dashboard metrics, approval records (`/api/approvals`), and audit history (`/api/audit-events`).

## Folder Structure

```text
Backend/
├── design/                     # StarUML model and exported diagrams
├── integration/
│   ├── API.md                  # API contract for frontend integration
│   └── hrm-client.mjs          # Fetch helper using Clerk Bearer tokens
├── supabase/
│   ├── schema.sql              # Full 21-table PostgreSQL schema + RLS hardening
│   └── migrate_existing.sql    # Non-destructive upgrade script
├── src/
│   ├── main/java/com/kanishk/hrm/
│   ├── main/resources/
│   │   ├── application.yml
│   │   ├── application-prod.yml
│   │   ├── schema.sql
│   │   └── static/hrm/         # Production web UI served on GitHub Pages
│   ├── test/                   # JUnit 5 workflow & schema tests (signed RS256 JWTs)
│   └── postgres-test/          # Embedded PostgreSQL tests
├── .env.example
├── render.yaml
└── pom.xml
```

## Running the Spring Boot Backend

Make sure JDK 11+ is on your `PATH`, then copy `.env.example` to `.env` and fill in your Supabase PostgreSQL and Clerk credentials:

```powershell
Copy-Item .env.example .env
.\mvnw.cmd clean package
java -jar target\hrm-backend-0.0.1-SNAPSHOT.jar
```

- Web UI: `http://127.0.0.1:8080/hrm/`
- API base: `http://127.0.0.1:8080/api`
- Health check: `GET /api/health`

## Tests

```powershell
# Run workflow and schema/migration tests
.\mvnw.cmd test

# Run tests against embedded PostgreSQL and package the JAR
.\mvnw.cmd -Ppostgres-test clean package
```

## Environment Variables

| Variable | Purpose | Default |
| --- | --- | --- |
| `DB_URL` | Supabase / PostgreSQL JDBC URL | `jdbc:postgresql://localhost:5432/postgres?sslmode=require` |
| `DB_USERNAME` | PostgreSQL user | `postgres` |
| `DB_PASSWORD` | PostgreSQL password | *(required)* |
| `CLERK_ISSUER` | Clerk Frontend API issuer URL for JWKS verification | `https://positive-caiman-7190.clerk.accounts.dev` |
| `CLERK_SECRET_KEY` | Clerk Backend API secret key for HR user provisioning/resets | *(required for HR account writes)* |
| `HRM_FRONTEND_ORIGIN` | Allowed CORS origins (comma-separated) | `https://sampat-barik.github.io` |
| `HRM_ZONE` | Timezone for attendance and reports | `Asia/Kolkata` |
| `DAILY_REGULAR_HOURS` | Daily regular hours limit (`0 < x <= 24`) | `8` |
| `BIND_ADDRESS` | Server bind address | `127.0.0.1` |
| `PORT` | Server port | `8080` |

## Deployment

- **GitHub Pages** serves `src/main/resources/static/hrm/` via `.github/workflows/deploy-pages.yml` and connects to the live Supabase Edge Function (`api`) configured in `config.js`.
- **Supabase Edge Function Secrets**: Add `CLERK_SECRET_KEY` in **Supabase Dashboard -> Project Settings -> Edge Functions -> Secrets** so HR account creation, password resets, email updates, and deactivation sync directly to Clerk.
- **Render (optional Spring Boot host)**: [Dockerfile](Dockerfile) and [render.yaml](render.yaml) are ready if you want to host the Spring Boot service directly against Supabase PostgreSQL.
