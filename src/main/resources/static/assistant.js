
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
    for (const card of message.cards ?? []) bubble.append(assistantCard(card));
    if (message.proposal) {
      const proposal = message.proposal;
      const card = assistantCard(proposal.card);
      card.append(element("p", "draft-status", proposal.status === "Pendente"
        ? "Aguardando confirmação. A proposta é válida por cinco minutos." : proposal.status));
      if (proposal.status === "Pendente") {
        const decisions = element("div", "draft-actions");
        for (const [label, confirm] of [["Confirmar", true], ["Cancelar", false]]) {
          const button = element("button", `button ${confirm ? "primary" : "secondary"}`, label);
          button.type = "button";
          button.addEventListener("click", () => decideAssistantProposal(proposal, confirm));
          decisions.append(button);
        }
        card.append(decisions);
      }
      bubble.append(card);
    }
    row.append(element("strong", "", message.role === "admin" ? "Você" : "URUTAU AI"), bubble);
    return row;
  }));
  log.scrollTop = log.scrollHeight;
  window.urutauPwa?.renderUpdate();
}

function assistantCard(card) {
  const section = element("section", "assistant-card");
  section.append(element("h3", "", card.title));
  if (card.details.length) {
    const list = element("ul");
    for (const detail of card.details) list.append(element("li", "", detail));
    section.append(list);
  }
  if (card.actions?.length) {
    const actions = element("div", "draft-actions");
    for (const item of card.actions) {
      if (item.kind === "download" && /^\d+$/u.test(item.target)) {
        actions.append(link(item.label, issuedPdfPath(item.target, true), "button secondary"));
      } else if (item.kind === "whatsapp" && /^\d{10,15}$/u.test(item.target)) {
        const anchor = link(item.label, `https://wa.me/${item.target}`, "button secondary");
        anchor.target = "_blank";
        anchor.rel = "noopener noreferrer";
        actions.append(anchor);
      } else {
        const button = element("button", "button secondary", item.label);
        button.type = "button";
        button.addEventListener("click", () => void openAssistantAction(item));
        actions.append(button);
      }
    }
    section.append(actions);
  }
  return section;
}

async function openAssistantAction({ kind, target }) {
  byId("assistant-panel").close();
  if ((kind === "draft" || kind === "preview") && /^[0-9a-f-]{36}$/u.test(target)) {
    await openDraft(target, draftUi.current?.id === target && !draftIsDirty());
    if (kind === "preview" && draftUi.current?.id === target) await showPdfPreview();
    return;
  }
  if (kind === "nav" && Object.hasOwn(routes, target)) {
    location.hash = target;
    return;
  }
  if (!/^\d+$/u.test(target)) return;
  const id = Number(target);
  if (!Number.isSafeInteger(id) || id < 1) return;
  if (kind === "pdf") return showIssuedPdf(id);
  if (kind === "share") return openQuoteShare(id);
  if (kind === "product") {
    history.pushState(null, "", "#catalogo");
    await navigate();
    await loadCatalog();
    const product = state.catalog?.find(model => model.id === id);
    if (product) openCatalogEditor(product);
    return;
  }
  if (kind === "customer" || kind === "order") {
    history.pushState(null, "", kind === "customer" ? "#clientes" : "#pedidos");
    await navigate();
    if (kind === "customer") await openCustomer(id);
    else await openCommercialOrder(id, "detail");
  }
}

async function decideAssistantProposal(proposal, confirm) {
  if (!await assistantRequest(`/api/assistant/proposals/${encodeURIComponent(proposal.id)}/decision`,
    { method: "POST", body: { confirm } }, confirm ? "Confirmando operação…" : "Cancelando proposta…")) return;
  const current = assistantUi.messages.find(message => message.proposal?.id === proposal.id)?.proposal;
  if (current?.status !== "Confirmada") return;
  await loadCatalog();
  const draft = draftUi.current;
  if (draft?.version != null) {
    try {
      const reopened = await api(`/api/quote-drafts/${draft.id}`);
      if (reopened.summary.version !== draft.version) {
        draft.conflict = true;
        updateDraftStatus();
      }
    } catch (error) {
      if (error.status === 404 && draftUi.current === draft && !draftIsDirty()) completeDraftIssue();
    }
  }
  if (state.route?.page === "history") void loadHistory(0);
  if (state.route?.page === "orders") void loadOrders(0);
  if (state.route?.page === "customers") void loadCustomers(0);
  if (state.route?.page === "manual" || state.route?.page === "ai") void updatePreview();
}

const assistantSuggestions = {
  customers: ["Liste os clientes ativos", "Quais clientes possuem pedidos em produção?"],
  orders: ["Quais pedidos estão em produção?", "Quais pedidos estão atrasados?"],
  catalog: ["Quais modelos estão desativados?", "Mostre a tabela de preços"],
  prices: ["Qual é o mínimo de lojista?", "Mostre a tabela de preços"],
  history: ["Mostre os orçamentos pendentes", "Qual foi o orçamento de maior valor deste mês?"]
};

function updateAssistantContext() {
  byId("assistant-context").textContent = state.route?.title ?? "Visão geral";
  const suggestions = assistantSuggestions[state.route?.page] ?? ["Quais rascunhos tenho?", "Quanto vendemos neste mês?"];
  byId("assistant-suggestions").replaceChildren(...suggestions.map(text => {
    const button = element("button", "text-button", text);
    button.type = "button";
    button.addEventListener("click", () => {
      const field = byId("assistant-message");
      field.value = text;
      field.focus();
    });
    return button;
  }));
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
    if (quotePage(state.route?.page) && draftIsDirty() && !await flushDraft()) {
      byId("assistant-feedback").textContent = "Sincronize o rascunho antes de usar a assistente nesta página.";
      return;
    }
    const draftId = quotePage(state.route?.page) && draftUi.current?.version != null ? draftUi.current.id : null;
    if (await assistantRequest("/api/assistant/messages", { method: "POST", body: { section, message, draftId } }, "Consultando…")
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
