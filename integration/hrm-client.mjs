/** Create one client per signed-in browser tab. apiRoot may be an absolute HTTPS URL. */
export function createHrmClient(apiRoot = "/api") {
  let token;
  async function request(path, {method = "GET", body} = {}) {
    const headers = {};
    if (token) headers.Authorization = "Bearer " + token;
    if (body !== undefined) headers["Content-Type"] = "application/json";
    const response = await fetch(apiRoot.replace(/\/$/, "") + path, {
      method, headers, body: body === undefined ? undefined : JSON.stringify(body)
    });
    if (!response.ok) {
      if (response.status === 401) token = undefined;
      const detail = await response.json().catch(() => ({}));
      const error = new Error(detail.message || detail.error || response.statusText);
      error.status = response.status;
      throw error;
    }
    if (response.status === 204) return;
    return response.headers.get("content-type")?.includes("application/json")
      ? response.json() : response.text();
  }
  return {
    request,
    async login(email, password) {
      const result = await request("/auth/login", {method: "POST", body: {email, password}});
      token = result.accessToken;
      return result.role;
    },
    async logout() {
      await request("/auth/logout", {method: "POST"});
      token = undefined;
    }
  };
}
