-- =============================================================================
-- Kanishk HRM - Non-Destructive Migration for Existing Supabase Databases
-- Execute in Supabase Dashboard -> SQL Editor ONLY when upgrading an existing
-- HRM database that already has data. Never drops or truncates any table.
-- =============================================================================

begin;

-- 1. Add columns introduced after initial schema version
alter table public.hr_goal add column if not exists description varchar(2000);
alter table public.hr_employee add column if not exists avatar_url varchar(2000);
alter table public.hr_user add column if not exists clerk_user_id varchar(128) unique;

-- 2. Ensure hr_audit table exists
create table if not exists public.hr_audit (
    id uuid primary key,
    actor_id uuid not null references public.hr_user(id),
    entity_type varchar(24) not null,
    entity_id uuid not null,
    action varchar(64) not null,
    created_at timestamp with time zone not null
);

-- 3. Add missing indexes idempotently
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

-- 4. Add missing CHECK constraints safely (skipped if already present)
do $$
begin
    if not exists (select 1 from pg_constraint where conname = 'chk_hr_employee_status') then
        alter table public.hr_employee
            add constraint chk_hr_employee_status check (employment_status in ('ACTIVE', 'INACTIVE', 'TERMINATED'));
    end if;
    if not exists (select 1 from pg_constraint where conname = 'chk_hr_employee_location') then
        alter table public.hr_employee
            add constraint chk_hr_employee_location check (location in ('ONSITE', 'REMOTE'));
    end if;
    if not exists (select 1 from pg_constraint where conname = 'chk_hr_leave_dates') then
        alter table public.hr_leave
            add constraint chk_hr_leave_dates check (end_date >= start_date);
    end if;
    if not exists (select 1 from pg_constraint where conname = 'chk_hr_sheet_period') then
        alter table public.hr_sheet
            add constraint chk_hr_sheet_period check (end_period >= start_period and total_regular_hours >= 0 and overtime_hours >= 0);
    end if;
    if not exists (select 1 from pg_constraint where conname = 'chk_hr_enrollment_pct') then
        alter table public.hr_enrollment
            add constraint chk_hr_enrollment_pct check (completion_percent between 0 and 100);
    end if;
    if not exists (select 1 from pg_constraint where conname = 'chk_hr_meeting_duration') then
        alter table public.hr_meeting
            add constraint chk_hr_meeting_duration check (duration_minutes > 0);
    end if;
end $$;

-- 5. Enforce Supabase RLS & revoke anon/authenticated direct Data API access
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
