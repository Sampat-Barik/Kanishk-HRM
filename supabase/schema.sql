-- =============================================================================
-- Kanishk HRM - Production PostgreSQL Schema for Supabase
-- Execute in: Supabase Dashboard -> SQL Editor -> New Query -> Run
--
-- Target schema: public
-- Tables: 21 (all existing hr_* tables preserved in dependency order)
-- Safe for fresh Supabase databases and idempotent re-runs.
-- Enables Row Level Security (RLS) and revokes anon/authenticated Data API
-- privileges so HRM data is accessible only through the Spring Boot backend.
-- =============================================================================

begin;

-- 1. Identity & Organization
create table if not exists public.hr_role (
    id uuid primary key,
    name varchar(32) unique not null
);

create table if not exists public.hr_user (
    id uuid primary key,
    email varchar(254) unique not null,
    password_hash varchar(100) not null,
    status varchar(20) not null check (status in ('ACTIVE', 'DISABLED')),
    clerk_user_id varchar(128) unique
);

create table if not exists public.hr_user_role (
    user_id uuid not null references public.hr_user(id),
    role_id uuid not null references public.hr_role(id),
    primary key (user_id, role_id)
);

create table if not exists public.hr_department (
    id uuid primary key,
    name varchar(120) unique not null
);

create table if not exists public.hr_employee (
    id uuid primary key,
    user_id uuid not null unique references public.hr_user(id),
    employee_code varchar(64) unique not null,
    full_name varchar(180) not null,
    department_id uuid not null references public.hr_department(id),
    employment_status varchar(32) not null check (employment_status in ('ACTIVE', 'INACTIVE', 'TERMINATED')),
    hire_date date not null,
    position varchar(160),
    phone varchar(50),
    address varchar(500),
    emergency_contact varchar(250),
    location varchar(16) not null default 'ONSITE' check (location in ('ONSITE', 'REMOTE')),
    avatar_url varchar(2000)
);

-- 2. Authentication
create table if not exists public.hr_token (
    digest varchar(64) primary key,
    user_id uuid not null references public.hr_user(id),
    expires_at timestamp with time zone not null
);

-- 3. Attendance & Timesheets
create table if not exists public.hr_sheet (
    id uuid primary key,
    employee_id uuid not null references public.hr_employee(id),
    start_period date not null,
    end_period date not null,
    total_regular_hours numeric(12,4) not null check (total_regular_hours >= 0),
    overtime_hours numeric(12,4) not null check (overtime_hours >= 0),
    status varchar(20) not null,
    check (end_period >= start_period)
);

create table if not exists public.hr_session (
    id uuid primary key,
    employee_id uuid not null references public.hr_employee(id),
    timesheet_id uuid references public.hr_sheet(id),
    clock_in_at timestamp with time zone not null,
    clock_out_at timestamp with time zone,
    location varchar(16) not null check (location in ('ONSITE', 'REMOTE')),
    status varchar(20) not null,
    check (clock_out_at is null or clock_out_at >= clock_in_at)
);

-- 4. Leave & Payroll
create table if not exists public.hr_leave (
    id uuid primary key,
    employee_id uuid not null references public.hr_employee(id),
    type varchar(80) not null,
    start_date date not null,
    end_date date not null,
    reason varchar(2000),
    status varchar(20) not null,
    submitted_at timestamp with time zone not null,
    check (end_date >= start_date)
);

create table if not exists public.hr_payroll (
    id uuid primary key,
    timesheet_id uuid not null unique references public.hr_sheet(id),
    employee_id uuid not null references public.hr_employee(id),
    regular_hours numeric(12,4) not null check (regular_hours >= 0),
    overtime_hours numeric(12,4) not null check (overtime_hours >= 0),
    regular_rate bigint not null check (regular_rate >= 0),
    overtime_rate bigint not null check (overtime_rate >= 0),
    gross_pay bigint not null check (gross_pay >= 0),
    deductions bigint not null check (deductions >= 0 and deductions <= gross_pay),
    net_pay bigint not null check (net_pay >= 0),
    currency varchar(3) not null,
    status varchar(20) not null
);

-- 5. Employee Development
create table if not exists public.hr_goal (
    id uuid primary key,
    employee_id uuid not null references public.hr_employee(id),
    title varchar(200) not null,
    description varchar(2000),
    priority varchar(16) not null check (priority in ('LOW', 'MEDIUM', 'HIGH')),
    progress_percent int not null check (progress_percent between 0 and 100),
    due_date date not null
);

create table if not exists public.hr_module (
    id uuid primary key,
    title varchar(200) not null,
    description varchar(2000),
    duration_minutes int not null check (duration_minutes > 0),
    mandatory boolean not null
);

create table if not exists public.hr_enrollment (
    id uuid primary key,
    employee_id uuid not null references public.hr_employee(id),
    module_id uuid not null references public.hr_module(id),
    status varchar(24) not null,
    completion_percent int not null check (completion_percent between 0 and 100),
    completed_at timestamp with time zone,
    unique (employee_id, module_id)
);

