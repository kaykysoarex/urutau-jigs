"use strict";

(() => {
  const byId = id => document.getElementById(id);
  const install = byId("pwa-install");
  const iosGuide = byId("pwa-ios-guide");
  const updateNotice = byId("pwa-update-notice");
  const updateButton = byId("pwa-update");
  const offlineDialog = byId("pwa-offline");
  const offlinePageRetry = byId("pwa-retry");
  const standalone = matchMedia("(display-mode: standalone)");
  const serviceWorker = navigator.serviceWorker;
  const isIOS = /iPad|iPhone|iPod/i.test(navigator.userAgent)
    || (navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1);
  const isIOSSafari = isIOS && /Version\/\d+.*Safari/i.test(navigator.userAgent)
    && !/CriOS|FxiOS|EdgiOS|OPiOS/i.test(navigator.userAgent);
  let installPrompt = null;
  let installed = false;
  let waitingWorker = null;
  let updateAvailable = false;
  let updateRequested = false;
  let hasController = Boolean(serviceWorker?.controller);
  let hasUnsavedWork = () => false;
  let offline = false;
  let connectionCheck = null;

  const isInstalled = () => installed || standalone.matches || navigator.standalone === true;

  function renderInstall() {
    if (install) install.hidden = isInstalled() || !(installPrompt || isIOSSafari);
  }

  addEventListener("beforeinstallprompt", event => {
    event.preventDefault();
    if (!install || isInstalled()) return;
    installPrompt = event;
    renderInstall();
  });
  addEventListener("appinstalled", () => {
    installed = true;
    installPrompt = null;
    renderInstall();
  });
  standalone.addEventListener?.("change", renderInstall);
  install?.addEventListener("click", async () => {
    if (isInstalled()) return;
    if (isIOSSafari && !installPrompt) {
      iosGuide.showModal();
      return;
    }
    const prompt = installPrompt;
    installPrompt = null;
    renderInstall();
    try {
      const result = await prompt?.prompt();
      if (result?.outcome === "accepted") installed = true;
    } catch {
      // The browser can still offer installation through its own menu.
    }
    renderInstall();
  });
  byId("pwa-ios-close")?.addEventListener("click", () => iosGuide.close());
  renderInstall();

  function renderUpdate() {
    if (!updateNotice) return;
    const dirty = hasUnsavedWork();
    updateNotice.hidden = !updateAvailable;
    updateButton.disabled = dirty || offline || updateRequested;
    byId("pwa-update-detail").hidden = !dirty;
  }

  function offerUpdate(worker) {
    waitingWorker = worker;
    updateAvailable = true;
    renderUpdate();
  }

  updateButton?.addEventListener("click", () => {
    if (hasUnsavedWork() || offline || updateRequested) return;
    if (!waitingWorker || waitingWorker.state === "activated") {
      location.reload();
      return;
    }
    updateRequested = true;
    renderUpdate();
    waitingWorker.postMessage({ type: "SKIP_WAITING" });
  });
  if (updateNotice) document.addEventListener("input", renderUpdate);

  serviceWorker?.addEventListener("controllerchange", () => {
    const isUpdate = hasController;
    hasController = Boolean(serviceWorker.controller);
    if (updateRequested && !hasUnsavedWork() && !offline) {
      location.reload();
      return;
    }
    updateRequested = false;
    if (isUpdate) offerUpdate(null);
  });

  async function registerWorker() {
    if (!serviceWorker) return;
    try {
      const registration = await serviceWorker.register("/service-worker.js", { scope: "/", updateViaCache: "none" });
      if (registration.waiting && serviceWorker.controller) offerUpdate(registration.waiting);
      registration.addEventListener("updatefound", () => {
        const worker = registration.installing;
        worker?.addEventListener("statechange", () => {
          if (worker.state === "installed" && serviceWorker.controller) offerUpdate(worker);
          if (worker.state === "redundant" && worker === waitingWorker) {
            waitingWorker = null;
            updateAvailable = false;
            updateRequested = false;
            renderUpdate();
          }
        });
      });
      let lastCheck = Date.now();
      function checkForUpdate() {
        if (document.hidden || offline || Date.now() - lastCheck < 60_000) return;
        lastCheck = Date.now();
        void registration.update().catch(() => {});
      }
      document.addEventListener("visibilitychange", checkForUpdate);
      addEventListener("urutau:online", checkForUpdate);
      if (updateNotice) setInterval(checkForUpdate, 30 * 60_000);
    } catch {
      // Online features are still available without a Service Worker.
    }
  }

  function setOffline(unavailable) {
    if (offline === unavailable) return;
    offline = unavailable;
    if (offlineDialog) {
      document.body.classList.toggle("pwa-offline-active", offline);
      if (offline) offlineDialog.showModal();
      else offlineDialog.close();
    }
    renderUpdate();
    if (!offline && !offlinePageRetry) dispatchEvent(new Event("urutau:online"));
  }

  async function networkAvailable() {
    const timeout = new AbortController();
    const timer = setTimeout(() => timeout.abort(), 5000);
    try {
      const response = await fetch("/pwa.js?connection-check=1", {
        credentials: "omit", cache: "no-store", signal: timeout.signal
      });
      return response.ok && /javascript/i.test(response.headers.get("Content-Type") ?? "");
    } catch {
      return false;
    } finally {
      clearTimeout(timer);
    }
  }

  async function checkConnection() {
    if (!connectionCheck) connectionCheck = networkAvailable().finally(() => { connectionCheck = null; });
    const reachable = await connectionCheck && navigator.onLine;
    setOffline(!reachable);
    return reachable;
  }

  function setRetryAction(button, status, navigate) {
    button?.addEventListener("click", async () => {
      button.disabled = true;
      status.textContent = "Verificando conexão…";
      if (await checkConnection()) {
        if (navigate) location.replace("/");
        else status.textContent = "";
      } else {
        status.textContent = "A conexão ainda está indisponível. Tente novamente.";
      }
      button.disabled = false;
    });
  }

  offlineDialog?.addEventListener("cancel", event => event.preventDefault());
  setRetryAction(byId("pwa-offline-retry"), byId("pwa-offline-status"), false);
  setRetryAction(offlinePageRetry, byId("pwa-retry-status"), true);
  addEventListener("offline", () => setOffline(true));
  if (!offlinePageRetry) addEventListener("online", () => void checkConnection());
  if (!navigator.onLine) setOffline(true);

  window.urutauPwa = Object.freeze({
    isOffline: () => offline,
    checkConnection,
    renderUpdate,
    setUpdateGuard(guard) {
      hasUnsavedWork = guard;
      renderUpdate();
    }
  });
  void registerWorker();
})();
