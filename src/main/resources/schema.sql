create table if not exists hr_role (
    id uuid primary key,
    name varchar(32) unique not null
);

create table if not exists hr_user (
    id uuid primary key,
    email varchar(254) unique not null,
    password_hash varchar(100) not null,
    status varchar(20) not null
);

create table if not exists hr_user_role (
    user_id uuid references hr_user(id) not null,
    role_id uuid references hr_role(id) not null,
    primary key(user_id,role_id)
);

create table if not exists hr_department (
    id uuid primary key,
    name varchar(120) unique not null
);

create table if not exists hr_employee (
    id uuid primary key,
    user_id uuid references hr_user(id) not null unique,
    employee_code varchar(64) unique not null,
    full_name varchar(180) not null,
    department_id uuid references hr_department(id) not null,
    employment_status varchar(32) not null,
    hire_date date not null,
    position varchar(160),
    phone varchar(50),
    address varchar(500),
    emergency_contact varchar(250),
    location varchar(16) not null default 'ONSITE'
);

create table if not exists hr_token (
    digest varchar(64) primary key,
    user_id uuid references hr_user(id) not null,
    expires_at timestamp with time zone not null
);

create table if not exists hr_sheet (
    id uuid primary key,
    employee_id uuid references hr_employee(id) not null,
    start_period date not null,
    end_period date not null,
    total_regular_hours numeric(12,4) not null,
    overtime_hours numeric(12,4) not null,
    status varchar(20) not null
);

create table if not exists hr_session (
    id uuid primary key,
    employee_id uuid references hr_employee(id) not null,
    timesheet_id uuid references hr_sheet(id),
    clock_in_at timestamp with time zone not null,
    clock_out_at timestamp with time zone,
    location varchar(16) not null,
    status varchar(20) not null
);

create table if not exists hr_leave (
    id uuid primary key,
    employee_id uuid references hr_employee(id) not null,
    type varchar(80) not null,
    start_date date not null,
    end_date date not null,
    reason varchar(2000),
    status varchar(20) not null,
    submitted_at timestamp with time zone not null
);

create table if not exists hr_payroll (
    id uuid primary key,
    timesheet_id uuid references hr_sheet(id) not null unique,
    employee_id uuid references hr_employee(id) not null,
    regular_hours numeric(12,4) not null,
    overtime_hours numeric(12,4) not null,
    regular_rate bigint not null,
    overtime_rate bigint not null,
    gross_pay bigint not null,
    deductions bigint not null,
    net_pay bigint not null,
    currency varchar(3) not null,
    status varchar(20) not null
);

create table if not exists hr_goal (
    id uuid primary key,
    employee_id uuid references hr_employee(id) not null,
    title varchar(200) not null,
    priority varchar(16) not null,
    progress_percent int not null check(progress_percent between 0 and 100),
    due_date date not null
);

create table if not exists hr_module (
    id uuid primary key,
    title varchar(200) not null,
    description varchar(2000),
    duration_minutes int not null,
    mandatory boolean not null
);

create table if not exists hr_enrollment (
    id uuid primary key,
    employee_id uuid references hr_employee(id) not null,
    module_id uuid references hr_module(id) not null,
    status varchar(24) not null,
    completion_percent int not null,
    completed_at timestamp with time zone,
    unique(employee_id,module_id)
);

create table if not exists hr_meeting (
    id uuid primary key,
    title varchar(200) not null,
    starts_at timestamp with time zone not null,
    duration_minutes int not null,
    join_url varchar(2000) not null
);

create table if not exists hr_attendee (
    meeting_id uuid references hr_meeting(id) not null,
    employee_id uuid references hr_employee(id) not null,
    primary key(meeting_id,employee_id)
);

create table if not exists hr_job (
    id uuid primary key,
    title varchar(200) not null,
    description varchar(2000),
    status varchar(24) not null,
    published_at timestamp with time zone
);

create table if not exists hr_application (
    id uuid primary key,
    job_id uuid references hr_job(id) not null,
    candidate_name varchar(180) not null,
    email varchar(254) not null,
    stage varchar(24) not null,
    unique(job_id,email)
);

create table if not exists hr_approval (
    id uuid primary key,
    entity_type varchar(24) not null,
    entity_id uuid not null,
    status varchar(24) not null,
    reviewer_id uuid references hr_user(id) not null,
    actioned_at timestamp with time zone not null
);

create table if not exists hr_calendar (
    id uuid primary key,
    title varchar(180) not null,
    kind varchar(24) not null,
    starts_at timestamp with time zone not null,
    ends_at timestamp with time zone not null,
    location varchar(200)
);

create table if not exists hr_office_hours (
    weekday integer primary key check (weekday between 1 and 7),
    closed boolean not null,
    opens_at time,
    closes_at time
);

alter table hr_goal add column if not exists description varchar(2000);
alter table hr_employee add column if not exists avatar_url varchar(2000);

create table if not exists hr_audit (
    id uuid primary key,
    actor_id uuid not null references hr_user(id),
    entity_type varchar(24) not null,
    entity_id uuid not null,
    action varchar(64) not null,
    created_at timestamp with time zone not null
);