-- 6. Meetings
create table if not exists public.hr_meeting (
    id uuid primary key,
    title varchar(200) not null,
    starts_at timestamp with time zone not null,
    duration_minutes int not null check (duration_minutes > 0),
    join_url varchar(2000) not null
);

create table if not exists public.hr_attendee (
    meeting_id uuid not null references public.hr_meeting(id),
    employee_id uuid not null references public.hr_employee(id),
    primary key (meeting_id, employee_id)
);

-- 7. Recruitment
create table if not exists public.hr_job (
    id uuid primary key,
    title varchar(200) not null,
    description varchar(2000),
    status varchar(24) not null,
    published_at timestamp with time zone
);

create table if not exists public.hr_application (
    id uuid primary key,
    job_id uuid not null references public.hr_job(id),
    candidate_name varchar(180) not null,
    email varchar(254) not null,
    stage varchar(24) not null,
    unique (job_id, email)
);

-- 8. Administration, Calendar & Auditing
create table if not exists public.hr_approval (
    id uuid primary key,
    entity_type varchar(24) not null,
    entity_id uuid not null,
    status varchar(24) not null,
    reviewer_id uuid not null references public.hr_user(id),
    actioned_at timestamp with time zone not null
);

create table if not exists public.hr_calendar (
    id uuid primary key,
    title varchar(180) not null,
    kind varchar(24) not null check (kind in ('HOLIDAY', 'EVENT')),
    starts_at timestamp with time zone not null,
    ends_at timestamp with time zone not null,
    location varchar(200),
    check (ends_at >= starts_at)
);

create table if not exists public.hr_office_hours (
    weekday integer primary key check (weekday between 1 and 7),
    closed boolean not null,
    opens_at time,
    closes_at time,
    check (
        (closed and opens_at is null and closes_at is null)
        or (not closed and opens_at is not null and closes_at is not null and closes_at > opens_at)
    )
);

create table if not exists public.hr_audit (
    id uuid primary key,
    actor_id uuid not null references public.hr_user(id),
    entity_type varchar(24) not null,
    entity_id uuid not null,
    action varchar(64) not null,
    created_at timestamp with time zone not null
);

-- Indexes for foreign keys and frequent query filters (non-redundant with PK/UNIQUE)
create index if not exists idx_hr_employee_department on public.hr_employee(department_id);
create index if not exists idx_hr_employee_status on public.hr_employee(employment_status);
create index if not exists idx_hr_token_user on public.hr_token(user_id);
create index if not exists idx_hr_session_employee on public.hr_session(employee_id);
create index if not exists idx_hr_session_timesheet on public.hr_session(timesheet_id);
create index if not exists idx_hr_sheet_employee on public.hr_sheet(employee_id);
create index if not exists idx_hr_leave_employee on public.hr_leave(employee_id);
create index if not exists idx_hr_leave_status on public.hr_leave(status);
create index if not exists idx_hr_payroll_employee on public.hr_payroll(employee_id);
create index if not exists idx_hr_goal_employee on public.hr_goal(employee_id);
create index if not exists idx_hr_enrollment_module on public.hr_enrollment(module_id);
create index if not exists idx_hr_attendee_employee on public.hr_attendee(employee_id);
create index if not exists idx_hr_approval_reviewer on public.hr_approval(reviewer_id);
create index if not exists idx_hr_audit_actor on public.hr_audit(actor_id);
create index if not exists idx_hr_audit_created on public.hr_audit(created_at);

-- =============================================================================
-- Supabase Security Hardening
-- Enable RLS on all 21 HRM tables (default-deny for PostgREST Data API) and
-- revoke direct access from PUBLIC, anon, and authenticated roles.
-- The Spring Boot backend connects via PostgreSQL JDBC (postgres / owner role)
-- and enforces its own authentication and role-based access control.
-- =============================================================================
do $$
declare
    tbl text;
    hrm_tables text[] := array[
        'hr_role', 'hr_user', 'hr_user_role', 'hr_department', 'hr_employee',
        'hr_token', 'hr_sheet', 'hr_session', 'hr_leave', 'hr_payroll',
        'hr_goal', 'hr_module', 'hr_enrollment', 'hr_meeting', 'hr_attendee',
        'hr_job', 'hr_application', 'hr_approval', 'hr_calendar',
        'hr_office_hours', 'hr_audit'
    ];
begin
    foreach tbl in array hrm_tables loop
        execute format('alter table public.%I enable row level security', tbl);
        execute format('revoke all on table public.%I from public', tbl);
        if exists (select 1 from pg_roles where rolname = 'anon') then
            execute format('revoke all on table public.%I from anon', tbl);
        end if;
        if exists (select 1 from pg_roles where rolname = 'authenticated') then
            execute format('revoke all on table public.%I from authenticated', tbl);
        end if;
    end loop;
end $$;

commit;
