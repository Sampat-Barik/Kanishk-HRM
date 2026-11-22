import {createHrmClient} from "./hrm-client.mjs";

const api = createHrmClient("/api");
const $ = selector => document.querySelector(selector);
const id = name => document.getElementById(name);
const get = path => api.request(path);
const save = (path, body, method = "POST") => api.request(path, {method, body});
const visible = (element, show) => element.classList.toggle("hidden", !show);
const dateText = value => value ? new Date(value).toLocaleString() : "—";
let me, screen, revision = 0;
const admin = () => me?.role === "HR_ADMIN";

function node(tag, text, css) {
    const el = document.createElement(tag);
    if (text != null) el.textContent = text;
    if (css) el.className = css;
    return el;
}
function button(label, handler) {
    const el = node("button", label, "btn btn--secondary btn--sm");
    el.type = "button";
    bind(el, handler);
    return el;
}
function signedOut(message = "") {
    revision++;
    me = null;
    id("editor").close();
    id("leaveForm").reset();
    visible(id("leaveRequestForm"), false);
    visible(id("loginScreen"), true);
    visible(id("appContainer"), false);
    id("loginMessage").textContent = message;
}
function bind(el, handler, event = "click") {
    el.addEventListener(event, async e => {
        e.preventDefault();
        if (el.dataset.busy) return;
        el.dataset.busy = "yes";
        try {
            id(me ? "message" : "loginMessage").textContent = "";
            await handler();
        } catch (error) {
            if (me && error.status === 401) signedOut("Your session expired. Sign in again.");
            else {
                const target = id("editor").open ? id("editorError") : id(me ? "message" : "loginMessage");
                target.textContent = error.message || "Request failed.";
            }
        } finally { delete el.dataset.busy; }
    });
}
function card(title, lines, actions = []) {
    const body = node("div", null, "card__body");
    body.append(node("h4", title), ...lines.filter(Boolean).map(line => node("p", line)), ...actions);
    const container = node("article", null, "card");
    container.append(body);
    return container;
}
function list(selector, items, render, empty = "No records yet.") {
    $(selector).replaceChildren(...(items.length ? items.map(render) : [node("p", empty)]));
}
function box(css, ...children) {
    const element = node("div", null, css);
    element.append(...children);
    return element;
}
function status(value) {
    const state = String(value).toUpperCase();
    const tone = ["APPROVED", "PUBLISHED", "ACTIVE"].includes(state) ? "success" : state === "REJECTED" ? "error" : "warning";
    return node("span", state.toLowerCase(), "status status--" + tone);
}
function detail(label, value) {
    const line = node("p");
    line.append(node("strong", label + ": "), document.createTextNode(value == null || value === "" ? "—" : String(value)));
    return line;
}
function localTime(value) {
    return value ? new Date(value).toLocaleTimeString("en-US", {hour: "2-digit", minute: "2-digit"}) : "—";
}
function day(value) {
    return new Date(value + "T12:00:00").toLocaleDateString("en-US", {weekday: "long", year: "numeric", month: "long", day: "numeric"});
}
function metric(label, value, kind = "summary") {
    if (kind === "stat") return box("stat-item", node("span", value, "stat-number"), node("span", label, "stat-label"));
    if (kind === "tracking") return box("tracking-item", node("span", label, "label"), node("span", value, "value"));
    if (kind === "payroll") return box("payroll-item", node("span", label, "label"), node("span", value, "value"));
    return box("summary-item", node("span", value, "summary-value"), node("span", label, "summary-label"));
}
// Each field is [API key, label, input type or select options, initial value, optional].
function editor(title, fields, submit) {
    const form = id("editorForm");
    form.replaceChildren(node("h3", title));
    const inputs = [];
    for (const [key, label, type = "text", value = "", optional = false] of fields) {
        const wrapper = node("label", label, "form-group");
        const input = node(Array.isArray(type) ? "select" : "input", null, "form-control");
        if (Array.isArray(type)) {
            for (const [v, name] of type) input.add(new Option(name, v));
        } else input.type = type;
        if (value != null && value !== "") input.value = value;
        input.required = !optional;
        input.name = key;
        wrapper.append(input);
        form.append(wrapper);
        inputs.push(input);
    }
    const error = node("p");
    error.id = "editorError";
    error.setAttribute("role", "alert");
    form.append(error, node("button", "Save", "btn btn--primary"), button("Cancel", () => id("editor").close()));
    form.onsubmit = async e => {
        e.preventDefault();
        if (form.dataset.busy) return;
        form.dataset.busy = "yes";
        try {
            await submit(Object.fromEntries(inputs.filter(i => i.value !== "").map(i => [i.name, i.value])));
            id("editor").close();
            await openScreen(screen);
        } catch (err) {
            if (err.status === 401) signedOut("Your session expired. Sign in again.");
            else error.textContent = err.message;
        } finally { delete form.dataset.busy; }
    };
    id("editor").showModal();
}
function action(label, path, body) {
    return button(label, async () => { await save(path, body); await openScreen(screen); });
}
async function clock() {
    const dashboard = await get("/dashboard");
    const session = dashboard.sessions.find(s => !s.clockOutAt);
    await save(session ? "/time-sessions/" + session.id + "/clock-out" : "/time-sessions/clock-in");
    await openScreen(screen);
}
async function openScreen(name) {
    if (!admin() && ["employees","recruitment"].includes(name)) return;
    const request = ++revision;
    screen = name;
    document.querySelectorAll(".content-section").forEach(el => el.classList.remove("active"));
    document.querySelectorAll(".nav-link").forEach(el => el.classList.toggle("active", el.dataset.section === name));
    const draw = await views[name]();
    if (request !== revision) return;
    draw();
    id(name + "Section").classList.add("active");
}
const goalCard = g => box("goal-item",
    box("goal-header", node("h5", g.title, "goal-title"), node("span", g.priority.toLowerCase(), "goal-priority " + g.priority.toLowerCase())),
    node("p", g.description, "goal-description"),
    box("goal-progress", box("goal-progress-bar", (() => { const fill=node("div", null, "goal-progress-fill"); fill.style.width=g.progressPercent+"%"; return fill; })()),
        box("goal-meta", node("span", "Progress: " + g.progressPercent + "%"), node("span", "Due: " + g.dueDate))),
    box("goal-actions", button("Update Progress", () => editor("Update Goal Progress", [["progressPercent","Progress (0–100)","number",g.progressPercent]],
        fields => save("/goals/" + g.id, fields, "PATCH")))
    ));
