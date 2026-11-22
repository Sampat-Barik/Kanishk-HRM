// Kanishk HRM Frontend - Connected to Spring Boot REST API
const API_BASE = window.HRM_API_BASE || (window.location.protocol === 'file:' ? 'http://127.0.0.1:8080/api' : '/api');

// Application State
let accessToken = null;
let currentUser = null;
let isHRUser = false;
let currentSection = 'dashboard';
let currentDashboard = null;
let currentGoalId = null;
let currentTrainingEnrollment = null;
let currentTrainingModule = null;
let lastTimesheetReport = null;
let actionModalHandler = null;

// API Helper
async function apiRequest(path, { method = 'GET', body } = {}) {
    const headers = {};
    if (accessToken) headers['Authorization'] = 'Bearer ' + accessToken;
    if (body !== undefined) headers['Content-Type'] = 'application/json';

    const response = await fetch(API_BASE.replace(/\/$/, '') + path, {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body)
    });

    if (!response.ok) {
        const detail = await response.json().catch(() => ({}));
        const message = detail.message || detail.error || response.statusText || 'Request failed';
        if (response.status === 401 && currentUser) {
            signOutLocal('Your session expired. Please sign in again.');
        }
        const err = new Error(message);
        err.status = response.status;
        throw err;
    }

    if (response.status === 204) return null;
    const contentType = response.headers.get('content-type') || '';
    return contentType.includes('application/json') ? response.json() : response.text();
}

function escapeHtml(value) {
    if (value == null) return '';
    return String(value)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

function setNotice(message = '', isSuccess = false) {
    const el = document.getElementById('appNotice');
    if (!el) return;
    el.textContent = message;
    el.style.color = isSuccess ? 'var(--color-success, #21808d)' : 'var(--color-error, #c0152f)';
}

function setLoginError(message = '') {
    const el = document.getElementById('loginError');
    if (el) el.textContent = message;
}

function formatDate(isoDate) {
    if (!isoDate) return '—';
    const d = new Date(isoDate.length === 10 ? isoDate + 'T12:00:00' : isoDate);
    return Number.isNaN(d.getTime()) ? isoDate : d.toLocaleDateString('en-US', {
        weekday: 'long', year: 'numeric', month: 'long', day: 'numeric'
    });
}

function formatShortDate(iso) {
    if (!iso) return '—';
    const d = new Date(iso.length === 10 ? iso + 'T12:00:00' : iso);
    return Number.isNaN(d.getTime()) ? iso : d.toLocaleDateString('en-US', {
        year: 'numeric', month: 'short', day: 'numeric'
    });
}

function formatTime(iso) {
    if (!iso) return '—';
    const d = new Date(iso);
    return Number.isNaN(d.getTime()) ? iso : d.toLocaleTimeString('en-US', {
        hour: '2-digit', minute: '2-digit', hour12: true
    });
}

function formatDateTime(iso) {
    if (!iso) return '—';
    const d = new Date(iso);
    return Number.isNaN(d.getTime()) ? iso : d.toLocaleString('en-US', {
        month: 'short', day: 'numeric', year: 'numeric', hour: '2-digit', minute: '2-digit'
    });
}

function formatMoney(minorUnits, currencyCode) {
    try {
        const fmt = new Intl.NumberFormat(undefined, { style: 'currency', currency: currencyCode || 'INR' });
        const digits = fmt.resolvedOptions().maximumFractionDigits;
        return fmt.format((minorUnits || 0) / Math.pow(10, digits));
    } catch (_) {
        return `${currencyCode || ''} ${(minorUnits || 0) / 100}`;
    }
}

function statusClass(status) {
    const s = String(status || '').toUpperCase();
    if (['APPROVED', 'PUBLISHED', 'ACTIVE', 'HIRED', 'COMPLETED'].includes(s)) return 'status--success';
    if (['REJECTED', 'CANCELLED', 'CLOSED', 'TERMINATED', 'INACTIVE'].includes(s)) return 'status--error';
    return 'status--warning';
}

function getPeriodRange(anchorDate) {
    const viewEl = document.getElementById('timesheetView');
    const periodEl = document.getElementById('timesheetPeriod');
    const monthly = viewEl && viewEl.value === 'monthly';
    const previous = periodEl && periodEl.value === 'previous';

    const base = anchorDate ? new Date(anchorDate + 'T00:00:00Z') : new Date();
    const start = new Date(Date.UTC(base.getUTCFullYear(), base.getUTCMonth(), base.getUTCDate()));

    if (monthly) {
        start.setUTCDate(1);
        if (previous) start.setUTCMonth(start.getUTCMonth() - 1);
    } else {
        const offsetToMonday = (start.getUTCDay() + 6) % 7;
        start.setUTCDate(start.getUTCDate() - offsetToMonday - (previous ? 7 : 0));
    }

    const end = new Date(start.getTime());
    if (monthly) {
        end.setUTCMonth(end.getUTCMonth() + 1, 0);
    } else {
        end.setUTCDate(end.getUTCDate() + 6);
    }

    return {
        from: start.toISOString().slice(0, 10),
        to: end.toISOString().slice(0, 10)
    };
}

function downloadCsv(filename, csvContent) {
    const blob = new Blob([csvContent], { type: 'text/csv;charset=utf-8' });
    const url = window.URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => window.URL.revokeObjectURL(url), 1000);
}

// Generic Action Modal
function openActionModal(title, fields, onSubmit) {
    const modal = document.getElementById('actionModal');
    const titleEl = document.getElementById('actionModalTitle');
    const form = document.getElementById('actionModalForm');
    const errEl = document.getElementById('actionModalError');
    if (!modal || !form) return;

    titleEl.textContent = title;
    errEl.textContent = '';
    form.innerHTML = '';

    for (const field of fields) {
        const group = document.createElement('div');
        group.className = 'form-group';

        const label = document.createElement('label');
        label.className = 'form-label';
        label.textContent = field.label;
        label.htmlFor = 'action_field_' + field.name;
        group.appendChild(label);

        let input;
        if (field.type === 'select') {
            input = document.createElement('select');
            input.className = 'form-control';
            if (field.multiple) input.multiple = true;
            for (const opt of field.options || []) {
                const option = document.createElement('option');
                option.value = opt.value;
                option.textContent = opt.label;
                if (field.value !== undefined && String(field.value) === String(opt.value)) option.selected = true;
                input.appendChild(option);
            }
        } else if (field.type === 'textarea') {
            input = document.createElement('textarea');
            input.className = 'form-control';
            input.rows = 3;
            if (field.value != null) input.value = field.value;
        } else if (field.type === 'checkbox') {
            input = document.createElement('input');
            input.type = 'checkbox';
            input.checked = Boolean(field.value);
            input.style.marginLeft = '8px';
        } else {
            input = document.createElement('input');
            input.type = field.type || 'text';
            input.className = 'form-control';
            if (field.value != null) input.value = field.value;
            if (field.placeholder) input.placeholder = field.placeholder;
            if (field.min !== undefined) input.min = field.min;
            if (field.max !== undefined) input.max = field.max;
        }

        input.id = 'action_field_' + field.name;
        input.name = field.name;
        if (field.required) input.required = true;
        group.appendChild(input);
        form.appendChild(group);
    }

    actionModalHandler = async (e) => {
        e.preventDefault();
        errEl.textContent = '';
        const values = {};
        for (const field of fields) {
            const el = form.elements[field.name];
            if (!el) continue;
            if (field.type === 'checkbox') {
                values[field.name] = el.checked;
            } else if (field.type === 'select' && field.multiple) {
                values[field.name] = Array.from(el.selectedOptions).map(o => o.value);
            } else if (field.type === 'number') {
                if (el.value !== '') values[field.name] = Number(el.value);
            } else {
                if (el.value !== '' || field.allowEmpty) values[field.name] = el.value;
            }
        }
        try {
            await onSubmit(values);
            closeModal();
        } catch (err) {
            errEl.textContent = err.message || 'Action failed.';
        }
    };

    modal.classList.remove('hidden');
}

// Global Functions (kept for inline onclick attributes in HTML)
window.loginAs = async function (role) {
    const email = role === 'hr' ? 'admin@hrm.local' : 'employee@hrm.local';
    const password = role === 'hr' ? 'Admin@123' : 'Employee@123';
    const emailInput = document.getElementById('email');
    const passwordInput = document.getElementById('password');
    if (emailInput) emailInput.value = email;
    if (passwordInput) passwordInput.value = password;
    await performLogin(email, password);
};

