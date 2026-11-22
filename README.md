# Kanishk HRM Backend

Backend REST API for the Kanishk HRM application (`https://kanishkservices.com/hrm`).

The system design diagrams are in `design/` ([class diagram](design/HRM_Class_Diagram.mdj), [class diagram image](design/HRM_Class_Diagram.jpg), [use case diagram](design/Use%20Case%20diagram.png)), and the endpoint reference for the frontend team is in [integration/API.md](integration/API.md).

## Tech Stack

- Java 11, Spring Boot 2.7.18
- Spring Security (stateless Bearer auth, BCrypt password hashing)
- Spring JDBC (`JdbcTemplate`)
- PostgreSQL (production) and H2 (local demo/tests)
- Maven Wrapper (`mvnw` / `mvnw.cmd`), JUnit 5

## Implemented Modules

- **Auth & Users** (`Accounts.java`, `Security.java`) — Login with 8-hour Bearer tokens (stored as SHA-256 hashes), logout, role-based access (`HR_ADMIN`, `EMPLOYEE`), and automatic token revocation when an employee is deactivated.
- **Employees & Departments** (`Accounts.java`) — Department creation/listing, employee CRUD, self-service profile updates, and live online/clock status.
- **Attendance & Time Sessions** (`Attendance.java`) — Clock-in/clock-out (blocks multiple open sessions), remote/onsite location toggle, and filtering by `employeeId`, `date`, or `from`/`to`.
- **Timesheets & Reports** (`Attendance.java`) — Submit closed sessions for a period, regular/overtime split based on `DAILY_REGULAR_HOURS`, HR approve/reject (rejection releases sessions for resubmission), period report endpoint, and CSV export.
- **Leave Requests** (`Leave.java`) — Submit leave with overlap validation against pending/approved dates, employee cancel, and HR approve/reject.
- **Payroll** (`Attendance.java`) — Generate payroll from approved timesheets (minor currency units, regular/overtime rates, deductions), duplicate payroll prevention per timesheet, period/employee filtering, and HR approval.
- **Goals & Training** (`Development.java`) — Employee goals with progress updates, training modules with completion rates, enrollments (no duplicates), start/progress/complete flow, and HR approval.
- **Meetings & Calendar** (`Office.java`) — Schedule meetings with attendees, restricted join links, office hours, and holidays/events.
- **Recruitment** (`Recruitment.java`) — HR-only job postings (`DRAFT`, `PUBLISHED`, `CLOSED`) and candidate applications with stage tracking and duplicate `(job_id, email)` protection.
- **Dashboard, Approvals & Audit Log** (`Dashboard.java`, `Store.java`) — Employee/HR dashboard metrics, approval records (`/api/approvals`), and audit history (`/api/audit-events`).

## Folder Structure

```text
Backend/
├── design/                     # StarUML model and exported diagrams
├── integration/
│   ├── API.md                  # API contract for frontend integration
│   └── hrm-client.mjs          # Helper fetch client for the browser
├── src/
│   ├── main/java/com/kanishk/hrm/
│   ├── main/resources/
│   │   ├── application.yml
│   │   ├── application-demo.yml
│   │   ├── schema.sql          # Shared schema for H2 and PostgreSQL
│   │   └── static/hrm/         # Local test UI for quick demoing
│   ├── test/                   # H2 integration tests
│   └── postgres-test/          # Embedded PostgreSQL tests
├── .env.example
└── pom.xml
```

## Running Locally

Make sure JDK 11+ is on your `PATH`.

```powershell
.\mvnw.cmd clean package
java -jar target\hrm-backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=demo
```

- Local test UI: `http://127.0.0.1:8080/hrm/`
- API base: `http://127.0.0.1:8080/api`
- Demo DB file: `data/demo.mv.db` (ignored by Git)

Demo accounts (only seeded when `--spring.profiles.active=demo` is active):

| Role | Email | Password |
| --- | --- | --- |
| HR Admin | `admin@hrm.local` | `Admin@123` |
| Employee | `employee@hrm.local` | `Employee@123` |

Quick check in PowerShell:

```powershell
$api = 'http://127.0.0.1:8080/api'
$login = Invoke-RestMethod "$api/auth/login" -Method Post -ContentType application/json -Body '{"email":"employee@hrm.local","password":"Employee@123"}'
$headers = @{ Authorization = "Bearer " + $login.accessToken }
Invoke-RestMethod "$api/me" -Headers $headers
Invoke-RestMethod "$api/dashboard" -Headers $headers
```

## Tests

```powershell
# Run tests against H2
.\mvnw.cmd test

# Run tests against H2 + embedded PostgreSQL and build the JAR
.\mvnw.cmd -Ppostgres-test clean package
```

## Environment Variables

Copy [.env.example](.env.example) and set values for your server environment. Do not use the `demo` profile in production.

| Variable | Purpose | Default |
| --- | --- | --- |
| `DB_URL` | PostgreSQL JDBC URL (`jdbc:postgresql://HOST:5432/DB`) | `jdbc:h2:file:./data/hrm;...` |
| `DB_USERNAME` | Database user | `sa` |
| `DB_PASSWORD` | Database password | *(empty)* |
| `HRM_ADMIN_EMAIL` | Initial HR Admin email on first startup | *(required outside demo)* |
| `HRM_ADMIN_PASSWORD` | Initial HR Admin password (min 12 chars) | *(required outside demo)* |
| `HRM_FRONTEND_ORIGIN` | Allowed CORS origin (e.g. `https://kanishkservices.com`) | *(empty)* |
| `HRM_ZONE` | Timezone for attendance and reports | `Asia/Kolkata` |
| `DAILY_REGULAR_HOURS` | Daily regular hours limit (`0 < x <= 24`) | `8` |
| `BIND_ADDRESS` | Server bind address | `127.0.0.1` |
| `PORT` | Server port | `8080` |

## Frontend Handoff

The production frontend at `https://kanishkservices.com/hrm` is handled separately. To connect it:

- Use the endpoints and payload formats in [integration/API.md](integration/API.md).
- `integration/hrm-client.mjs` can be used directly in the frontend or as a reference for Bearer auth and 401 handling.
- Route `/api/*` to this service via reverse proxy (same-origin), or host the API on a separate HTTPS domain and set `HRM_FRONTEND_ORIGIN=https://kanishkservices.com`.

## Pending / Before Production Deployment

- [ ] Provision PostgreSQL database and set `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` (tables auto-create on first startup from `schema.sql`).
- [ ] Set `HRM_ADMIN_EMAIL` and `HRM_ADMIN_PASSWORD` (12+ chars) for initial admin seeding.
- [ ] Configure HTTPS reverse proxy (Nginx/Apache/Cloudflare) forwarding `/api/*` to `127.0.0.1:8080`, or set `HRM_FRONTEND_ORIGIN` if hosted on a separate subdomain.
- [ ] Connect the hosted frontend (`https://kanishkservices.com/hrm`) to the live `/api` endpoints.
- [ ] Confirm `HRM_ZONE` and `DAILY_REGULAR_HOURS` defaults with HR.
- [ ] Set up production process management (systemd/Docker) and DB backups.
- [ ] Future backlog (not in current scope): password reset / email notifications, token refresh, and Flyway/Liquibase migrations for future schema changes.