const meetingCard = m => {
    const body = box("card__body", box("meeting-header", node("h4",m.title), node("span",dateText(m.startsAt),"meeting-time")),
        detail("Duration",m.durationMinutes+" min"), detail("Attendees",m.attendees?.map(a=>a.fullName).join(", ") || me.fullName));
    body.append(meetingLink(m.joinUrl));
    const container = node("article",null,"card"); container.append(body); return container;
};
function meetingLink(url) {
    const link = node("a","Join Meeting", "btn btn--primary btn--sm");
    if (url?.startsWith("https://")) link.href = url;
    link.rel = "noreferrer"; link.target = "_blank";
    return link;
}
const views = {
    async dashboard() {
        const d = await get("/dashboard");
        return () => {
            $(".current-date").textContent = "Today is " + day(d.date);
            id("workLocation").textContent = d.myStatus.location === "REMOTE" ? "Remote" : "On-site";
            id("clockStatus").textContent = d.myStatus.clockStatus === "CLOCKED_IN" ? "Clocked In" : "Not Clocked In";
            id("clockInOutBtn").textContent = d.myStatus.clockStatus === "CLOCKED_IN" ? "Clock Out" : "Clock In";
            list("#employeeGoals", d.goals, goalCard);
            list("#upcomingMeetings", d.upcomingMeetings, meetingCard, "No upcoming meetings");
            visible(id("employeeDashboard"), !admin());
            visible(id("hrDashboard"), admin());
            if (admin()) {
                const labels = [["Leave Requests", "leave"], ["Payroll Approvals", "payroll"], ["Training Completions", "training"], ["Timesheet Approvals", "timesheets"]];
                $(".approval-summary").replaceChildren(...labels.map(([label,key]) => box("", detail(label, d.pending[key]))));
                const locations = Object.fromEntries(d.byLocation.map(row => [row.location, row.total]));
                $(".employee-summary").replaceChildren(detail("Total Employees", d.employees), detail("Online", d.online),
                    detail("On-site", locations.ONSITE || 0), detail("Remote", locations.REMOTE || 0));
            }
        };
    },
    async employees() {
        const rows = await get("/employees");
        return () => list("#employeesList", rows, r => {
            const header = box("employee-header");
            if (r.avatarUrl) { const avatar=node("img",null,"employee-avatar"); avatar.src=r.avatarUrl; avatar.alt=r.fullName; header.append(avatar); }
            header.append(box("employee-info",node("h4",r.fullName),node("p",r.position || "Employee")));
            const body = box("card__body",header,box("employee-details",detail("Department",r.department),detail("Email",r.email),detail("Status",r.online?"online":"offline"),detail("Location",r.location === "REMOTE" ? "Remote" : "On-site"),detail("Clock Status",r.clockStatus === "CLOCKED_IN" ? "Clocked In" : "Not Clocked In")));
            const container=node("article",null,"card employee-card"); container.append(body); return container;
        });
    },
    async timetracking() {
        const d = await get("/dashboard");
        return () => {
            const open = d.sessions.find(s => !s.clockOutAt);
            id("clockInBtn").disabled = !!open;
            id("clockOutBtn").disabled = !open;
            id("todayDate").textContent = day(d.date);
            id("todayClockIn").textContent = localTime(open?.clockInAt || d.sessions[0]?.clockInAt);
            id("todayHours").textContent = (d.todaySeconds / 3600).toFixed(2) + " hours";
            id("todayLocation").textContent = d.myStatus.location === "REMOTE" ? "Remote" : "On-site";
            id("remoteBtn").classList.toggle("active",d.myStatus.location === "REMOTE");
            id("onsiteBtn").classList.toggle("active",d.myStatus.location === "ONSITE");
        };
    },
    async leave() {
        const rows = await get(admin() ? "/leave-requests/all" : "/leave-requests");
        return () => list("#leaveRequestsList",rows,r => {
            const decisions = r.status !== "PENDING" ? [] : admin() ? ["approve","reject"] : ["cancel"];
            const body=box("card__body",box("request-header",node("h4",(r.employee || me.fullName)+" - "+r.type),status(r.status)),
                box("request-details",detail("Start",r.startDate),detail("End",r.endDate),detail("Submitted",String(r.submittedAt || "").slice(0,10))),detail("Reason",r.reason));
            if (decisions.length) body.append(box("request-actions",...decisions.map(k => action(k[0].toUpperCase()+k.slice(1), "/leave-requests/" + r.id + "/" + k))));
            const container=node("article",null,"card request-card"); container.append(body); return container;
        });
    },
    async timesheet() {
        const d = await get("/dashboard");
        const range = period(d.date);
        const [report,sheets] = await Promise.all([get("/timesheets/report?from=" + range.from + "&to=" + range.to),get("/timesheets")]);
        return () => {
            id("timesheetSummary").replaceChildren(metric("Total Hours",report.totalHours), metric("Approved Hours",report.approvedHours),
                metric("Pending Hours",report.pendingHours), metric("Avg Hours/Day",report.averageHoursPerDay),
                action("Submit my timesheet","/timesheets",{startPeriod:range.from,endPeriod:range.to}));
            const header = box("time-record-header", ...["Date","Clock In","Clock Out","Hours","Location","Status","Actions"].map(value => node("span",value)));
            const rows = report.records.map(r => {
                const line=box("time-record",node("span",String(r.clockInAt).slice(0,10)),node("span",localTime(r.clockInAt)),node("span",localTime(r.clockOutAt)),node("span",r.hours == null ? "—" : r.hours+"h"),node("span",r.location === "REMOTE" ? "remote" : "on-site"),status(r.approvalStatus),node("span","-"));
                return line;
            });
            id("timesheetRecords").replaceChildren(header,...(rows.length ? rows : [node("p","No time records found.")]));
            for (const s of sheets.filter(s => s.startPeriod <= range.to && s.endPeriod >= range.from)) {
                const actions = [button("Download CSV",async () => download(await get("/timesheets/" + s.id + "/export")))];
                if (admin() && s.status === "PENDING") actions.push(...["approve","reject"].map(k => action(k,"/timesheets/" + s.id + "/" + k)));
                id("timesheetRecords").append(card("Timesheet · " + s.status,[s.startPeriod + " to " + s.endPeriod],actions));
            }
            id("exportTimesheetBtn").onclick = () => download(["Clock in,Clock out,Location,Hours,Status",...report.records.map(r =>
                [r.clockInAt,r.clockOutAt,r.location,r.hours,r.approvalStatus].join(","))].join("\r\n"));
        };
    },
    async payroll() {
        const rows = await get("/payroll-records");
        return () => {
            const select = id("payPeriodSelect"), prior = select.value;
            const periodName = r => r.startPeriod + " to " + r.endPeriod;
            const periods = [...new Set(rows.map(periodName))];
            select.replaceChildren(new Option("All periods",""),...periods.map(p => new Option(p,p)));
            if (periods.includes(prior)) select.value = prior;
            list("#payrollData",rows.filter(r => !select.value || periodName(r) === select.value),r => {
                const currency = new Intl.NumberFormat(undefined,{style:"currency",currency:r.currency});
                const money = n => currency.format(n / 10 ** currency.resolvedOptions().maximumFractionDigits);
                const body=box("card__body",box("payroll-header",node("h4",r.employeeName || me.fullName),status(r.status)),
                    detail("Pay Period",periodName(r)),box("payroll-tracking",metric("Clock Ins",r.clockIns,"tracking"),metric("Clock Outs",r.clockOuts,"tracking"),metric("Timesheet",String(r.timesheetStatus).toLowerCase(),"tracking")),
                    box("payroll-details",metric("Regular Hours",r.regularHours,"payroll"),metric("Overtime",r.overtimeHours,"payroll"),metric("Gross Pay",money(r.grossPay),"payroll"),metric("Net Pay",money(r.netPay),"payroll")));
                if (admin() && r.status === "PENDING") body.append(action("Approve","/payroll-records/" + r.id + "/approve"));
                const container=node("article",null,"card payroll-card payroll-enhanced"); container.append(body); return container;
            });
        };
    },
    async calendar() {
        const d = await get("/calendar");
        return () => {
            const days = ["Monday","Tuesday","Wednesday","Thursday","Friday","Saturday","Sunday"];
            list("#officeHours",d.officeHours,r => box("office-hour-item",node("span",days[r.weekday-1],"day-name"),node("span",r.closed ? "Closed" : r.opensAt + " - " + r.closesAt,"hours")));
            for (const [selector,kind] of [["#companyHolidays","HOLIDAY"],["#specialEvents","EVENT"]])
                list(selector,d.events.filter(e => e.kind === kind),e => {
                    const body=box("",box(kind === "HOLIDAY" ? "holiday-header" : "event-header",node("h4",e.title,kind === "HOLIDAY" ? "holiday-name" : "event-name"),status(e.kind)),node("p",dateText(e.startsAt),kind === "HOLIDAY" ? "holiday-date" : "event-date"));
                    if (e.location) body.append(detail("Location",e.location));
                    const container=node("article",null,kind === "HOLIDAY" ? "holiday-item" : "event-item"); container.append(body); return container;
                });
        };
    },
    async training() {
        const [modules,enrollments] = await Promise.all([get("/training-modules"),get("/training-enrollments")]);
        return () => list("#trainingModules",modules,m => {
            const mine = enrollments.find(e => e.moduleId === m.id && e.employeeId === me.id);
            const base = "/training-enrollments/";
            const actions = [];
            if (!mine) actions.push(action("Enroll","/training-modules/" + m.id + "/enroll"));
            if (mine?.status === "ASSIGNED") actions.push(action("Start",base + mine.id + "/start"));
            if (mine?.status === "IN_PROGRESS") actions.push(action("Mark complete",base + mine.id + "/complete"));
            if (admin()) for (const e of enrollments.filter(e => e.moduleId === m.id && e.status === "COMPLETED"))
                actions.push(action("Approve " + e.employeeId,base + e.id + "/approve"));
            const fill=node("div",null,"progress-fill"); fill.style.width=m.completionRate+"%";
            const body=box("card__body",box("training-header",node("h4",m.title),m.mandatory ? node("span","Mandatory","training-badge") : node("span")),node("p",m.description),detail("Duration",m.durationMinutes+" minutes"),box("training-progress",box("progress-bar",fill),node("p","Completion Rate: "+m.completionRate+"%")),...actions);
            const container=node("article",null,"card training-card"); container.append(body); return container;
        });
    },
    async meetings() {
        const rows = await get("/meetings");
        return () => list("#meetingsList", rows, meetingCard);
    },
    async recruitment() {
        const [jobs,applications] = await Promise.all([get("/job-postings"),get("/applications")]);
        return () => {
            const jobsList=box("job-listings",...jobs.map(j => box("job-item",node("h4",j.title),node("p","Applications: "+j.applicationCount),status(j.status))));
            const pipeline=box("pipeline-stats",metric("Total Applications",applications.length,"stat"),metric("In Review",applications.filter(a=>a.stage === "IN_REVIEW").length,"stat"),metric("Interviews Scheduled",applications.filter(a=>a.stage === "INTERVIEW").length,"stat"));
            $(".recruitment-content").replaceChildren(card("Active Job Postings",[],[jobsList]),card("Recruitment Pipeline",[],[pipeline]));
        };
    }
};