window.showSection = async function (sectionName) {
    if (!isHRUser && ['employees', 'recruitment'].includes(sectionName)) return;
    currentSection = sectionName;
    setNotice('');

    document.querySelectorAll('.content-section').forEach(section => {
        section.classList.remove('active');
    });
    const targetSection = document.getElementById(sectionName + 'Section');
    if (targetSection) targetSection.classList.add('active');

    document.querySelectorAll('.nav-link').forEach(link => {
        link.classList.toggle('active', link.dataset.section === sectionName);
    });

    try {
        switch (sectionName) {
            case 'dashboard': await loadDashboard(); break;
            case 'employees': await loadEmployees(); break;
            case 'timesheet': await loadTimesheetSection(); break;
            case 'timetracking': await loadTimeTracking(); break;
            case 'leave': await loadLeaveRequests(); break;
            case 'payroll': await loadPayroll(); break;
            case 'calendar': await loadCalendar(); break;
            case 'training': await loadTraining(); break;
            case 'meetings': await loadMeetings(); break;
            case 'recruitment': await loadRecruitment(); break;
        }
    } catch (err) {
        setNotice(err.message);
    }
};

// Initialize when DOM is loaded
document.addEventListener('DOMContentLoaded', () => {
    setupEventListeners();
    updateCurrentTime();
    setInterval(updateCurrentTime, 1000);
});

function setupEventListeners() {
    const loginForm = document.getElementById('loginForm');
    if (loginForm) {
        loginForm.addEventListener('submit', async (e) => {
            e.preventDefault();
            const email = document.getElementById('email').value.trim();
            const password = document.getElementById('password').value;
            await performLogin(email, password);
        });
    }

    const logoutBtn = document.getElementById('logoutBtn');
    if (logoutBtn) logoutBtn.addEventListener('click', handleLogout);

    const editProfileBtn = document.getElementById('editProfileBtn');
    if (editProfileBtn) editProfileBtn.addEventListener('click', openProfileModal);

    const saveProfileBtn = document.getElementById('saveProfileBtn');
    if (saveProfileBtn) saveProfileBtn.addEventListener('click', saveProfile);

    document.querySelectorAll('.nav-link').forEach(link => {
        link.addEventListener('click', (e) => {
            e.preventDefault();
            if (link.dataset.section) window.showSection(link.dataset.section);
        });
    });

    // Clock & location controls
    const clockInOutBtn = document.getElementById('clockInOutBtn');
    const clockInBtn = document.getElementById('clockInBtn');
    const clockOutBtn = document.getElementById('clockOutBtn');
    if (clockInOutBtn) clockInOutBtn.addEventListener('click', toggleClockInOut);
    if (clockInBtn) clockInBtn.addEventListener('click', clockIn);
    if (clockOutBtn) clockOutBtn.addEventListener('click', clockOut);

    const remoteBtn = document.getElementById('remoteBtn');
    const onsiteBtn = document.getElementById('onsiteBtn');
    if (remoteBtn) remoteBtn.addEventListener('click', () => setWorkLocation('REMOTE'));
    if (onsiteBtn) onsiteBtn.addEventListener('click', () => setWorkLocation('ONSITE'));

    // Leave controls
    const newRequestBtn = document.getElementById('newLeaveRequestBtn');
    const cancelLeaveBtn = document.getElementById('cancelLeaveForm');
    const leaveForm = document.getElementById('leaveForm');
    if (newRequestBtn) newRequestBtn.addEventListener('click', () => {
        document.getElementById('leaveRequestForm').classList.remove('hidden');
    });
    if (cancelLeaveBtn) cancelLeaveBtn.addEventListener('click', hideLeaveRequestForm);
    if (leaveForm) leaveForm.addEventListener('submit', submitLeaveRequest);

    // Timesheet controls
    const timesheetView = document.getElementById('timesheetView');
    const timesheetPeriod = document.getElementById('timesheetPeriod');
    const exportBtn = document.getElementById('exportTimesheetBtn');
    if (timesheetView) timesheetView.addEventListener('change', () => {
        const unit = timesheetView.value === 'monthly' ? 'Month' : 'Week';
        if (timesheetPeriod) {
            timesheetPeriod.options[0].textContent = 'Current ' + unit;
            timesheetPeriod.options[1].textContent = 'Previous ' + unit;
        }
        loadTimesheetSection().catch(err => setNotice(err.message));
    });
    if (timesheetPeriod) timesheetPeriod.addEventListener('change', () => {
        loadTimesheetSection().catch(err => setNotice(err.message));
    });
    if (exportBtn) exportBtn.addEventListener('click', exportCurrentTimesheetReport);

    // Payroll period filter & calculation
    const payPeriodSelect = document.getElementById('payPeriodSelect');
    if (payPeriodSelect) payPeriodSelect.addEventListener('change', () => {
        loadPayroll().catch(err => setNotice(err.message));
    });
    const calcPayrollBtn = document.getElementById('calculatePayrollBtn');
    if (calcPayrollBtn) calcPayrollBtn.addEventListener('click', openCalculatePayrollModal);

    // Goals
    const addGoalBtn = document.getElementById('addGoalBtn');
    if (addGoalBtn) addGoalBtn.addEventListener('click', openAddGoalModal);
    const goalProgress = document.getElementById('goalProgress');
    const progressValue = document.getElementById('progressValue');
    const goalStatus = document.getElementById('goalStatus');
    const updateGoalBtn = document.getElementById('updateGoalBtn');
    if (goalProgress && progressValue) {
        goalProgress.addEventListener('input', () => {
            const val = Number(goalProgress.value);
            progressValue.textContent = val;
            if (goalStatus) {
                goalStatus.value = val === 100 ? 'completed' : val > 0 ? 'in-progress' : 'pending';
            }
        });
    }
    if (goalStatus && goalProgress && progressValue) {
        goalStatus.addEventListener('change', () => {
            if (goalStatus.value === 'completed') {
                goalProgress.value = 100;
                progressValue.textContent = '100';
            } else if (goalStatus.value === 'pending') {
                goalProgress.value = 0;
                progressValue.textContent = '0';
            }
        });
    }
    if (updateGoalBtn) updateGoalBtn.addEventListener('click', saveGoalUpdate);

    // Training modal & add module
    const completeTrainingBtn = document.getElementById('completeTrainingBtn');
    if (completeTrainingBtn) completeTrainingBtn.addEventListener('click', completeActiveTraining);
    const addTrainingBtn = document.getElementById('addTrainingBtn');
    if (addTrainingBtn) addTrainingBtn.addEventListener('click', openAddTrainingModal);

    // Employees & departments
    const addEmployeeBtn = document.getElementById('addEmployeeBtn');
    if (addEmployeeBtn) addEmployeeBtn.addEventListener('click', openAddEmployeeModal);
    const addDepartmentBtn = document.getElementById('addDepartmentBtn');
    if (addDepartmentBtn) addDepartmentBtn.addEventListener('click', openAddDepartmentModal);

    // Calendar & meetings
    const setOfficeHoursBtn = document.getElementById('setOfficeHoursBtn');
    if (setOfficeHoursBtn) setOfficeHoursBtn.addEventListener('click', openOfficeHoursModal);
    const addCalendarEventBtn = document.getElementById('addCalendarEventBtn');
    if (addCalendarEventBtn) addCalendarEventBtn.addEventListener('click', openCalendarEventModal);
    const scheduleMeetingBtn = document.getElementById('scheduleMeetingBtn');
    if (scheduleMeetingBtn) scheduleMeetingBtn.addEventListener('click', openScheduleMeetingModal);

    // Recruitment
    const postJobBtn = document.getElementById('postJobBtn');
    if (postJobBtn) postJobBtn.addEventListener('click', openPostJobModal);
    const addApplicationBtn = document.getElementById('addApplicationBtn');
    if (addApplicationBtn) addApplicationBtn.addEventListener('click', openAddApplicationModal);

    // Generic action modal form submit
    const actionModalForm = document.getElementById('actionModalForm');
    if (actionModalForm) {
        actionModalForm.addEventListener('submit', (e) => {
            if (actionModalHandler) actionModalHandler(e);
        });
    }

    // Modal close buttons
    document.querySelectorAll('.modal-close').forEach(btn => {
        btn.addEventListener('click', closeModal);
    });
}

