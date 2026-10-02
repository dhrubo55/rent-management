import { afterEach, expect, test, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";
import { AxiosError, AxiosHeaders, type AxiosAdapter, type InternalAxiosRequestConfig } from "axios";
import { apiClient, setAccessTokenProvider, setUnauthorizedHandler } from "../api";
import { useAuthStore } from "./auth";

const originalAdapter = apiClient.defaults.adapter;

afterEach(() => {
  apiClient.interceptors.request.clear();
  apiClient.interceptors.response.clear();
  apiClient.defaults.adapter = originalAdapter;
  vi.unstubAllGlobals();
});

test("a temporary refresh network error keeps the session available for retry", async () => {
  const values = new Map<string, string>();
  vi.stubGlobal("window", {
    localStorage: {
      getItem: (key: string) => values.get(key) ?? null,
      setItem: (key: string, value: string) => values.set(key, value),
      removeItem: (key: string) => values.delete(key)
    }
  });
  setActivePinia(createPinia());
  const auth = useAuthStore();
  auth.setAccessToken("expired-access");
  auth.setRefreshToken("still-valid-refresh");
  apiClient.defaults.adapter = async (config) => {
    throw new AxiosError("offline", "ERR_NETWORK", config);
  };

  expect(await auth.refreshSession()).toBeNull();
  expect(auth.accessToken).toBe("expired-access");
  expect(auth.refreshToken).toBe("still-valid-refresh");
});

test("concurrent expired requests refresh once and retain their request bodies", async () => {
  const values = new Map<string, string>();
  vi.stubGlobal("window", {
    localStorage: {
      getItem: (key: string) => values.get(key) ?? null,
      setItem: (key: string, value: string) => values.set(key, value),
      removeItem: (key: string) => values.delete(key)
    }
  });
  setActivePinia(createPinia());
  const auth = useAuthStore();
  auth.setAccessToken("expired-access");
  auth.setRefreshToken("first-refresh");
  auth.setRole("OWNER");
  setAccessTokenProvider(() => auth.accessToken);
  setUnauthorizedHandler(async (usedToken) => {
    if (auth.accessToken && auth.accessToken !== usedToken) return auth.accessToken;
    return auth.refreshSession();
  });

  let refreshCount = 0;
  const adapter: AxiosAdapter = async (config: InternalAxiosRequestConfig) => {
    if (config.url === "/auth/refresh") {
      refreshCount++;
      await new Promise((resolve) => setTimeout(resolve, 5));
      return {
        data: { data: { accessToken: "new-access", refreshToken: "next-refresh", role: "OWNER" } },
        status: 200, statusText: "OK", headers: new AxiosHeaders(), config
      };
    }
    if (config.headers.Authorization !== "Bearer new-access") {
      const response = { data: {}, status: 401, statusText: "Unauthorized", headers: new AxiosHeaders(), config };
      throw new AxiosError("expired", "ERR_BAD_REQUEST", config, undefined, response);
    }
    return { data: { body: config.data }, status: 200, statusText: "OK", headers: new AxiosHeaders(), config };
  };
  apiClient.defaults.adapter = adapter;

  const [first, second] = await Promise.all([
    apiClient.post("/private", { draft: "first" }),
    apiClient.post("/private", { draft: "second" })
  ]);

  expect(refreshCount).toBe(1);
  expect(auth.refreshToken).toBe("next-refresh");
  expect(first.data.body).toContain('"draft":"first"');
  expect(second.data.body).toContain('"draft":"second"');
});
