import { defineStore } from "pinia";
import { apiClient } from "../api";
import { setLanguage, t } from "../i18n";

const ACCESS_TOKEN_KEY = "rentease.access_token";
const REFRESH_TOKEN_KEY = "rentease.refresh_token";
const ROLE_KEY = "rentease.role";

type AuthResponse = {
  accessToken: string;
  refreshToken: string;
  accessTokenExpiresInSeconds: number;
  preferredLanguage?: string | null;
  role?: string | null;
};

type AuthState = {
  accessToken: string | null;
  refreshToken: string | null;
  role: string | null;
  loading: boolean;
  error: string | null;
};

let refreshInFlight: Promise<string | null> | null = null;

export const useAuthStore = defineStore("auth", {
  state: (): AuthState => ({
    accessToken: getStoredToken(ACCESS_TOKEN_KEY),
    refreshToken: getStoredToken(REFRESH_TOKEN_KEY),
    role: getStoredToken(ROLE_KEY),
    loading: false,
    error: null
  }),
  getters: {
    isAuthenticated: (state) => !!state.accessToken,
    isOwner: (state) => state.role === "OWNER"
  },
  actions: {
    setAccessToken(token: string | null) {
      this.accessToken = token;
      setStoredToken(ACCESS_TOKEN_KEY, token);
    },
    setRefreshToken(token: string | null) {
      this.refreshToken = token;
      setStoredToken(REFRESH_TOKEN_KEY, token);
    },
    setRole(role: string | null) {
      this.role = role;
      setStoredToken(ROLE_KEY, role);
    },
    clearSession() {
      this.setAccessToken(null);
      this.setRefreshToken(null);
      this.setRole(null);
      this.error = null;
    },
    async refreshSession(): Promise<string | null> {
      if (!this.refreshToken) return null;
      if (refreshInFlight) return refreshInFlight;
      refreshInFlight = (async () => {
        try {
          const response = await apiClient.post<{ success: boolean; data: AuthResponse }>("/auth/refresh", {
            refreshToken: this.refreshToken
          });
          const data = response.data.data;
          this.setAccessToken(data.accessToken);
          this.setRefreshToken(data.refreshToken);
          this.setRole(data.role ?? null);
          if (data.preferredLanguage) setLanguage(data.preferredLanguage);
          return data.accessToken;
        } catch {
          this.clearSession();
          return null;
        } finally {
          refreshInFlight = null;
        }
      })();
      return refreshInFlight;
    },
    async bootstrapSession() {
      if (this.accessToken || !this.refreshToken) {
        return;
      }
      await this.refreshSession();
    },
    async login(email: string, password: string) {
      this.loading = true;
      this.error = null;
      try {
        const response = await apiClient.post<{ success: boolean; data: AuthResponse }>("/auth/login", {
          email,
          password
        });
        this.setAccessToken(response.data.data.accessToken);
        this.setRefreshToken(response.data.data.refreshToken);
        this.setRole(response.data.data.role ?? null);
        if (response.data.data.preferredLanguage) {
          setLanguage(response.data.data.preferredLanguage);
        }
      } catch (err: any) {
        if (err?.code === "ERR_NETWORK") {
          this.error = t("Cannot reach API. Confirm backend is running on port 8080.");
        } else {
          this.error = err?.response?.data?.error?.message ?? t("Login failed");
        }
        throw err;
      } finally {
        this.loading = false;
      }
    },
    async logout() {
      if (this.refreshToken) {
        try {
          await apiClient.post("/auth/logout", { refreshToken: this.refreshToken });
        } catch {
          // Session may already be expired/revoked; local sign-out should still proceed.
        }
      }
      this.clearSession();
    },
    async updatePreferredLanguage(language: string) {
      if (!this.accessToken) {
        return;
      }
      await apiClient.patch("/auth/preferred-language", { language });
    }
  }
});

function getStoredToken(key: string): string | null {
  if (typeof window === "undefined") {
    return null;
  }
  const value = window.localStorage.getItem(key);
  return value && value.trim() ? value : null;
}

function setStoredToken(key: string, value: string | null) {
  if (typeof window === "undefined") {
    return;
  }
  if (!value) {
    window.localStorage.removeItem(key);
    return;
  }
  window.localStorage.setItem(key, value);
}
