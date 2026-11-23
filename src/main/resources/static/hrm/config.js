// Runtime configuration for Kanishk HRM Frontend.
// Safe public values ONLY (never put CLERK_SECRET_KEY or DB passwords in client code).
window.HRM_API_BASE = window.HRM_API_BASE || (
    typeof window !== 'undefined' && window.location.hostname.endsWith('.github.io')
        ? 'https://sppmbetepxgtyzdisgcq.supabase.co/functions/v1/api'
        : ''
);

window.CLERK_PUBLISHABLE_KEY = window.CLERK_PUBLISHABLE_KEY || 'pk_test_cG9zaXRpdmUtY2FpbWFuLTcxOTAuY2xlcmsuYWNjb3VudHMuZGV2JA';
