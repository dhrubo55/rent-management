import { createPinia } from "pinia";
import { createApp } from "vue";
import App from "./App.vue";
import { setAccessTokenProvider, setUnauthorizedHandler } from "./api";
import router from "./router";
import "./styles.css";
import { useAuthStore } from "./stores/auth";

const pinia = createPinia();
const app = createApp(App);
app.use(pinia);

const authStore = useAuthStore(pinia);
setAccessTokenProvider(() => authStore.accessToken);
setUnauthorizedHandler(async (usedToken) => {
  if (authStore.accessToken && authStore.accessToken !== usedToken) return authStore.accessToken;
  return authStore.refreshSession();
});

const bootstrap = async () => {
  await authStore.bootstrapSession();
  app.use(router);
  app.mount("#app");
};

void bootstrap();