// Authentication
async function performLogin(email, password) {
    setLoginError('');
    try {
        const result = await apiRequest('/auth/login', {
            method: 'POST',
            body: { email, password }
        });
        accessToken = result.accessToken;
        currentUser = await apiRequest('/me');
        isHRUser = currentUser.role === 'HR_ADMIN';

        const passwordInput = document.getElementById('password');
        if (passwordInput) passwordInput.value = '';

        showMainApp();
    } catch (err) {
        setLoginError(err.message || 'Invalid email or password.');
    }
}

async function handleLogout() {
    try {
        if (accessToken) await apiRequest('/auth/logout', { method: 'POST' });
    } catch (_) {
        // Ignore network/401 errors on logout
    }
    signOutLocal('');
}

function signOutLocal(message) {
    accessToken = null;
    currentUser = null;
    isHRUser = false;
    currentDashboard = null;
    closeModal();
    setNotice('');
    setLoginError(message || '');

    document.body.classList.remove('show-hr-only');
    const loginScreen = document.getElementById('loginScreen');
    const appContainer = document.getElementById('appContainer');
    if (loginScreen) loginScreen.classList.remove('hidden');
    if (appContainer) appContainer.classList.add('hidden');

    const emailInput = document.getElementById('email');
    const passwordInput = document.getElementById('password');
    if (emailInput) emailInput.value = '';
    if (passwordInput) passwordInput.value = '';
}

function showMainApp() {
    const loginScreen = document.getElementById('loginScreen');
    const appContainer = document.getElementById('appContainer');
    if (loginScreen) loginScreen.classList.add('hidden');
    if (appContainer) appContainer.classList.remove('hidden');

    updateHeaderUserInfo();

    document.body.classList.toggle('show-hr-only', isHRUser);
    document.querySelectorAll('.hr-only').forEach(el => {
        el.classList.toggle('hidden', !isHRUser);
    });

    const hrDashboard = document.getElementById('hrDashboard');
    const employeeDashboard = document.getElementById('employeeDashboard');
    if (hrDashboard) hrDashboard.classList.toggle('hidden', !isHRUser);
    if (employeeDashboard) employeeDashboard.classList.toggle('hidden', isHRUser);

    window.showSection('dashboard');
}

function updateHeaderUserInfo() {
    const userInfo = document.getElementById('userInfo');
    if (userInfo && currentUser) {
        const label = currentUser.position || (isHRUser ? 'HR Admin' : 'Employee');
        userInfo.textContent = `${currentUser.fullName} (${label})`;
    }
}

// Profile Management
function openProfileModal() {
    if (!currentUser) return;
    document.getElementById('profileName').value = currentUser.fullName || '';
    document.getElementById('profileEmail').value = currentUser.email || '';
    document.getElementById('profilePhone').value = currentUser.phone || '';
    document.getElementById('profileAddress').value = currentUser.address || '';
    document.getElementById('profileEmergency').value = currentUser.emergencyContact || '';
    document.getElementById('profileModal').classList.remove('hidden');
}

async function saveProfile() {
    if (!currentUser) return;
    try {
        const body = {
            fullName: document.getElementById('profileName').value.trim(),
            email: document.getElementById('profileEmail').value.trim(),
            phone: document.getElementById('profilePhone').value.trim(),
            address: document.getElementById('profileAddress').value.trim(),
            emergencyContact: document.getElementById('profileEmergency').value.trim()
        };
        currentUser = await apiRequest('/me/profile', { method: 'PATCH', body });
        updateHeaderUserInfo();
        closeModal();
        setNotice('Profile updated successfully.', true);
    } catch (err) {
        setNotice(err.message);
    }
}

// Dashboard
async function loadDashboard() {
    const d = await apiRequest('/dashboard');
    currentDashboard = d;

    const dateEl = document.querySelector('.current-date');
    if (dateEl) dateEl.textContent = 'Today is ' + formatDate(d.date);

    // Employee Status & Clock
    const statusEl = document.querySelector('#employeeStatus .status');
    if (statusEl) {
        statusEl.textContent = d.myStatus.online ? 'Online' : 'Offline';
        statusEl.className = 'status ' + (d.myStatus.online ? 'status--success' : 'status--error');
    }
    const workLocationEl = document.getElementById('workLocation');
    if (workLocationEl) workLocationEl.textContent = d.myStatus.location === 'REMOTE' ? 'Remote' : 'On-site';

    const clockedIn = d.myStatus.clockStatus === 'CLOCKED_IN';
    const clockStatusEl = document.getElementById('clockStatus');
    if (clockStatusEl) clockStatusEl.textContent = clockedIn ? 'Clocked In' : 'Not Clocked In';

    const clockInOutBtn = document.getElementById('clockInOutBtn');
    if (clockInOutBtn) {
        clockInOutBtn.textContent = clockedIn ? 'Clock Out' : 'Clock In';
        clockInOutBtn.classList.toggle('btn--primary', !clockedIn);
        clockInOutBtn.classList.toggle('btn--secondary', clockedIn);
    }

    // HR Summary
    if (isHRUser && d.pending) {
        const approvalSummary = document.getElementById('hrApprovalSummary');
        if (approvalSummary) {
            approvalSummary.innerHTML = `
                <p>Leave Requests: <span class="badge">${d.pending.leave || 0}</span></p>
                <p>Payroll Approvals: <span class="badge">${d.pending.payroll || 0}</span></p>
                <p>Training Completions: <span class="badge">${d.pending.training || 0}</span></p>
                <p>Timesheet Approvals: <span class="badge">${d.pending.timesheets || 0}</span></p>
            `;
        }
        const byLoc = Object.fromEntries((d.byLocation || []).map(row => [row.location, row.total]));
        const empSummary = document.getElementById('hrEmployeeSummary');
        if (empSummary) {
            empSummary.innerHTML = `
                <p>Total Employees: <span class="highlight">${d.employees || 0}</span></p>
                <p>Online: <span class="status status--success">${d.online || 0}</span></p>
                <p>On-site: <span class="highlight">${byLoc.ONSITE || 0}</span></p>
                <p>Remote: <span class="highlight">${byLoc.REMOTE || 0}</span></p>
            `;
        }
    }

    renderGoals(d.goals || []);
    renderUpcomingMeetings(d.upcomingMeetings || []);
}

function renderGoals(goals) {
    const container = document.getElementById('employeeGoals');
    if (!container) return;

    if (!goals.length) {
        container.innerHTML = '<p>No goals assigned yet.</p>';
        return;
    }

    container.innerHTML = goals.map(g => {
        const prio = String(g.priority || 'MEDIUM').toLowerCase();
        return `
            <div class="goal-item">
                <div class="goal-header">
                    <h5 class="goal-title">${escapeHtml(g.title)}</h5>
                    <span class="goal-priority ${escapeHtml(prio)}">${escapeHtml(prio)}</span>
                </div>
                ${g.description ? `<p class="goal-description">${escapeHtml(g.description)}</p>` : ''}
                <div class="goal-progress">
                    <div class="goal-progress-bar">
                        <div class="goal-progress-fill" style="width: ${Number(g.progressPercent) || 0}%"></div>
                    </div>
                    <div class="goal-meta">
                        <span>Progress: ${Number(g.progressPercent) || 0}%</span>
                        <span>Due: ${escapeHtml(formatShortDate(g.dueDate))}</span>
                    </div>
                </div>
                <div class="goal-actions">
                    <button class="btn btn--outline btn--sm" onclick="updateGoal('${escapeHtml(g.id)}')">Update Progress</button>
                </div>
            </div>
        `;
    }).join('');
}

window.updateGoal = function (goalId) {
    const goal = (currentDashboard?.goals || []).find(g => g.id === goalId);
    if (!goal) return;
    currentGoalId = goalId;

    const pct = Number(goal.progressPercent) || 0;
    document.getElementById('goalModalTitle').textContent = `Update Goal: ${goal.title}`;
    document.getElementById('goalProgress').value = pct;
    document.getElementById('progressValue').textContent = pct;
    document.getElementById('goalStatus').value = pct === 100 ? 'completed' : pct > 0 ? 'in-progress' : 'pending';
    document.getElementById('goalModal').classList.remove('hidden');
};

