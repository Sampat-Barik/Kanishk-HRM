// Runtime API base override for static deployments (e.g. GitHub Pages).
// When hosted on GitHub Pages (*.github.io), connects directly to the live Supabase HRM API for project sppmbetepxgtyzdisgcq.
// When running locally on http://127.0.0.1:8080/hrm/, leaves empty so app.js uses the local Spring Boot /api.
window.HRM_API_BASE = window.HRM_API_BASE || (
    typeof window !== 'undefined' && window.location.hostname.endsWith('.github.io')
        ? 'https://sppmbetepxgtyzdisgcq.supabase.co/functions/v1/api'
        : ''
);