function period(date) {
    const start = new Date(date + "T00:00:00Z");
    const previous = id("timesheetPeriod").value === "previous";
    const monthly = id("timesheetView").value === "monthly";
    if (monthly) { start.setUTCDate(1); if (previous) start.setUTCMonth(start.getUTCMonth()-1); }
    else start.setUTCDate(start.getUTCDate() - (start.getUTCDay()+6)%7 - (previous ? 7 : 0));
    const end = new Date(start);
    if (monthly) end.setUTCMonth(end.getUTCMonth()+1,0);
    else end.setUTCDate(end.getUTCDate()+6);
    return {from:start.toISOString().slice(0,10),to:end.toISOString().slice(0,10)};
}
function download(csv) {
    const url = URL.createObjectURL(new Blob([csv],{type:"text/csv"}));
    const a = node("a");
    a.href = url; a.download = "timesheet.csv"; a.click();
    setTimeout(() => URL.revokeObjectURL(url),1000);
}
bind(id("loginForm"),async () => {
    await api.login(id("email").value,id("password").value);
    id("password").value = "";
    me = await get("/me");
    id("userInfo").textContent = me.fullName + " · " + (admin() ? "HR Admin" : "Employee");
    document.body.classList.toggle("show-hr-only",admin());
    document.querySelectorAll(".hr-only").forEach(el => visible(el,admin()));
    id("message").textContent = "";
    visible(id("loginScreen"),false); visible(id("appContainer"),true);
    await openScreen("dashboard");
},"submit");
function demoLogin(email, password) {
    id("email").value = email;
    id("password").value = password;
    id("loginForm").requestSubmit();
}
bind(id("demoEmployee"),() => demoLogin("employee@hrm.local", "Employee@123"));
bind(id("demoAdmin"),() => demoLogin("admin@hrm.local", "Admin@123"));
bind(id("logoutBtn"),async () => { await api.logout(); signedOut(); });
document.querySelectorAll("[data-section],[data-open]").forEach(el => bind(el,() => openScreen(el.dataset.section || el.dataset.open)));
for (const name of ["clockInOutBtn","clockInBtn","clockOutBtn"]) bind(id(name),clock);
for (const [name,location] of [["remoteBtn","REMOTE"],["onsiteBtn","ONSITE"]])
    bind(id(name),async () => { await save("/me/location",{location},"PATCH"); await openScreen(screen); });