async function saveGoalUpdate() {
    if (!currentGoalId) return;
    try {
        const progressPercent = parseInt(document.getElementById('goalProgress').value, 10);
        await apiRequest(`/goals/${currentGoalId}`, {
            method: 'PATCH',
            body: { progressPercent }
        });
        closeModal();
        await loadDashboard();
        setNotice('Goal progress updated.', true);
    } catch (err) {
        setNotice(err.message);
    }
}

function openAddGoalModal() {
    openActionModal('Add Goal', [
        { name: 'title', label: 'Goal Title', required: true },
        { name: 'description', label: 'Description', type: 'textarea' },
        {
            name: 'priority', label: 'Priority', type: 'select', value: 'MEDIUM', required: true,
            options: [
                { value: 'LOW', label: 'Low' },
                { value: 'MEDIUM', label: 'Medium' },
                { value: 'HIGH', label: 'High' }
            ]
        },
        { name: 'dueDate', label: 'Due Date', type: 'date', required: true }
    ], async (values) => {
        await apiRequest('/goals', { method: 'POST', body: values });
        await loadDashboard();
        setNotice('Goal created.', true);
    });
}

function renderUpcomingMeetings(meetings) {
    const el = document.getElementById('upcomingMeetings');
    if (!el) return;
    if (!meetings.length) {
        el.innerHTML = '<p>No upcoming meetings</p>';
        return;
    }
    el.innerHTML = meetings.slice(0, 5).map(m => `
        <div class="meeting-item">
            <div class="meeting-header">
                <h4>${escapeHtml(m.title)}</h4>
                <span class="meeting-time">${escapeHtml(formatDateTime(m.startsAt))}</span>
            </div>
            <p class="meeting-attendees">Duration: ${Number(m.durationMinutes) || 0} min</p>
            <button class="btn btn--primary btn--sm" onclick="joinMeeting('${escapeHtml(m.id)}')">Join Meeting</button>
        </div>
    `).join('');
}

// Time Tracking & Clock
function updateCurrentTime() {
    const now = new Date();
    const currentTimeEl = document.getElementById('currentTime');
    const todayDateEl = document.getElementById('todayDate');
    if (currentTimeEl) {
        currentTimeEl.textContent = now.toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit', hour12: true });
    }
    if (todayDateEl) {
        todayDateEl.textContent = now.toLocaleDateString('en-US', { weekday: 'long', year: 'numeric', month: 'long', day: 'numeric' });
    }
}

async function loadTimeTracking() {
    const d = await apiRequest('/dashboard');
    currentDashboard = d;

    const open = (d.sessions || []).find(s => !s.clockOutAt);
    const clockInBtn = document.getElementById('clockInBtn');
    const clockOutBtn = document.getElementById('clockOutBtn');
    if (clockInBtn) clockInBtn.disabled = Boolean(open);
    if (clockOutBtn) clockOutBtn.disabled = !open;

    const todayClockIn = document.getElementById('todayClockIn');
    const todayHours = document.getElementById('todayHours');
    const todayLocation = document.getElementById('todayLocation');
    if (todayClockIn) todayClockIn.textContent = open ? formatTime(open.clockInAt) : (d.sessions?.[0] ? formatTime(d.sessions[0].clockInAt) : '--:--');
    if (todayHours) todayHours.textContent = ((d.todaySeconds || 0) / 3600).toFixed(2) + ' hrs';
    if (todayLocation) todayLocation.textContent = d.myStatus.location === 'REMOTE' ? 'Remote' : 'On-site';

    const remoteBtn = document.getElementById('remoteBtn');
    const onsiteBtn = document.getElementById('onsiteBtn');
    if (remoteBtn) remoteBtn.classList.toggle('active', d.myStatus.location === 'REMOTE');
    if (onsiteBtn) onsiteBtn.classList.toggle('active', d.myStatus.location === 'ONSITE');
}

async function toggleClockInOut() {
    const d = await apiRequest('/dashboard');
    const open = (d.sessions || []).find(s => !s.clockOutAt);
    if (open) await clockOut();
    else await clockIn();
}

async function clockIn() {
    try {
        await apiRequest('/time-sessions/clock-in', { method: 'POST' });
        await window.showSection(currentSection);
        setNotice('Clocked in.', true);
    } catch (err) {
        setNotice(err.message);
    }
}

async function clockOut() {
    try {
        const d = await apiRequest('/dashboard');
        const open = (d.sessions || []).find(s => !s.clockOutAt);
        if (!open) {
            setNotice('No active clock-in session found.');
            return;
        }
        await apiRequest(`/time-sessions/${open.id}/clock-out`, { method: 'POST' });
        await window.showSection(currentSection);
        setNotice('Clocked out successfully.', true);
    } catch (err) {
        setNotice(err.message);
    }
}

async function setWorkLocation(location) {
    try {
        await apiRequest('/me/location', { method: 'PATCH', body: { location } });
        await window.showSection(currentSection);
        setNotice(`Work location set to ${location === 'REMOTE' ? 'Remote' : 'On-site'}.`, true);
    } catch (err) {
        setNotice(err.message);
    }
}

// Employees (HR Admin)
async function loadEmployees() {
    if (!isHRUser) return;
    const employees = await apiRequest('/employees');
    const listEl = document.getElementById('employeesList');
    if (!listEl) return;

    if (!employees.length) {
        listEl.innerHTML = '<p>No employees found.</p>';
        return;
    }

    listEl.innerHTML = employees.map(emp => {
        const initials = (emp.fullName || '?').split(/\s+/).map(p => p[0]).join('').slice(0, 2).toUpperCase();
        const avatar = emp.avatarUrl
            ? `<img src="${escapeHtml(emp.avatarUrl)}" alt="${escapeHtml(emp.fullName)}" class="employee-avatar">`
            : `<div class="employee-avatar" style="display:flex;align-items:center;justify-content:center;background:var(--color-primary,#21808d);color:#fff;font-weight:600;">${escapeHtml(initials)}</div>`;
        return `
            <div class="card employee-card">
                <div class="card__body">
                    <div class="employee-header">
                        ${avatar}
                        <div class="employee-info">
                            <h4>${escapeHtml(emp.fullName)}</h4>
                            <p>${escapeHtml(emp.position || 'Employee')} · ${escapeHtml(emp.employeeCode)}</p>
                        </div>
                    </div>
                    <div class="employee-details">
                        <p><strong>Department:</strong> ${escapeHtml(emp.department)}</p>
                        <p><strong>Email:</strong> ${escapeHtml(emp.email)}</p>
                        <p><strong>Employment:</strong> <span class="status ${statusClass(emp.employmentStatus)}">${escapeHtml(emp.employmentStatus)}</span></p>
                        <p><strong>Presence:</strong> <span class="status ${emp.online ? 'status--success' : 'status--error'}">${emp.online ? 'online' : 'offline'}</span></p>
                        <p><strong>Location:</strong> ${emp.location === 'REMOTE' ? 'Remote' : 'On-site'}</p>
                        <p><strong>Clock Status:</strong> ${emp.clockStatus === 'CLOCKED_IN' ? 'Clocked In (' + escapeHtml(formatTime(emp.currentClockInAt)) + ')' : 'Not Clocked In'}</p>
                    </div>
                    <div style="margin-top: 12px;">
                        <button class="btn btn--outline btn--sm" onclick="openEditEmployeeModal('${escapeHtml(emp.id)}')">Edit</button>
                    </div>
                </div>
            </div>
        `;
    }).join('');
}

async function openAddDepartmentModal() {
    openActionModal('Add Department', [
        { name: 'name', label: 'Department Name', required: true }
    ], async (values) => {
        await apiRequest('/departments', { method: 'POST', body: values });
        setNotice('Department created.', true);
    });
}

