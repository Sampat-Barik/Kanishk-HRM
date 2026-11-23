/** Create an API client backed by a Clerk session token provider. */
export function createHrmClient(apiRoot = "/api", getToken = async () => null) {
  async function request(path, {method = "GET", body} = {}) {
    const headers = {};
    const token = await getToken();
    if (token) headers.Authorization = "Bearer " + token;
    if (body !== undefined) headers["Content-Type"] = "application/json";
    const response = await fetch(apiRoot.replace(/\/$/, "") + path, {
      method, headers, body: body === undefined ? undefined : JSON.stringify(body)
    });
    if (!response.ok) {
      const detail = await response.json().catch(() => ({}));
      const error = new Error(detail.message || detail.error || response.statusText);
      error.status = response.status;
      throw error;
    }
    if (response.status === 204) return;
    return response.headers.get("content-type")?.includes("application/json")
      ? response.json() : response.text();
  }
  return { request };
}