setInterval(() => { id("currentTime").textContent = new Date().toLocaleTimeString(); },1000);
for (const name of ["timesheetView","timesheetPeriod","payPeriodSelect"]) bind(id(name),() => {
    const unit = id("timesheetView").value === "weekly" ? "Week" : "Month";
    id("timesheetPeriod").options[0].text = "Current " + unit;
    id("timesheetPeriod").options[1].text = "Previous " + unit;
    return openScreen(screen);
},"change");
bind(id("newLeaveRequestBtn"),() => visible(id("leaveRequestForm"),true));
bind(id("cancelLeaveForm"),() => visible(id("leaveRequestForm"),false));
bind(id("leaveForm"),async () => {
    await save("/leave-requests",{type:id("leaveType").value,startDate:id("startDate").value,endDate:id("endDate").value,reason:id("leaveReason").value});
    id("leaveForm").reset(); visible(id("leaveRequestForm"),false); await openScreen("leave");
},"submit");
bind(id("editProfileBtn"),() => editor("Edit profile",[
    ["fullName","Full name","text",me.fullName],["email","Email","email",me.email],
    ...[["phone","Phone"],["address","Address"],["emergencyContact","Emergency contact"]].map(([k,l]) => [k,l,"text",me[k],true])
],async f => { me = await save("/me/profile",f,"PATCH"); id("userInfo").textContent = me.fullName + " · " + me.role; }));
bind($("#employeesSection .section-header button"),async () => {
    const depts = await get("/departments");
    editor("Add employee",[["fullName","Full name"],["email","Email","email"],["password","Initial password","password"],
        ["employeeCode","Employee code"],["departmentId","Department",depts.map(d => [d.id,d.name])],["hireDate","Hire date","date"]],
        f => save("/employees",f));
});
bind(id("scheduleMeetingBtn"),async () => {
    const staff = await get("/employees");
    editor("Schedule meeting",[["title","Title"],["startsAt","Start (local time)","datetime-local"],["durationMinutes","Duration in minutes","number",30],
        ["joinUrl","HTTPS meeting link","url"],["attendee","Invite employee",staff.map(e => [e.id,e.fullName])]],
        ({attendee,startsAt,...rest}) => save("/meetings",{...rest,startsAt:new Date(startsAt).toISOString(),attendeeIds:[attendee]}));
});
bind($("#recruitmentSection .section-header button"),() => editor("Post new job",[["title","Title"],["description","Description"]],f => save("/job-postings",f)));