async function openAddEmployeeModal() {
    try {
        const depts = await apiRequest('/departments');
        openActionModal('Add New Employee', [
            { name: 'fullName', label: 'Full Name', required: true },
            { name: 'email', label: 'Email', type: 'email', required: true },
            { name: 'password', label: 'Initial Password (min 8 chars)', type: 'password', required: true },
            { name: 'employeeCode', label: 'Employee Code', required: true },
            {
                name: 'departmentId', label: 'Department', type: 'select', required: true,
                options: depts.map(d => ({ value: d.id, label: d.name }))
            },
            { name: 'position', label: 'Position' },
            { name: 'hireDate', label: 'Hire Date', type: 'date', required: true }
        ], async (values) => {
            await apiRequest('/employees', { method: 'POST', body: values });
            await loadEmployees();
            setNotice('Employee created.', true);
        });
    } catch (err) {
        setNotice(err.message);
    }
}

window.openEditEmployeeModal = async function (employeeId) {
    try {
        const [emp, depts] = await Promise.all([
            apiRequest(`/employees/${employeeId}`),
            apiRequest('/departments')
        ]);
        openActionModal(`Edit ${emp.fullName}`, [
            { name: 'fullName', label: 'Full Name', value: emp.fullName, required: true },
            { name: 'position', label: 'Position', value: emp.position || '', allowEmpty: true },
            {
                name: 'departmentId', label: 'Department', type: 'select', value: emp.departmentId, required: true,
                options: depts.map(d => ({ value: d.id, label: d.name }))
            },
            {
                name: 'employmentStatus', label: 'Employment Status', type: 'select', value: emp.employmentStatus, required: true,
                options: [
                    { value: 'ACTIVE', label: 'Active' },
                    { value: 'INACTIVE', label: 'Inactive' },
                    { value: 'TERMINATED', label: 'Terminated' }
                ]
            }
        ], async (values) => {
            await apiRequest(`/employees/${employeeId}`, { method: 'PATCH', body: values });
            await loadEmployees();
            setNotice('Employee updated.', true);
        });
    } catch (err) {
        setNotice(err.message);
    }
};

// Timesheets
async function loadTimesheetSection() {
    if (!currentDashboard) currentDashboard = await apiRequest('/dashboard');
    const range = getPeriodRange(currentDashboard.date);
    const [report, sheets] = await Promise.all([
        apiRequest(`/timesheets/report?from=${range.from}&to=${range.to}`),
        apiRequest('/timesheets')
    ]);
    lastTimesheetReport = { range, report };

    const summaryEl = document.getElementById('timesheetSummary');
    if (summaryEl) {
        summaryEl.innerHTML = `
            <div class="summary-item">
                <span class="summary-value">${report.totalHours}</span>
                <span class="summary-label">Total Hours</span>
            </div>
            <div class="summary-item">
                <span class="summary-value">${report.approvedHours}</span>
                <span class="summary-label">Approved Hours</span>
            </div>
            <div class="summary-item">
                <span class="summary-value">${report.pendingHours}</span>
                <span class="summary-label">Pending Hours</span>
            </div>
            <div class="summary-item">
                <span class="summary-value">${report.averageHoursPerDay}</span>
                <span class="summary-label">Avg Hours/Day</span>
            </div>
            <div class="summary-item" style="display:flex;align-items:center;justify-content:center;">
                <button class="btn btn--primary btn--sm" onclick="submitPeriodTimesheet('${range.from}', '${range.to}')">Submit Timesheet</button>
            </div>
        `;
    }

    const recordsEl = document.getElementById('timesheetRecords');
    if (!recordsEl) return;

    const recordsHtml = (report.records || []).length
        ? `
            <div class="time-record-header">
                <span>Date</span>
                <span>Clock In</span>
                <span>Clock Out</span>
                <span>Hours</span>
                <span>Location</span>
                <span>Status</span>
                <span>Employee</span>
            </div>
            ${report.records.map(r => `
                <div class="time-record">
                    <span>${escapeHtml(String(r.clockInAt || '').slice(0, 10))}</span>
                    <span>${escapeHtml(formatTime(r.clockInAt))}</span>
                    <span>${escapeHtml(formatTime(r.clockOutAt))}</span>
                    <span>${r.hours == null ? '—' : r.hours + 'h'}</span>
                    <span>${r.location === 'REMOTE' ? 'remote' : 'on-site'}</span>
                    <span class="status ${statusClass(r.approvalStatus)}">${escapeHtml(String(r.approvalStatus || '').toLowerCase())}</span>
                    <span>${escapeHtml(r.fullName || '')}</span>
                </div>
            `).join('')}
        `
        : '<p>No time records found for this period.</p>';

    const matchingSheets = (sheets || []).filter(s => s.startPeriod <= range.to && s.endPeriod >= range.from);
    const sheetsHtml = matchingSheets.length
        ? `<h4 style="margin-top: 20px;">Submitted Timesheets</h4>` + matchingSheets.map(s => `
            <div class="card" style="margin-top: 10px;">
                <div class="card__body" style="display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:8px;">
                    <div>
                        <strong>${escapeHtml(s.startPeriod)} to ${escapeHtml(s.endPeriod)}</strong>
                        <span class="status ${statusClass(s.status)}" style="margin-left:8px;">${escapeHtml(s.status)}</span>
                        <p style="margin:4px 0 0;">Regular: ${s.totalRegularHours}h · Overtime: ${s.overtimeHours}h</p>
                    </div>
                    <div style="display:flex;gap:8px;">
                        <button class="btn btn--outline btn--sm" onclick="exportSingleTimesheet('${escapeHtml(s.id)}')">CSV</button>
                        ${isHRUser && s.status === 'PENDING' ? `
                            <button class="btn btn--primary btn--sm" onclick="reviewTimesheet('${escapeHtml(s.id)}', 'approve')">Approve</button>
                            <button class="btn btn--secondary btn--sm" onclick="reviewTimesheet('${escapeHtml(s.id)}', 'reject')">Reject</button>
                        ` : ''}
                    </div>
                </div>
            </div>
        `).join('')
        : '';

    recordsEl.innerHTML = recordsHtml + sheetsHtml;
}

window.submitPeriodTimesheet = async function (startPeriod, endPeriod) {
    try {
        await apiRequest('/timesheets', { method: 'POST', body: { startPeriod, endPeriod } });
        await loadTimesheetSection();
        setNotice('Timesheet submitted for approval.', true);
    } catch (err) {
        setNotice(err.message);
    }
};

window.reviewTimesheet = async function (sheetId, decision) {
    try {
        await apiRequest(`/timesheets/${sheetId}/${decision}`, { method: 'POST' });
        await loadTimesheetSection();
        setNotice(`Timesheet ${decision}d.`, true);
    } catch (err) {
        setNotice(err.message);
    }
};

window.exportSingleTimesheet = async function (sheetId) {
    try {
        const csv = await apiRequest(`/timesheets/${sheetId}/export`);
        downloadCsv(`timesheet-${sheetId}.csv`, csv);
    } catch (err) {
        setNotice(err.message);
    }
};

function exportCurrentTimesheetReport() {
    if (!lastTimesheetReport || !(lastTimesheetReport.report.records || []).length) {
        setNotice('No timesheet records to export for the selected period.');
        return;
    }
    const { range, report } = lastTimesheetReport;
    const csv = [
        'ClockInAt,ClockOutAt,Hours,Location,Status,Employee',
        ...report.records.map(r => [r.clockInAt, r.clockOutAt || '', r.hours ?? '', r.location, r.approvalStatus, `"${String(r.fullName || '').replace(/"/g, '""')}"`].join(','))
    ].join('\r\n');
    downloadCsv(`timesheet_${range.from}_to_${range.to}.csv`, csv);
}

// Leave Management
function hideLeaveRequestForm() {
    document.getElementById('leaveRequestForm').classList.add('hidden');
    document.getElementById('leaveForm').reset();
}

async function submitLeaveRequest(e) {
    e.preventDefault();
    try {
        await apiRequest('/leave-requests', {
            method: 'POST',
            body: {
                type: document.getElementById('leaveType').value,
                startDate: document.getElementById('startDate').value,
                endDate: document.getElementById('endDate').value,
                reason: document.getElementById('leaveReason').value.trim()
            }
        });
        hideLeaveRequestForm();
        await loadLeaveRequests();
        setNotice('Leave request submitted.', true);
    } catch (err) {
        setNotice(err.message);
    }
}

