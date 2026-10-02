import axios, { type AxiosError, type InternalAxiosRequestConfig } from "axios";

const normalizeApiBaseUrl = () => {
  const configured = import.meta.env.VITE_API_BASE_URL;
  if (!configured || !configured.trim()) {
    return "/api/v1";
  }

  const base = configured.trim().replace(/\/+$/, "");
  if (base.endsWith("/api/v1")) {
    return base;
  }
  if (base.endsWith("/api")) {
    return `${base}/v1`;
  }
  return `${base}/api/v1`;
};

export const apiClient = axios.create({
  baseURL: normalizeApiBaseUrl(),
  timeout: 10000
});

export const setAccessTokenProvider = (provider: () => string | null) => {
  apiClient.interceptors.request.use((config) => {
    const token = provider();
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
  });
};

type RetryConfig = InternalAxiosRequestConfig & { _authRetry?: boolean };

export const setUnauthorizedHandler = (handler: (usedToken: string | null) => Promise<string | null>) => {
  apiClient.interceptors.response.use(undefined, async (error: AxiosError) => {
    const config = error.config as RetryConfig | undefined;
    const noRetry = ["/auth/login", "/auth/refresh", "/auth/logout", "/auth/forgot-password", "/auth/reset-password"];
    if (error.response?.status !== 401 || !config || config._authRetry ||
        noRetry.some((path) => config.url?.startsWith(path))) {
      throw error;
    }

    config._authRetry = true;
    const usedToken = String(config.headers.Authorization ?? "").replace(/^Bearer /, "") || null;
    const token = await handler(usedToken);
    if (!token) throw error;
    config.headers.Authorization = `Bearer ${token}`;
    return apiClient(config);
  });
};
