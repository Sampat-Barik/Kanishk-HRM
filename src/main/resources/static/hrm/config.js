// Runtime API base configuration for Kanishk HRM Frontend.
window.HRM_API_BASE = window.HRM_API_BASE || (
    typeof window !== 'undefined' && window.location.hostname.endsWith('.github.io')
        ? 'https://sppmbetepxgtyzdisgcq.supabase.co/functions/v1/api'
        : ''
);