async function loadLeaveRequests() {
    const requests = await apiRequest(isHRUser ? '/leave-requests/all' : '/leave-requests');
    const listEl = document.getElementById('leaveRequestsList');
    if (!listEl) return;

    if (!requests.length) {
        listEl.innerHTML = '<div class="card"><div class="card__body"><p>No leave requests found.</p></div></div>';
        return;
    }

    listEl.innerHTML = requests.map(req => `
        <div class="card request-card">
            <div class="card__body">
                <div class="request-header">
                    <h4>${escapeHtml(req.employee || currentUser.fullName)} - ${escapeHtml(req.type)}</h4>
                    <span class="status ${statusClass(req.status)}">${escapeHtml(String(req.status).toLowerCase())}</span>
                </div>
                <div class="request-details">
                    <div><strong>Start:</strong> ${escapeHtml(req.startDate)}</div>
                    <div><strong>End:</strong> ${escapeHtml(req.endDate)}</div>
                    <div><strong>Submitted:</strong> ${escapeHtml(String(req.submittedAt || '').slice(0, 10))}</div>
                </div>
                ${req.reason ? `<p><strong>Reason:</strong> ${escapeHtml(req.reason)}</p>` : ''}
                ${req.status === 'PENDING' ? `
                    <div class="request-actions">
                        ${isHRUser ? `
                            <button class="btn btn--primary btn--sm" onclick="reviewLeaveRequest('${escapeHtml(req.id)}', 'approve')">Approve</button>
                            <button class="btn btn--secondary btn--sm" onclick="reviewLeaveRequest('${escapeHtml(req.id)}', 'reject')">Reject</button>
                        ` : `
                            <button class="btn btn--secondary btn--sm" onclick="cancelLeaveRequest('${escapeHtml(req.id)}')">Cancel</button>
                        `}
                    </div>
                ` : ''}
            </div>
        </div>
    `).join('');
}

window.reviewLeaveRequest = async function (id, decision) {
    try {
        await apiRequest(`/leave-requests/${id}/${decision}`, { method: 'POST' });
        await loadLeaveRequests();
        setNotice(`Leave request ${decision}d.`, true);
    } catch (err) {
        setNotice(err.message);
    }
};

window.cancelLeaveRequest = async function (id) {
    try {
        await apiRequest(`/leave-requests/${id}/cancel`, { method: 'POST' });
        await loadLeaveRequests();
        setNotice('Leave request cancelled.', true);
    } catch (err) {
        setNotice(err.message);
    }
};

// Payroll
async function loadPayroll() {
    const records = await apiRequest('/payroll-records');
    const select = document.getElementById('payPeriodSelect');
    const prior = select ? select.value : '';

    const periods = [...new Set(records.map(r => `${r.startPeriod} to ${r.endPeriod}`))];
    if (select) {
        select.innerHTML = `<option value="">All Periods</option>` +
            periods.map(p => `<option value="${escapeHtml(p)}">${escapeHtml(p)}</option>`).join('');
        if (periods.includes(prior)) select.value = prior;
    }

    const filtered = (select && select.value)
        ? records.filter(r => `${r.startPeriod} to ${r.endPeriod}` === select.value)
        : records;

    const el = document.getElementById('payrollData');
    if (!el) return;

    if (!filtered.length) {
        el.innerHTML = '<div class="card"><div class="card__body"><p>No payroll records found.</p></div></div>';
        return;
    }

    el.innerHTML = filtered.map(p => `
        <div class="card payroll-card payroll-enhanced">
            <div class="card__body">
                <div class="payroll-header">
                    <h4>${escapeHtml(p.employeeName || currentUser.fullName)}</h4>
                    <span class="status ${statusClass(p.status)}">${escapeHtml(String(p.status).toLowerCase())}</span>
                </div>
                <p><strong>Pay Period:</strong> ${escapeHtml(p.startPeriod)} to ${escapeHtml(p.endPeriod)}</p>
                <div class="payroll-tracking">
                    <div class="tracking-item">
                        <span class="label">Clock Ins</span>
                        <span class="value">${p.clockIns ?? 0}</span>
                    </div>
                    <div class="tracking-item">
                        <span class="label">Clock Outs</span>
                        <span class="value">${p.clockOuts ?? 0}</span>
                    </div>
                    <div class="tracking-item">
                        <span class="label">Timesheet</span>
                        <span class="value ${statusClass(p.timesheetStatus)}">${escapeHtml(String(p.timesheetStatus || '').toLowerCase())}</span>
                    </div>
                </div>
                <div class="payroll-details">
                    <div class="payroll-item">
                        <span class="label">Regular Hours</span>
                        <span class="value">${p.regularHours}</span>
                    </div>
                    <div class="payroll-item">
                        <span class="label">Overtime</span>
                        <span class="value">${p.overtimeHours}</span>
                    </div>
                    <div class="payroll-item">
                        <span class="label">Gross Pay</span>
                        <span class="value">${escapeHtml(formatMoney(p.grossPay, p.currency))}</span>
                    </div>
                    <div class="payroll-item">
                        <span class="label">Net Pay</span>
                        <span class="value">${escapeHtml(formatMoney(p.netPay, p.currency))}</span>
                    </div>
                </div>
                ${isHRUser && p.status === 'PENDING' ? `
                    <div style="margin-top: 12px;">
                        <button class="btn btn--primary btn--sm" onclick="approvePayroll('${escapeHtml(p.id)}')">Approve Payroll</button>
                    </div>
                ` : ''}
            </div>
        </div>
    `).join('');
}

window.approvePayroll = async function (id) {
    try {
        await apiRequest(`/payroll-records/${id}/approve`, { method: 'POST' });
        await loadPayroll();
        setNotice('Payroll approved.', true);
    } catch (err) {
        setNotice(err.message);
    }
};

async function openCalculatePayrollModal() {
    try {
        const sheets = (await apiRequest('/timesheets')).filter(s => s.status === 'APPROVED');
        if (!sheets.length) {
            setNotice('Approve a timesheet first before calculating payroll.');
            return;
        }
        openActionModal('Calculate Payroll', [
            {
                name: 'timesheetId', label: 'Approved Timesheet', type: 'select', required: true,
                options: sheets.map(s => ({ value: s.id, label: `${s.startPeriod} to ${s.endPeriod} (${s.totalRegularHours}h reg / ${s.overtimeHours}h OT)` }))
            },
            { name: 'regularRate', label: 'Regular Rate (minor units, e.g. paise/cents)', type: 'number', min: 0, value: 10000, required: true },
            { name: 'overtimeRate', label: 'Overtime Rate (minor units)', type: 'number', min: 0, value: 15000, required: true },
            { name: 'deductions', label: 'Deductions (minor units)', type: 'number', min: 0, value: 0, required: true },
            { name: 'currency', label: 'Currency (ISO 4217)', value: 'INR', required: true }
        ], async (values) => {
            await apiRequest('/payroll-records', { method: 'POST', body: values });
            await loadPayroll();
            setNotice('Payroll record calculated.', true);
        });
    } catch (err) {
        setNotice(err.message);
    }
}

// Office Calendar
async function loadCalendar() {
    const data = await apiRequest('/calendar');
    const dayNames = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'];

    const hoursEl = document.getElementById('officeHours');
    if (hoursEl) {
        const rows = data.officeHours || [];
        hoursEl.innerHTML = rows.length
            ? rows.map(r => `
                <div class="office-hour-item">
                    <span class="day-name">${dayNames[(r.weekday || 1) - 1]}</span>
                    <span class="hours">${r.closed ? 'Closed' : `${escapeHtml(r.opensAt)} - ${escapeHtml(r.closesAt)}`}</span>
                </div>
            `).join('')
            : '<p>No office hours configured yet.</p>';
    }

    const holidays = (data.events || []).filter(e => e.kind === 'HOLIDAY');
    const holidaysEl = document.getElementById('companyHolidays');
    if (holidaysEl) {
        holidaysEl.innerHTML = holidays.length
            ? holidays.map(h => `
                <div class="holiday-item">
                    <div class="holiday-header">
                        <h4 class="holiday-name">${escapeHtml(h.title)}</h4>
                        <span class="status status--success">holiday</span>
                    </div>
                    <p class="holiday-date">${escapeHtml(formatDateTime(h.startsAt))}</p>
                </div>
            `).join('')
            : '<p>No holidays scheduled.</p>';
    }

    const events = (data.events || []).filter(e => e.kind === 'EVENT');
    const eventsEl = document.getElementById('specialEvents');
    if (eventsEl) {
        eventsEl.innerHTML = events.length
            ? events.map(ev => `
                <div class="event-item">
                    <div class="event-header">
                        <h4 class="event-name">${escapeHtml(ev.title)}</h4>
                    </div>
                    <p class="event-date">${escapeHtml(formatDateTime(ev.startsAt))} – ${escapeHtml(formatDateTime(ev.endsAt))}</p>
                    ${ev.location ? `<p><strong>Location:</strong> ${escapeHtml(ev.location)}</p>` : ''}
                </div>
            `).join('')
            : '<p>No special events scheduled.</p>';
    }
}

