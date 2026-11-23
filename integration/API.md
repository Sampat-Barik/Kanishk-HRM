# HRM screen integration

All paths below follow `/api` (or the Supabase Edge Function base URL). IDs are UUID strings. Authentication is handled exclusively by Clerk: sign in via Clerk (`@clerk/clerk-js`) with HR-issued company email and password, then send the Clerk session JWT in `Authorization: Bearer <token>`. The backend verifies the RS256 JWT against Clerk's JWKS and matches the active HR-provisioned user in `hr_user`. Read the [schema](../src/main/resources/schema.sql) for every persisted field, reference and type. SQL column names become camelCase JSON fields, e.g. `completion_percent` becomes `completionPercent`.

| Screen or action | Requests and body fields |
| --- | --- |
| Session and dashboard | Clerk handles sign-in and sign-out (`POST /auth/login` is disabled). Call `GET /me` and `GET /dashboard` with a Clerk Bearer JWT. Dashboard includes today's seconds, sessions, goals, upcoming meetings and HR counts for admins. |
| Profile | PATCH /me/profile with fullName, phone, address, emergencyContact and/or avatarUrl. Company login emails are HR-managed. |
| Departments | GET /departments. HR can POST {name}. |
| Employees | HR GET or POST /employees. Create with email, password, fullName, employeeCode, departmentId, hireDate and optional role (EMPLOYEE or HR_ADMIN), position and contact fields. GET /employees/{id} for HR or self. HR PATCH /employees/{id} with profile fields, email, departmentId, position, role or employmentStatus (ACTIVE, INACTIVE, TERMINATED). HR POST /employees/{id}/reset-password {password} resets credentials and revokes active sessions. Disabling employment or changing roles revokes outstanding sessions. |
| Attendance | PATCH /me/location {location}, POST /time-sessions/clock-in with optional {location}, POST /time-sessions/{id}/clock-out. Values REMOTE or ONSITE. GET /time-sessions returns employee records or HR's full list. Filter by employeeId, date, or from/to for daily, weekly and monthly views. |
| Timesheets | POST /timesheets {startPeriod,endPeriod} submits closed, unsubmitted sessions. GET /timesheets. HR POST /timesheets/{id}/approve or /reject. GET /timesheets/{id}/export returns CSV. |
| Leave | POST /leave-requests {type,startDate,endDate,reason}. GET /leave-requests lists own requests. HR GET /leave-requests/all. Employee POST /leave-requests/{id}/cancel. HR POST /leave-requests/{id}/approve or /reject. Reviews and cancellation apply to PENDING requests. |
| Payroll | HR POST /payroll-records {timesheetId,regularRate,overtimeRate,deductions,currency}. HR POST /payroll-records/{id}/approve. GET /payroll-records for employee or HR (includes `employeeName`, `startPeriod`, `endPeriod`, `timesheetStatus`, `clockIns`, `clockOuts`). Filter by startPeriod, endPeriod and (for HR) employeeId. |
| Goals | POST /goals {title,priority,dueDate} and optional employeeId for HR. GET /goals. PATCH /goals/{id} {progressPercent}. Priorities LOW, MEDIUM, HIGH. |
| Training | GET /training-modules. HR POST /training-modules {title,description,durationMinutes,mandatory} or PATCH /training-modules/{id}. POST /training-modules/{id}/enroll for self, or HR with {employeeId}. GET /training-enrollments. POST /training-enrollments/{id}/start, PATCH /training-enrollments/{id} {completionPercent}, POST /training-enrollments/{id}/complete. HR POST /training-enrollments/{id}/approve. Calculate module completion rate from enrollments in COMPLETED or APPROVED status divided by total assigned. |
| Meetings | HR POST /meetings {title,startsAt,durationMinutes,joinUrl,attendeeIds}. GET /meetings lists attendee names and details for invited employees or all meetings for HR. GET /meetings/{id}/join returns {joinUrl}. |
| Office calendar | GET /calendar returns officeHours, events and meetings. HR POST /calendar/office-hours {weekday,closed,opensAt,closesAt}. Weekdays use Monday=1 to Sunday=7. Omit times when closed. HR POST /calendar/events {title,kind,startsAt,endsAt,location} with kind HOLIDAY or EVENT. |
| Recruitment | HR GET or POST /job-postings {title,description,status}. Status DRAFT or PUBLISHED. POST /job-postings/{id}/publish or /close. GET or POST /applications {jobId,candidateName,email}. PATCH /applications/{id} {stage}. Pipeline stages APPLIED, IN_REVIEW, INTERVIEW, OFFERED, HIRED, REJECTED. |
| Review history | HR GET /approvals returns entityType, entityId, status, reviewerId and actionedAt for reviewed leave, timesheets, payroll and training. |

## Time and pay conventions

### Screen fields and reports

- Employee profiles expose `online`, `location`, `clockStatus`, `currentClockInAt` and optional `avatarUrl`. Online means the account has an unexpired login session. Profile edits can set an HTTPS avatar URL or clear it with null. HR employee edits can also correct `employeeCode` and `hireDate`. The dashboard returns the signed-in employee's presence, location and clock state in `myStatus`.
- A goal accepts an optional `description` on creation and includes it in dashboard and goal responses. Training module responses include `completionRate` as a percentage of all assigned enrollments. A module with no enrollments returns zero.
- For weekly or monthly views, GET `/timesheets/report?from=2026-11-01&to=2026-11-30`. Dates are inclusive in the business timezone. The response includes sessions, per-session hours and approval state, total closed hours, approved hours, pending hours and the average closed hours per distinct worked date. Open sessions have null hours. HR can filter by `employeeId`. Employee requests are scoped to the caller.
- HR can GET `/audit-events` for a chronological history (newest first) of profile changes, leave submissions, review decisions, payroll calculations and recruitment decisions. The API appends the actor, action, target and timestamp in the same transaction as the change.

### Payroll calculation

Periods use inclusive ISO dates. An overnight session belongs to its clock-in business date for timesheet accounting. The dashboard counts only the elapsed portion of a session within today. A rejected timesheet releases its sessions for resubmission. Per-day regular and overtime totals are rounded to four decimal places. Inputs and outputs for money use integer minor units of the supplied currency (e.g. paise for INR). Gross pay equals regular hours × regular rate plus overtime hours × overtime rate, rounded once to the nearest minor unit. Net pay subtracts the supplied deductions. HR supplies the actual rates and deductions. A unique timesheet reference prevents duplicate payroll.

## UML conventions

The database maps User and Role through a many-to-many membership table. Each employee has a user and department. Work sessions, timesheets, leave, pay, goals and enrollments reference employees directly. Meeting attendees have a many-to-many join table. Applications reference their job posting. Each approval records one review target and the reviewer. Action timestamps use offsets. The model's duplicate empty PayrollRecord and unnamed editor placeholders (Port1, Attribute5, Operation3) have no specified business behavior. The implementation uses Meeting for Metting and Application for Applications.

HTTP 401 indicates a missing, expired or revoked session. HTTP 403 indicates a role or ownership restriction. HTTP 400 indicates invalid input. HTTP 404 indicates a missing record. HTTP 409 indicates a conflict such as overlapping leave, duplicate payroll or an already-reviewed request.
