
"use strict";

const assistantUi = { busy: false, messages: [], frame: 0 };
const assistantSections = {
  overview: "OVERVIEW", manual: "MANUAL", ai: "AI", history: "HISTORY", orders: "ORDERS",
  customers: "CUSTOMERS", catalog: "CATALOG", prices: "PRICES", settings: "SETTINGS"
};

function assistantHasConversation() {
  return assistantUi.busy || assistantUi.messages.length > 0 || Boolean(byId("assistant-message").value.trim());
}

function renderAssistant(messages) {
  assistantUi.messages = messages;
  const log = byId("assistant-log");
  const greeting = { role: "assistant", text: "Olá! Sou a URUTAU AI, sua assistente administrativa. Como posso ajudar você hoje?" };
  log.replaceChildren(...[greeting, ...messages].map(message => {
    const row = element("div", `chat-row ${message.role === "admin" ? "admin" : "assistant"}`);
    const bubble = element("div", "chat-bubble");
    bubble.append(element("p", "", message.text));
    row.append(element("strong", "", message.role === "admin" ? "Você" : "URUTAU AI"), bubble);
    return row;
  }));
  log.scrollTop = log.scrollHeight;
  window.urutauPwa?.renderUpdate();
}

function updateAssistantContext() {
  byId("assistant-context").textContent = state.route?.title ?? "Visão geral";
}

async function assistantRequest(url, options, progress) {
  if (assistantUi.busy) return false;
  assistantUi.busy = true;
  byId("assistant-send").disabled = byId("assistant-new").disabled = true;
  byId("assistant-feedback").textContent = progress;
  window.urutauPwa?.renderUpdate();
  try {
    renderAssistant(await api(url, options));
    byId("assistant-feedback").textContent = "";
    return true;
  } catch (error) {
    byId("assistant-feedback").textContent = error.message;
    return false;
  } finally {
    assistantUi.busy = false;
    byId("assistant-send").disabled = byId("assistant-new").disabled = false;
    window.urutauPwa?.renderUpdate();
  }
}

function positionAssistant() {
  if (assistantUi.frame) return;
  assistantUi.frame = requestAnimationFrame(() => {
    assistantUi.frame = 0;
    const button = byId("assistant-launch");
    if (button.hidden) return;
    button.style.setProperty("--assistant-offset", "0px");
    if (innerWidth >= 960) return;
    const box = button.getBoundingClientRect();
    if (!box.width) return;
    const xValues = [box.left + 4, box.left + box.width / 2, box.right - 4];
    const important = "button,a,input,select,textarea,summary,[role='button'],.summary-values,.summary-total,.summary-actions,.sidebar";
    const overlaps = (y) => xValues.some(x => [y + 4, y + box.height / 2, y + box.height - 4].some(point =>
      document.elementsFromPoint(x, point).some(node => node !== button && !button.contains(node) && node.closest(important))));
    for (let offset = 0; box.top - offset >= 64; offset += 20) {
      if (!overlaps(box.top - offset)) {
        button.style.setProperty("--assistant-offset", `${offset}px`);
        return;
      }
    }
  });
}

function bindAssistant() {
  const dialog = byId("assistant-panel");
  const field = byId("assistant-message");
  renderAssistant([]);
  byId("assistant-launch").addEventListener("click", () => {
    updateAssistantContext();
    dialog.showModal();
    byId("assistant-title").focus({ preventScroll: true });
    void assistantRequest("/api/assistant", {}, "Carregando conversa…");
  });
  for (const id of ["assistant-close", "assistant-minimize"]) {
    byId(id).addEventListener("click", () => dialog.close());
  }
  byId("assistant-new").addEventListener("click", async () => {
    if (await assistantRequest("/api/assistant", { method: "DELETE" }, "Iniciando nova conversa…")) {
      field.value = "";
      field.focus();
    }
  });
  byId("assistant-form").addEventListener("submit", async (event) => {
    event.preventDefault();
    const message = field.value.trim();
    if (!message) return;
    const section = assistantSections[state.route?.page] ?? "OVERVIEW";
    if (await assistantRequest("/api/assistant/messages", { method: "POST", body: { section, message } }, "Consultando…")
        && field.value.trim() === message) {
      field.value = "";
      if (dialog.open) field.focus();
    }
    window.urutauPwa?.renderUpdate();
  });
  field.addEventListener("input", () => window.urutauPwa?.renderUpdate());
  addEventListener("scroll", positionAssistant, { passive: true });
  addEventListener("resize", positionAssistant);
  window.visualViewport?.addEventListener("resize", positionAssistant);
  if (typeof ResizeObserver === "function") new ResizeObserver(positionAssistant).observe(byId("main"));
}