function openOfficeHoursModal() {
    openActionModal('Set Office Hours', [
        {
            name: 'weekday', label: 'Day of Week', type: 'select', value: '1', required: true,
            options: [
                { value: '1', label: 'Monday' }, { value: '2', label: 'Tuesday' },
                { value: '3', label: 'Wednesday' }, { value: '4', label: 'Thursday' },
                { value: '5', label: 'Friday' }, { value: '6', label: 'Saturday' },
                { value: '7', label: 'Sunday' }
            ]
        },
        { name: 'closed', label: 'Closed all day', type: 'checkbox', value: false },
        { name: 'opensAt', label: 'Opens At (HH:MM)', type: 'time', value: '09:00' },
        { name: 'closesAt', label: 'Closes At (HH:MM)', type: 'time', value: '18:00' }
    ], async (values) => {
        const body = { weekday: Number(values.weekday), closed: Boolean(values.closed) };
        if (!body.closed) {
            body.opensAt = values.opensAt;
            body.closesAt = values.closesAt;
        }
        await apiRequest('/calendar/office-hours', { method: 'POST', body });
        await loadCalendar();
        setNotice('Office hours updated.', true);
    });
}

function openCalendarEventModal() {
    openActionModal('Add Calendar Event / Holiday', [
        { name: 'title', label: 'Title', required: true },
        {
            name: 'kind', label: 'Type', type: 'select', value: 'EVENT', required: true,
            options: [{ value: 'HOLIDAY', label: 'Holiday' }, { value: 'EVENT', label: 'Special Event' }]
        },
        { name: 'startsAt', label: 'Starts At', type: 'datetime-local', required: true },
        { name: 'endsAt', label: 'Ends At', type: 'datetime-local', required: true },
        { name: 'location', label: 'Location' }
    ], async (values) => {
        await apiRequest('/calendar/events', {
            method: 'POST',
            body: {
                title: values.title,
                kind: values.kind,
                startsAt: new Date(values.startsAt).toISOString(),
                endsAt: new Date(values.endsAt).toISOString(),
                location: values.location
            }
        });
        await loadCalendar();
        setNotice('Calendar entry added.', true);
    });
}

// Training & Development
async function loadTraining() {
    const [modules, enrollments] = await Promise.all([
        apiRequest('/training-modules'),
        apiRequest('/training-enrollments')
    ]);
    const el = document.getElementById('trainingModules');
    if (!el) return;

    if (!modules.length) {
        el.innerHTML = '<div class="card"><div class="card__body"><p>No training modules available.</p></div></div>';
        return;
    }

    el.innerHTML = modules.map(m => {
        const mine = enrollments.find(e => e.moduleId === m.id && e.employeeId === currentUser.employeeId);
        const pendingApprovals = isHRUser ? enrollments.filter(e => e.moduleId === m.id && e.status === 'COMPLETED') : [];
        let btnLabel = 'Enroll & Start';
        if (mine?.status === 'ASSIGNED') btnLabel = 'Start Training';
        else if (mine?.status === 'IN_PROGRESS') btnLabel = 'Continue Training';
        else if (mine?.status === 'COMPLETED' || mine?.status === 'APPROVED') btnLabel = 'Review';

        return `
            <div class="card training-card">
                <div class="card__body">
                    <div class="training-header">
                        <h4>${escapeHtml(m.title)}</h4>
                        ${m.mandatory ? '<span class="training-badge">Mandatory</span>' : ''}
                    </div>
                    ${m.description ? `<p>${escapeHtml(m.description)}</p>` : ''}
                    <p><strong>Duration:</strong> ${Number(m.durationMinutes) || 0} minutes</p>
                    ${mine ? `<p><strong>My Status:</strong> <span class="status ${statusClass(mine.status)}">${escapeHtml(mine.status)}</span> (${mine.completionPercent}%)</p>` : ''}
                    <div class="training-progress">
                        <div class="progress-bar">
                            <div class="progress-fill" style="width: ${Number(m.completionRate) || 0}%"></div>
                        </div>
                        <p>Completion Rate: ${Number(m.completionRate) || 0}%</p>
                    </div>
                    <div style="display:flex;flex-wrap:wrap;gap:8px;margin-top:8px;">
                        <button class="btn btn--primary btn--sm" onclick="openTrainingModule('${escapeHtml(m.id)}')">${btnLabel}</button>
                        ${pendingApprovals.map(e => `
                            <button class="btn btn--outline btn--sm" onclick="approveTrainingEnrollment('${escapeHtml(e.id)}')">Approve Completion</button>
                        `).join('')}
                    </div>
                </div>
            </div>
        `;
    }).join('');
}

window.openTrainingModule = async function (moduleId) {
    try {
        const [modules, enrollments] = await Promise.all([
            apiRequest('/training-modules'),
            apiRequest('/training-enrollments')
        ]);
        const mod = modules.find(m => m.id === moduleId);
        if (!mod) return;

        let mine = enrollments.find(e => e.moduleId === moduleId && e.employeeId === currentUser.employeeId);
        if (!mine) {
            mine = await apiRequest(`/training-modules/${moduleId}/enroll`, { method: 'POST' });
        }
        if (mine.status === 'ASSIGNED') {
            mine = await apiRequest(`/training-enrollments/${mine.id}/start`, { method: 'POST' });
        }

        currentTrainingModule = mod;
        currentTrainingEnrollment = mine;

        document.getElementById('trainingModalTitle').textContent = mod.title;
        document.getElementById('trainingModalContent').innerHTML = `
            <p>${escapeHtml(mod.description || 'No description provided.')}</p>
            <p><strong>Duration:</strong> ${Number(mod.durationMinutes) || 0} minutes</p>
            <p><strong>Status:</strong> <span class="status ${statusClass(mine.status)}">${escapeHtml(mine.status)}</span></p>
        `;
        const pct = Number(mine.completionPercent) || 0;
        document.getElementById('trainingProgress').style.width = pct + '%';
        document.getElementById('progressText').textContent = pct + '%';

        const completeBtn = document.getElementById('completeTrainingBtn');
        if (completeBtn) completeBtn.classList.toggle('hidden', mine.status !== 'IN_PROGRESS');

        document.getElementById('trainingModal').classList.remove('hidden');
        await loadTraining();
    } catch (err) {
        setNotice(err.message);
    }
};

async function completeActiveTraining() {
    if (!currentTrainingEnrollment) return;
    try {
        await apiRequest(`/training-enrollments/${currentTrainingEnrollment.id}/complete`, { method: 'POST' });
        closeModal();
        await loadTraining();
        setNotice('Training module marked complete.', true);
    } catch (err) {
        setNotice(err.message);
    }
}

window.approveTrainingEnrollment = async function (enrollmentId) {
    try {
        await apiRequest(`/training-enrollments/${enrollmentId}/approve`, { method: 'POST' });
        await loadTraining();
        setNotice('Training completion approved.', true);
    } catch (err) {
        setNotice(err.message);
    }
};

function openAddTrainingModal() {
    openActionModal('Add Training Module', [
        { name: 'title', label: 'Module Title', required: true },
        { name: 'description', label: 'Description', type: 'textarea' },
        { name: 'durationMinutes', label: 'Duration (minutes)', type: 'number', min: 1, value: 30, required: true },
        { name: 'mandatory', label: 'Mandatory', type: 'checkbox', value: true }
    ], async (values) => {
        await apiRequest('/training-modules', { method: 'POST', body: values });
        await loadTraining();
        setNotice('Training module created.', true);
    });
}

// Meetings
async function loadMeetings() {
    const meetings = await apiRequest('/meetings');
    const el = document.getElementById('meetingsList');
    if (!el) return;

    if (!meetings.length) {
        el.innerHTML = '<div class="card"><div class="card__body"><p>No meetings scheduled.</p></div></div>';
        return;
    }

    el.innerHTML = meetings.map(m => {
        const names = (m.attendees || []).map(a => a.fullName).join(', ') || '—';
        return `
            <div class="card">
                <div class="card__body">
                    <div class="meeting-header">
                        <h4>${escapeHtml(m.title)}</h4>
                        <span class="meeting-time">${escapeHtml(formatDateTime(m.startsAt))}</span>
                    </div>
                    <p><strong>Duration:</strong> ${Number(m.durationMinutes) || 0} min</p>
                    <p class="meeting-attendees"><strong>Attendees:</strong> ${escapeHtml(names)}</p>
                    <div style="margin-top: 16px;">
                        <button class="btn btn--primary btn--sm" onclick="joinMeeting('${escapeHtml(m.id)}')">Join Meeting</button>
                    </div>
                </div>
            </div>
        `;
    }).join('');
}

window.joinMeeting = async function (meetingId) {
    try {
        const res = await apiRequest(`/meetings/${meetingId}/join`);
        if (res && res.joinUrl && res.joinUrl.startsWith('https://')) {
            window.open(res.joinUrl, '_blank', 'noopener,noreferrer');
        }
    } catch (err) {
        setNotice(err.message);
    }
};

async function openScheduleMeetingModal() {
    try {
        const employees = await apiRequest('/employees');
        openActionModal('Schedule Meeting', [
            { name: 'title', label: 'Meeting Title', required: true },
            { name: 'startsAt', label: 'Start Time', type: 'datetime-local', required: true },
            { name: 'durationMinutes', label: 'Duration (minutes)', type: 'number', min: 1, max: 1440, value: 30, required: true },
            { name: 'joinUrl', label: 'HTTPS Join URL', type: 'url', placeholder: 'https://meet.example.com/room', required: true },
            {
                name: 'attendeeIds', label: 'Attendees (hold Ctrl/Cmd for multiple)', type: 'select', multiple: true, required: true,
                options: employees.map(e => ({ value: e.id, label: `${e.fullName} (${e.email})` }))
            }
        ], async (values) => {
            await apiRequest('/meetings', {
                method: 'POST',
                body: {
                    title: values.title,
                    startsAt: new Date(values.startsAt).toISOString(),
                    durationMinutes: values.durationMinutes,
                    joinUrl: values.joinUrl,
                    attendeeIds: values.attendeeIds
                }
            });
            await loadMeetings();
            setNotice('Meeting scheduled.', true);
        });
    } catch (err) {
        setNotice(err.message);
    }
}

// Recruitment (HR Admin)
async function loadRecruitment() {
    if (!isHRUser) return;
    const [jobs, applications] = await Promise.all([
        apiRequest('/job-postings'),
        apiRequest('/applications')
    ]);
    const container = document.getElementById('recruitmentContent');
    if (!container) return;

    const inReview = applications.filter(a => a.stage === 'IN_REVIEW').length;
    const interviews = applications.filter(a => a.stage === 'INTERVIEW').length;

    container.innerHTML = `
        <div class="card">
            <div class="card__body">
                <h3>Job Postings</h3>
                <div class="job-listings">
                    ${jobs.length ? jobs.map(j => `
                        <div class="job-item">
                            <h4>${escapeHtml(j.title)}</h4>
                            <p>Applications: ${j.applicationCount ?? 0}${j.publishedAt ? ' • Published: ' + escapeHtml(formatShortDate(j.publishedAt)) : ''}</p>
                            <div style="display:flex;align-items:center;gap:8px;margin-top:6px;">
                                <span class="status ${statusClass(j.status)}">${escapeHtml(j.status)}</span>
                                ${j.status === 'DRAFT' ? `<button class="btn btn--primary btn--sm" onclick="changeJobStatus('${escapeHtml(j.id)}', 'publish')">Publish</button>` : ''}
                                ${j.status === 'PUBLISHED' ? `<button class="btn btn--secondary btn--sm" onclick="changeJobStatus('${escapeHtml(j.id)}', 'close')">Close</button>` : ''}
                            </div>
                        </div>
                    `).join('') : '<p>No job postings yet.</p>'}
                </div>
            </div>
        </div>
        <div class="card">
            <div class="card__body">
                <h3>Recruitment Pipeline</h3>
                <div class="pipeline-stats">
                    <div class="stat-item">
                        <span class="stat-number">${applications.length}</span>
                        <span class="stat-label">Total Applications</span>
                    </div>
                    <div class="stat-item">
                        <span class="stat-number">${inReview}</span>
                        <span class="stat-label">In Review</span>
                    </div>
                    <div class="stat-item">
                        <span class="stat-number">${interviews}</span>
                        <span class="stat-label">Interviews Scheduled</span>
                    </div>
                </div>
                <div style="margin-top: 16px;">
                    ${applications.length ? applications.map(a => {
                        const job = jobs.find(j => j.id === a.jobId);
                        const locked = a.stage === 'HIRED' || a.stage === 'REJECTED';
                        return `
                            <div class="job-item" style="display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:8px;">
                                <div>
                                    <strong>${escapeHtml(a.candidateName)}</strong> (${escapeHtml(a.email)})
                                    <p style="margin:2px 0 0;">Job: ${escapeHtml(job ? job.title : a.jobId)}</p>
                                </div>
                                <div style="display:flex;align-items:center;gap:8px;">
                                    <span class="status ${statusClass(a.stage)}">${escapeHtml(a.stage)}</span>
                                    ${locked ? '' : `
                                        <select class="form-control" style="width:auto;" onchange="updateApplicationStage('${escapeHtml(a.id)}', this.value)">
                                            <option value="">Move stage...</option>
                                            <option value="IN_REVIEW">In Review</option>
                                            <option value="INTERVIEW">Interview</option>
                                            <option value="OFFERED">Offered</option>
                                            <option value="HIRED">Hired</option>
                                            <option value="REJECTED">Rejected</option>
                                        </select>
                                    `}
                                </div>
                            </div>
                        `;
                    }).join('') : '<p>No applications yet.</p>'}
                </div>
            </div>
        </div>
    `;
}

window.changeJobStatus = async function (jobId, action) {
    try {
        await apiRequest(`/job-postings/${jobId}/${action}`, { method: 'POST' });
        await loadRecruitment();
        setNotice(`Job posting ${action === 'publish' ? 'published' : 'closed'}.`, true);
    } catch (err) {
        setNotice(err.message);
    }
};

window.updateApplicationStage = async function (appId, stage) {
    if (!stage) return;
    try {
        await apiRequest(`/applications/${appId}`, { method: 'PATCH', body: { stage } });
        await loadRecruitment();
        setNotice(`Application moved to ${stage}.`, true);
    } catch (err) {
        setNotice(err.message);
    }
};

function openPostJobModal() {
    openActionModal('Post New Job', [
        { name: 'title', label: 'Job Title', required: true },
        { name: 'description', label: 'Description', type: 'textarea' },
        {
            name: 'status', label: 'Initial Status', type: 'select', value: 'PUBLISHED', required: true,
            options: [{ value: 'PUBLISHED', label: 'Published' }, { value: 'DRAFT', label: 'Draft' }]
        }
    ], async (values) => {
        await apiRequest('/job-postings', { method: 'POST', body: values });
        await loadRecruitment();
        setNotice('Job posting created.', true);
    });
}

async function openAddApplicationModal() {
    try {
        const jobs = (await apiRequest('/job-postings')).filter(j => j.status === 'PUBLISHED');
        if (!jobs.length) {
            setNotice('Publish a job posting first before adding an application.');
            return;
        }
        openActionModal('Add Candidate Application', [
            {
                name: 'jobId', label: 'Job Posting', type: 'select', required: true,
                options: jobs.map(j => ({ value: j.id, label: j.title }))
            },
            { name: 'candidateName', label: 'Candidate Name', required: true },
            { name: 'email', label: 'Candidate Email', type: 'email', required: true }
        ], async (values) => {
            await apiRequest('/applications', { method: 'POST', body: values });
            await loadRecruitment();
            setNotice('Application added.', true);
        });
    } catch (err) {
        setNotice(err.message);
    }
}

function closeModal() {
    document.querySelectorAll('.modal').forEach(modal => {
        modal.classList.add('hidden');
    });
    actionModalHandler = null;
}
