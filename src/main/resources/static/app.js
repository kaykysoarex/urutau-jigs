"use strict";

const byId = id => document.getElementById(id);
const routes = {
  "visao-geral": { page: "overview", title: "Visão geral", group: "Comercial" },
  "novo-orcamento": { page: "manual", title: "Novo orçamento manual", group: "Comercial" },
  "novo-orcamento-ia": { page: "ai", title: "Novo orçamento com IA", group: "Comercial" },
  clientes: { page: "customers", title: "Clientes", group: "Comercial" },
  pedidos: { page: "orders", title: "Pedidos", group: "Comercial" },
  producao: { page: "production", title: "Produção", group: "Operacional" },
  "meus-orcamentos": { page: "history", title: "Histórico de orçamentos", group: "Comercial" },
  catalogo: { page: "catalog", title: "Catálogo de iscas", group: "Administração" },
  precos: { page: "prices", title: "Tabela de preços", group: "Administração" },
  configuracoes: { page: "settings", title: "Configurações", group: "Administração" }
};
const state = {
  session: null,
  route: null,
  catalog: null,
  catalogError: null,
  catalogLoad: null,
  threshold: 30,
  draft: [],
  editingItem: null,
  editingQuantity: null,
  selectedColors: new Map(),
  preview: null,
  previewError: null,
  previewLoading: false,
  previewRevision: 0,
  pdfPreviewRequest: null,
  pdfPreviewUrl: null,
  issuedViewer: null,
  quoteShare: null,
  issueBusy: false,
  issueConfirmation: null,
  quoteAttempt: null,
  historyRevision: 0,
  catalogModel: null,
  catalogVariants: [],
  catalogSaving: false,
  priceEdits: new Map(),
  priceBusy: false,
  settingsBusy: false,
  conversation: null,
  aiOnline: false,
  aiStatusLoading: false,
  aiBusy: false
};
const numberFormat = new Intl.NumberFormat("pt-BR");
const moneyFormat = new Intl.NumberFormat("pt-BR", { style: "currency", currency: "BRL" });
const dateFormat = new Intl.DateTimeFormat("pt-BR", { dateStyle: "short", timeZone: "America/Sao_Paulo" });
const currentDateFormat = new Intl.DateTimeFormat("pt-BR", { day: "numeric", month: "long", year: "numeric", timeZone: "America/Sao_Paulo" });
const unitLabel = value => `${numberFormat.format(value)} ${value === 1 ? "unidade" : "unidades"}`;
const quoteNumber = value => String(value).padStart(5, "0");
const priceTierLabel = tier => tier === "LOJISTA" ? "Lojista" : tier === "CONSUMIDOR_FINAL" ? "Consumidor final" : "A definir";
const keyForVariant = ({ weight, color }) => `${weight}/${color}`;
const activeProducts = () => (state.catalog ?? []).filter(product => product.active && product.variants.some(variant => variant.active));
const maxItemQuantity = 100000;
const maxQuoteItems = 250;
const drawerLayout = matchMedia("(max-width: 940px), (pointer: coarse) and (max-height: 560px)");

class RequestError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

async function api(url, options = {}) {
  if (window.urutauPwa?.isOffline()) throw new RequestError(0, "Você está sem conexão.");
  const headers = { Accept: options.pdf ? "application/pdf, application/json" : "application/json" };
  const request = { method: options.method ?? "GET", headers, credentials: "same-origin", signal: options.signal };
  if (request.method !== "GET" && state.session?.csrfHeader) {
    headers[state.session.csrfHeader] = state.session.csrfToken;
  }
  if (options.body !== undefined) {
    headers["Content-Type"] = "application/json";
    request.body = JSON.stringify(options.body);
  }
  let response;
  try {
    response = await fetch(url, request);
  } catch {
    if (!options.signal?.aborted) void window.urutauPwa?.checkConnection();
    throw new RequestError(0, "Não foi possível conectar ao sistema. Tente novamente.");
  }
  if ([502, 503, 504].includes(response.status)) void window.urutauPwa?.checkConnection();
  if (response.status === 401 || (response.redirected && new URL(response.url).pathname === "/login.html")) {
    location.assign("/login.html");
    throw new RequestError(401, "Entre novamente para continuar.");
  }
  if (response.status === 204) return null;
  const contentType = response.headers.get("content-type");
  const data = contentType?.includes("json") ? await response.json() : null;
  if (!response.ok) {
    const message = response.status === 403 ? "Sua sessão pode ter expirado. Atualize a página e entre novamente, se necessário." : "Não foi possível concluir esta ação.";
    throw new RequestError(response.status, data?.message ?? message);
  }
  if (options.pdf) {
    if (contentType?.split(";")[0].trim() !== "application/pdf") {
      throw new RequestError(response.status, "O sistema não retornou um PDF.");
    }
    const pdf = await response.blob();
    if (!options.pdfName) return pdf;
    const disposition = response.headers.get("Content-Disposition");
    const filename = disposition?.match(/(?:^|;)\s*filename=(?:"([^"]+)"|([^;\s]+))/i);
    return new File([pdf], filename?.[1] ?? filename?.[2] ?? options.pdfName, { type: "application/pdf" });
  }
  return data;
}

function element(tag, className, content) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (content !== undefined) node.textContent = content;
  return node;
}

function icon(name) {
  const namespace = "http://www.w3.org/2000/svg";
  const svg = document.createElementNS(namespace, "svg");
  svg.setAttribute("class", "icon");
  svg.setAttribute("aria-hidden", "true");
  const use = document.createElementNS(namespace, "use");
  use.setAttribute("href", `/icons.svg#${name}`);
  svg.append(use);
  return svg;
}

function action(label, symbol, handler, variant = "") {
  const button = element("button", `icon-button ${variant}`.trim());
  button.type = "button";
  button.title = label;
  button.setAttribute("aria-label", label);
  button.append(icon(symbol));
  button.addEventListener("click", handler);
  return button;
}

function link(label, href, className = "text-link") {
  const anchor = element("a", className, label);
  anchor.href = href;
  return anchor;
}

function issuedPdfPath(number, download = false) {
  return `/api/quotes/${number}/pdf${download ? "?download=true" : ""}`;
}

function pdfLink(number, download, symbolOnly = false) {
  const label = download ? "Baixar PDF" : "Visualizar PDF";
  const anchor = link(label, issuedPdfPath(number, download), symbolOnly ? "icon-button" : "text-link");
  if (!download) {
    anchor.target = "_blank";
    anchor.rel = "noopener";
    anchor.setAttribute("aria-haspopup", "dialog");
    anchor.addEventListener("click", event => viewIssuedPdf(event, number));
  }
  if (symbolOnly) {
    const actionText = element("span", "pdf-action-label", label);
    actionText.setAttribute("aria-hidden", "true");
    anchor.replaceChildren(icon(download ? "download" : "eye"), actionText);
    anchor.title = label;
    anchor.setAttribute("aria-label", `${label} do orçamento ${quoteNumber(number)}`);
  }
  return anchor;
}

function viewIssuedPdf(event, number) {
  if (event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
  event.preventDefault();
  void showIssuedPdf(number);
}

function quoteShareButton(number, clientName) {
  const button = element("button", "button whatsapp-button");
  button.type = "button";
  button.setAttribute("aria-haspopup", "dialog");
  button.append(icon("whatsapp"), element("span", "", "Compartilhar pelo WhatsApp"));
  button.addEventListener("click", () => openQuoteShare(number, clientName));
  return button;
}

function emptyState(title, description, symbol, extra) {
  const result = element("div", "empty-state");
  const glyph = element("span", "empty-icon");
  glyph.append(icon(symbol));
  result.append(glyph, element("h3", "", title), element("p", "", description));
  if (extra) result.append(extra);
  return result;
}

function table(caption, headings, rows, className = "") {
  const wrapper = element("div", "scroll-table");
  const grid = element("table", `data-table ${className}`.trim());
  // Explicit roles retain table navigation when the mobile styles display rows as cards.
  grid.setAttribute("role", "table");
  grid.setAttribute("aria-label", caption);
  const headGroup = grid.createTHead();
  headGroup.setAttribute("role", "rowgroup");
  const head = headGroup.insertRow();
  head.setAttribute("role", "row");
  headings.forEach(([label, style]) => {
    const header = element("th", style, label);
    header.setAttribute("role", "columnheader");
    header.scope = "col";
    head.append(header);
  });
  const body = grid.createTBody();
  body.setAttribute("role", "rowgroup");
  rows.forEach(cells => {
    const row = body.insertRow();
    row.setAttribute("role", "row");
    cells.forEach(([value, style], index) => {
      const cell = row.insertCell();
      cell.setAttribute("role", "cell");
      cell.dataset.label = headings[index][0];
      if (style) cell.className = style;
      if (value instanceof Node) cell.append(value);
      else cell.textContent = value;
    });
  });
  wrapper.append(grid);
  return wrapper;
}

function captioned(primary, secondary) {
  const group = element("div");
  group.append(element("strong", "cell-name", primary), element("span", "cell-secondary", secondary));
  return group;
}

function setOptions(select, choices, placeholder, value) {
  const entries = [["", placeholder], ...choices];
  if (select.options.length !== entries.length || entries.some(([id, label], index) => {
    const option = select.options[index];
    return option.value !== String(id) || option.text !== label;
  })) {
    select.replaceChildren(...entries.map(([id, label]) => new Option(label, id)));
  }
  const requested = value === undefined ? "" : String(value);
  const next = entries.some(([id]) => String(id) === requested) ? requested : "";
  if (select.value !== next) select.value = next;
}

function formFeedback(id, message) {
  const feedback = byId(id);
  feedback.textContent = message || "";
  feedback.hidden = !message;
}

let toastTimer;
function toast(message) {
  const node = byId("toast");
  clearTimeout(toastTimer);
  node.textContent = message;
  node.hidden = false;
  toastTimer = setTimeout(() => { node.hidden = true; }, 5000);
}

function navOpen(open, restoreFocus = false) {
  const sidebar = byId("sidebar");
  const toggle = byId("nav-toggle");
  const background = document.querySelector(".body-shell");
  open = drawerLayout.matches && open;
  sidebar.classList.toggle("is-open", open);
  sidebar.inert = drawerLayout.matches && !open;
  background.inert = open;
  document.querySelector(".skip-link").inert = open;
  byId("nav-overlay").hidden = !open;
  toggle.setAttribute("aria-expanded", String(open));
  if (open) {
    sidebar.setAttribute("role", "dialog");
    sidebar.setAttribute("aria-modal", "true");
    byId("nav-close").focus({ preventScroll: true });
  } else {
    sidebar.removeAttribute("role");
    sidebar.removeAttribute("aria-modal");
    if (restoreFocus && drawerLayout.matches) toggle.focus({ preventScroll: true });
  }
}

function drawerKeydown(event) {
  const sidebar = byId("sidebar");
  if (!sidebar.classList.contains("is-open") || document.querySelector("dialog[open]")) return;
  if (event.key === "Escape") {
    event.preventDefault();
    navOpen(false, true);
  } else if (event.key === "Tab") {
    const focusable = [...sidebar.querySelectorAll("a[href], button:not(:disabled)")]
      .filter(control => control.getClientRects().length);
    const first = focusable[0];
    const last = focusable.at(-1);
    if ((event.shiftKey && document.activeElement === first) || (!event.shiftKey && document.activeElement === last)) {
      event.preventDefault();
      (event.shiftKey ? last : first).focus();
    }
  }
}

function quotePage(page) {
  return page === "manual" || page === "ai";
}

let manualEditor;

function mountManualEditor(visible) {
  if (!visible) {
    manualEditor?.remove();
    return;
  }
  if (byId("item-form")) return;
  const firstMount = !manualEditor;
  if (firstMount) manualEditor = byId("manual-editor-template").content.firstElementChild.cloneNode(true);
  byId("manual-editor-slot").append(manualEditor);
  if (firstMount) bindManualEditor();
}

function mountQuoteWorkspace(page) {
  mountManualEditor(page === "manual");
  quoteCustomerPicker?.setRegistrationAllowed(page === "manual");
  const layout = byId("manual-layout");
  const host = byId(page === "ai" ? "ai-workspace" : "manual-workspace");
  if (layout.parentElement !== host) host.append(layout);
  const interpretation = byId("quote-interpretation");
  interpretation.hidden = page !== "ai";
  if (page === "ai" && byId("ai-interpreter").parentElement !== interpretation) {
    interpretation.append(byId("ai-interpreter"));
  } else if (page === "manual" && byId("ai-interpreter").parentElement !== byId("page-ai")) {
    byId("page-ai").append(byId("ai-interpreter"));
  }
}

async function navigate() {
  const requestedHash = location.hash;
  const hash = requestedHash === "#urutau-ai" ? "#novo-orcamento-ia" : requestedHash;
  const route = routes[hash.slice(1)] ?? routes["novo-orcamento"];
  if (quotePage(route.page)) {
    const source = route.page === "ai" ? "IA" : "MANUAL";
    if (!state.session) draftUi.source = source;
    else if (draftUi.source !== source && !await switchQuoteWorkspace(source)) {
      if (location.hash === requestedHash) history.replaceState(null, "", state.routeHash || quoteModeHash(draftUi.source));
      return;
    }
    if (location.hash !== requestedHash) return void navigate();
    mountQuoteWorkspace(route.page);
  }
  state.routeHash = hash;
  const pageChanged = state.route?.page !== route.page;
  if (pageChanged && state.route?.page === "production") leaveProduction();
  state.route = route;
  const previewDialog = byId("preview-pdf-dialog");
  if (!quotePage(route.page) && previewDialog.open) previewDialog.close();
  if (pageChanged) {
    for (const id of ["quote-share-dialog", "issued-pdf-dialog"]) {
      if (byId(id).open) byId(id).close();
    }
    closeQuoteConfirmation(false);
  }
  document.title = `${route.title} | Urutau Jigs`;
  byId("breadcrumb-group").textContent = route.group;
  byId("breadcrumb-page").textContent = route.title;
  updateAssistantContext();
  positionAssistant();
  document.querySelectorAll("[data-view]").forEach(node => { node.hidden = node.dataset.view !== route.page; });
  document.querySelectorAll("[data-page]").forEach(node => {
    if (node.dataset.page === route.page) node.setAttribute("aria-current", "page");
    else node.removeAttribute("aria-current");
  });
  navOpen(false);
  if (pageChanged) {
    window.scrollTo(0, 0);
    byId("main").focus({ preventScroll: true });
  }
  if (!state.session) return;
  if (quotePage(route.page)) {
    renderManualCatalog();
    void loadCatalog();
    void updatePreview();
    void refreshDraftList();
  }
  if (route.page === "history") void loadHistory(0);
  if (route.page === "orders") void loadOrders(0);
  if (route.page === "production") void refreshProduction(pageChanged ? 0 : productionUi.page);
  if (route.page === "customers") void loadCustomers(0);
  if (route.page === "catalog") { renderCatalog(); void loadCatalog(); }
  if (route.page === "prices") { renderPriceModels(); void loadCatalog(); }
  if (route.page === "settings") void loadSettings();
  if (route.page === "ai") void checkAI();
}

async function loadCatalog() {
  if (state.catalogLoad) return state.catalogLoad;
  state.catalogLoad = (async () => {
    try {
      state.catalog = await api("/api/catalog");
      state.catalogError = null;
    } catch (error) {
      state.catalogError = error.message;
    } finally {
      state.catalogLoad = null;
      renderManualCatalog();
      renderCatalog();
      renderPriceModels();
    }
  })();
  return state.catalogLoad;
}

function renderManualCatalog(pick = {}) {
  if (!byId("item-form")) return;
  const models = activeProducts();
  const modelSelect = byId("item-model");
  const weightSelect = byId("item-weight");
  const modelId = pick.modelId ?? modelSelect.value;
  const weight = pick.weight ?? weightSelect.value;
  setOptions(modelSelect, models.map(model => [model.id, model.name]), state.catalog ? "Selecione o modelo" : state.catalogError ? "Catálogo indisponível" : "Carregando…", modelId);
  const selected = models.find(model => String(model.id) === modelSelect.value);
  const active = selected?.variants.filter(variant => variant.active) ?? [];
  const weights = [...new Set(active.map(variant => variant.weight))].sort((a, b) => a - b);
  setOptions(weightSelect, weights.map(grams => [grams, `${grams} g`]), "Selecione", weight);
  const colors = active.filter(variant => String(variant.weight) === weightSelect.value).sort((a, b) => a.color.localeCompare(b.color, "pt-BR"));
  modelSelect.disabled = !models.length || state.issueBusy;
  weightSelect.disabled = !selected || state.issueBusy;
  renderItemColors(colors);

  const note = byId("catalog-message");
  note.replaceChildren();
  note.hidden = true;
  if (state.catalogError && !state.catalog) {
    const retry = element("button", "text-button", "Tentar novamente");
    retry.type = "button";
    retry.addEventListener("click", () => void loadCatalog());
    note.append(element("span", "", state.catalogError), retry);
    note.hidden = false;
  } else if (state.catalog && !models.length) {
    note.append(element("span", "", "O orçamento precisa de pelo menos uma combinação ativa."), link("Abrir catálogo", "#catalogo"));
    note.hidden = false;
  }
}

function renderItemColors(colors) {
  const grid = byId("item-colors");
  const editing = state.editingItem !== null;
  const available = new Set(colors.map(color => color.id));
  for (const id of state.selectedColors.keys()) {
    if (!available.has(id)) state.selectedColors.delete(id);
  }
  const layoutKey = JSON.stringify([editing, colors.map(({ id, color }) => [id, color])]);
  if (grid.dataset.layoutKey !== layoutKey) {
    grid.replaceChildren(...colors.map(createItemColor));
    grid.dataset.layoutKey = layoutKey;
  }
  grid.hidden = colors.length === 0;
  byId("item-colors-empty").hidden = colors.length > 0;
  byId("item-bulk-tools").hidden = editing;
  byId("item-color-summary").hidden = editing;
  updateItemControls();

  function createItemColor({ id, color }) {
    const option = element("div", "item-color-option");
    const label = element("label", "item-color-check");
    const choice = element("input");
    choice.type = editing ? "radio" : "checkbox";
    choice.name = "quote-color";
    choice.value = id;
    label.append(choice, element("span", "item-color-code", `Cor ${color}`));
    const quantity = element("input", "item-color-quantity");
    quantity.type = "number";
    quantity.inputMode = "numeric";
    quantity.min = "1";
    quantity.max = String(maxItemQuantity);
    quantity.step = "1";
    quantity.setAttribute("aria-label", `Quantidade da cor ${color}`);
    option.append(label, quantity);
    return option;
  }
}

function readItemQuantity(value) {
  return /^\d+$/u.test(value) && Number(value) >= 1 && Number(value) <= maxItemQuantity ? Number(value) : null;
}

function updateItemControls(message = "") {
  const fields = byId("item-colors").children;
  const count = state.selectedColors.size;
  const locked = state.issueBusy;
  const defaultQuantity = byId("item-quantity");
  let total = 0;
  let hasInvalidQuantity = false;
  for (const field of fields) {
    const choice = field.querySelector("[name='quote-color']");
    const quantity = field.querySelector(".item-color-quantity");
    const raw = state.selectedColors.get(Number(choice.value));
    const selected = raw !== undefined;
    const amount = selected ? readItemQuantity(raw) : null;
    choice.checked = selected;
    choice.disabled = locked;
    quantity.disabled = locked || !selected;
    if (quantity.value !== (raw ?? "")) quantity.value = raw ?? "";
    quantity.setAttribute("aria-invalid", String(selected && amount === null));
    total += amount ?? 0;
    hasInvalidQuantity ||= selected && amount === null;
  }
  defaultQuantity.disabled = locked || !fields.length || state.editingItem !== null;
  const validDefault = readItemQuantity(defaultQuantity.value) !== null;
  defaultQuantity.setAttribute("aria-invalid", String(!defaultQuantity.disabled && !validDefault));
  byId("item-select-all").disabled = locked || count === fields.length;
  byId("item-clear-all").disabled = locked || !count;
  byId("item-apply-quantity").disabled = locked || !count || !validDefault;
  byId("item-selected-count").textContent = numberFormat.format(count);
  byId("item-selected-total").textContent = hasInvalidQuantity ? "—" : numberFormat.format(total);
  byId("item-add").disabled = locked || !count || hasInvalidQuantity;
  formFeedback("item-error", message || (hasInvalidQuantity ? "Informe uma quantidade inteira entre 1 e 100.000 para cada cor selecionada." : ""));
  window.urutauPwa?.renderUpdate();
}

function changeColorSelection(event) {
  const choice = event.target;
  if (choice.name !== "quote-color") return;
  const id = Number(choice.value);
  if (state.editingItem !== null) state.selectedColors.clear();
  if (choice.checked) state.selectedColors.set(id, state.editingQuantity ?? byId("item-quantity").value);
  else state.selectedColors.delete(id);
  updateItemControls();
}

function changeColorQuantity(event) {
  const quantity = event.target;
  if (!quantity.matches(".item-color-quantity")) return;
  const id = Number(quantity.closest(".item-color-option").querySelector("[name='quote-color']").value);
  if (state.editingItem !== null) state.editingQuantity = quantity.value;
  state.selectedColors.set(id, quantity.value);
  updateItemControls();
}

function pickVariant(id) {
  for (const product of activeProducts()) {
    const match = product.variants.find(variant => variant.active && variant.id === id);
    if (match) return { modelId: product.id, model: product.name, ...match };
  }
  return null;
}

function resetItemEditor() {
  clearDraftEditor();
  state.editingItem = null;
  state.editingQuantity = null;
  state.selectedColors.clear();
  if (!byId("item-form")) return;
  byId("item-quantity").value = "1";
  byId("item-add").querySelector("span").textContent = "Adicionar ao orçamento";
  byId("item-cancel").hidden = true;
  renderManualCatalog({ modelId: "", weight: "" });
}

function editItem(id) {
  const draft = state.draft.find(item => item.variantId === id);
  if (!draft || state.issueBusy) return;
  const variant = pickVariant(id);
  clearDraftEditor();
  state.editingItem = id;
  state.editingQuantity = String(draft.quantity);
  state.selectedColors.clear();
  if (variant) state.selectedColors.set(id, state.editingQuantity);
  byId("item-add").querySelector("span").textContent = "Salvar item";
  byId("item-cancel").hidden = false;
  renderManualCatalog(variant ? { modelId: variant.modelId, weight: variant.weight } : { modelId: "", weight: "" });
  if (!variant) formFeedback("item-error", "Selecione uma combinação ativa para substituir este item.");
  const quantity = byId("item-colors").querySelector(".item-color-quantity:not(:disabled)");
  (quantity ?? byId("item-model")).focus();
  queueDraftSave();
}

function draftChanged() {
  queueDraftSave();
  void updatePreview();
}

function addItems(event) {
  event.preventDefault();
  if (state.issueBusy || !state.selectedColors.size) return;
  const wasEditing = state.editingItem !== null;
  const nextDraft = new Map(state.draft.map(item => [item.variantId, item]));
  nextDraft.delete(state.editingItem);
  let replacement = null;
  for (const [variantId, raw] of state.selectedColors) {
    const variant = pickVariant(variantId);
    const amount = readItemQuantity(raw);
    if (!variant || amount === null) {
      updateItemControls("Selecione uma combinação ativa e informe uma quantidade inteira entre 1 e 100.000.");
      return;
    }
    const existing = nextDraft.get(variantId);
    const quantity = (existing?.quantity ?? 0) + amount;
    if (quantity > maxItemQuantity) {
      updateItemControls("Cada combinação admite até 100.000 unidades por orçamento.");
      return;
    }
    const item = { variantId, model: variant.model, weight: variant.weight, color: variant.color, quantity };
    if (wasEditing && !existing) replacement = item;
    else nextDraft.set(variantId, item);
  }
  const items = [...nextDraft.values()];
  if (replacement) items.splice(state.draft.findIndex(item => item.variantId === state.editingItem), 0, replacement);
  if (items.length > maxQuoteItems) {
    updateItemControls("Cada orçamento admite até 250 combinações.");
    return;
  }
  state.draft = items;
  if (wasEditing) resetItemEditor();
  else {
    state.selectedColors.clear();
    updateItemControls();
  }
  draftChanged();
  byId(wasEditing ? "item-model" : "item-select-all").focus();
}

function removeItem(id) {
  if (state.issueBusy) return;
  state.draft = state.draft.filter(item => item.variantId !== id);
  if (state.editingItem === id) resetItemEditor();
  draftChanged();
  byId("item-model").focus();
}

function renderManualRows() {
  const target = byId("manual-items");
  if (target) renderQuoteRows(target, state.draft, state.preview, true);
  else renderAIItems();
}

function renderQuoteRows(container, items, preview, editable) {
  if (!items.length) {
    container.replaceChildren(emptyState("Nenhum item adicionado",
      editable ? "Selecione uma combinação no formulário acima para começar." : "Envie uma instrução à URUTAU AI.", "boxes"));
    return;
  }
  const values = new Map((preview?.items ?? []).map(item => [item.variantId, item]));
  const rows = items.map(item => {
    const priced = values.get(item.variantId);
    const cells = [
      [captioned(priced?.model ?? item.model, `${item.weight} g · Cor ${item.color}${item.active === false ? " · Indisponível" : ""}`)],
      [numberFormat.format(item.quantity), "numerical"],
      [priced?.unitPrice != null ? moneyFormat.format(priced.unitPrice) : "—", `numerical${priced && priced.unitPrice == null ? " cell-missing" : ""}`],
      [priced?.subtotal != null ? moneyFormat.format(priced.subtotal) : "—", "numerical"]
    ];
    if (!editable) return cells;
    const controls = element("div", "table-row-actions");
    const edit = action(`Editar ${item.model}, ${item.weight} g, cor ${item.color}`, "edit", () => editItem(item.variantId));
    const remove = action(`Remover ${item.model}, ${item.weight} g, cor ${item.color}`, "trash", () => removeItem(item.variantId), "danger");
    edit.disabled = state.issueBusy;
    remove.disabled = state.issueBusy;
    controls.append(edit, remove);
    cells.push([controls, "cell-actions"]);
    return cells;
  });
  const headers = [["Isca"], ["Qtd.", "numerical"], ["Unitário", "numerical"], ["Subtotal", "numerical"]];
  if (editable) headers.push(["Ações", "cell-actions"]);
  container.replaceChildren(table(editable ? "Itens do orçamento" : "Itens interpretados", headers, rows, "quote-items-table"));
}

function draftItems() {
  return state.draft.map(({ variantId, quantity }) => ({ variantId, quantity }));
}

function readQuoteClient(form) {
  return Object.fromEntries(["name", "phone"].map(name => [name, form.elements.namedItem(name).value.trim()]));
}

async function updatePreview() {
  const revision = ++state.previewRevision;
  const items = draftItems();
  state.preview = null;
  state.previewError = null;
  state.previewLoading = items.length > 0;
  renderManualRows();
  renderSummary();
  if (!items.length) return;
  try {
    const result = await api("/api/quotes/preview", { method: "POST", body: { items } });
    if (revision !== state.previewRevision) return;
    state.preview = result;
    state.threshold = result.wholesaleThreshold;
  } catch (error) {
    if (revision !== state.previewRevision) return;
    state.previewError = error.message;
  } finally {
    if (revision === state.previewRevision) {
      state.previewLoading = false;
      renderManualRows();
      renderSummary();
    }
  }
}

function missingPrices() {
  if (!state.preview) return false;
  return state.preview.total == null || state.preview.items.some(item => item.unitPrice == null);
}

function hasUnreviewedInterpretation() {
  return draftUi.source === "IA" && (hasAiProposal() || draftUi.aiQuestions.length > 0);
}

function renderSummary() {
  const estimate = state.preview;
  const quantity = estimate?.quantity ?? state.draft.reduce((sum, item) => sum + item.quantity, 0);
  const threshold = estimate?.wholesaleThreshold ?? state.threshold;
  const wholesale = estimate?.priceTier === "LOJISTA";
  const remaining = estimate?.remainingForWholesale ?? Math.max(0, threshold - quantity);
  const tier = priceTierLabel(estimate?.priceTier);
  byId("summary-quantity").textContent = unitLabel(quantity);
  byId("summary-tier").replaceChildren(element("span", `tier-pill${wholesale ? " wholesale" : ""}`, tier));
  byId("summary-state").textContent = state.previewLoading ? "Calculando valores…" : "Em elaboração";
  byId("threshold-message").textContent = !quantity ? `Preço de lojista a partir de ${unitLabel(threshold)}.` : remaining ? `Faltam ${unitLabel(remaining)} para o preço de lojista.` : "Quantidade mínima de lojista atingida.";
  byId("threshold-progress").max = threshold;
  byId("threshold-progress").value = Math.min(quantity, threshold);
  byId("summary-total").textContent = !quantity ? moneyFormat.format(0) : estimate?.total == null ? "—" : moneyFormat.format(estimate.total);
  const warning = byId("summary-message");
  warning.replaceChildren();
  warning.hidden = true;
  if (state.previewError) {
    const retry = element("button", "text-button", "Recalcular");
    retry.type = "button";
    retry.addEventListener("click", () => void updatePreview());
    warning.append(element("span", "", `${state.previewError} `), retry);
    warning.hidden = false;
  } else if (hasUnreviewedInterpretation()) {
    warning.append(element("span", "", "Revise os itens interpretados ou resolva as pendências antes de emitir."));
    warning.hidden = false;
  } else if (missingPrices()) {
    warning.append(element("span", "", "Há itens sem preço para a tabela aplicada. "), link("Cadastrar preços", "#precos"));
    warning.hidden = false;
  }
  byId("quote-preview-pdf").disabled = state.issueBusy || state.draft.length === 0;
  const issue = byId("quote-issue");
  const canIssue = estimate && quantity && !missingPrices() && !hasUnreviewedInterpretation();
  issue.disabled = state.issueBusy || Boolean(state.issueConfirmation) || (!state.quoteAttempt && !canIssue);
  issue.querySelector("span").textContent = state.issueBusy ? "Emitindo…"
    : state.quoteAttempt ? "Tentar confirmar emissão" : "Emitir orçamento";
}

function resetPdfPreview() {
  state.pdfPreviewRequest?.abort();
  state.pdfPreviewRequest = null;
  const frame = byId("preview-pdf-frame");
  frame.hidden = true;
  frame.removeAttribute("src");
  byId("preview-pdf-actions").hidden = true;
  byId("preview-pdf-open").removeAttribute("href");
  byId("preview-pdf-download").removeAttribute("href");
  if (state.pdfPreviewUrl) {
    URL.revokeObjectURL(state.pdfPreviewUrl);
    state.pdfPreviewUrl = null;
  }
}

async function showPdfPreview() {
  const dialog = byId("preview-pdf-dialog");
  if (dialog.open || state.issueBusy || !state.draft.length) return;

  const body = { client: readQuoteClient(byId("client-form")), items: draftItems() };
  resetPdfPreview();
  const request = new AbortController();
  state.pdfPreviewRequest = request;
  const status = byId("preview-pdf-status");
  status.classList.remove("form-error");
  status.textContent = "Gerando prévia do PDF…";
  status.hidden = false;
  dialog.showModal();

  try {
    const pdf = await api("/api/quotes/preview/pdf", { method: "POST", body, pdf: true, signal: request.signal });
    if (request.signal.aborted || !dialog.open) return;
    if (!pdf?.size) throw new Error("O sistema retornou um PDF vazio.");
    const url = URL.createObjectURL(pdf);
    state.pdfPreviewUrl = url;
    byId("preview-pdf-open").href = url;
    byId("preview-pdf-download").href = url;
    const frame = byId("preview-pdf-frame");
    frame.src = url;
    frame.hidden = false;
    byId("preview-pdf-actions").hidden = false;
    status.hidden = true;
  } catch (error) {
    if (request.signal.aborted || !dialog.open) return;
    status.classList.add("form-error");
    status.textContent = error.message;
  } finally {
    if (state.pdfPreviewRequest === request) state.pdfPreviewRequest = null;
  }
}

async function fetchIssuedPdf(number, signal) {
  const file = await api(issuedPdfPath(number), {
    pdf: true,
    pdfName: `orcamento-urutau-${quoteNumber(number)}.pdf`,
    signal
  });
  if (!file?.size) throw new Error("O sistema retornou um PDF vazio.");
  return file;
}

function resetIssuedViewer() {
  const viewer = state.issuedViewer;
  if (!viewer) return;
  viewer.request.abort();
  byId("issued-pdf-frame").removeAttribute("src");
  if (viewer.url) URL.revokeObjectURL(viewer.url);
  state.issuedViewer = null;
}

async function showIssuedPdf(number) {
  const dialog = byId("issued-pdf-dialog");
  if (dialog.open) return;
  resetIssuedViewer();
  const viewer = { number, request: new AbortController(), file: null, url: null };
  state.issuedViewer = viewer;
  byId("issued-pdf-title").textContent = `Orçamento nº ${quoteNumber(number)}`;
  byId("issued-pdf-open").href = issuedPdfPath(number);
  byId("issued-pdf-download").href = issuedPdfPath(number, true);
  const frame = byId("issued-pdf-frame");
  frame.hidden = true;
  const status = byId("issued-pdf-status");
  status.classList.remove("form-error");
  status.textContent = "Carregando PDF do orçamento…";
  status.hidden = false;
  dialog.showModal();
  try {
    const file = await fetchIssuedPdf(number, viewer.request.signal);
    if (state.issuedViewer !== viewer || !dialog.open) return;
    viewer.file = file;
    viewer.url = URL.createObjectURL(file);
    frame.src = viewer.url;
    frame.hidden = false;
    status.hidden = true;
  } catch {
    if (state.issuedViewer !== viewer || !dialog.open) return;
    status.classList.add("form-error");
    status.textContent = "Não foi possível carregar o PDF aqui. Tente visualizar em nova aba ou baixar o arquivo.";
  }
}

function quoteShareMessage(clientName) {
  const name = clientName?.trim();
  const greeting = name ? `Olá, ${name}! Tudo bem?` : "Olá! Tudo bem?";
  return `${greeting}\n\nSegue o orçamento solicitado das iscas artesanais URUTAU JIGS.\n\nQualquer dúvida, estamos à disposição.\n\nObrigado pela preferência!\nURUTAU JIGS`;
}

function whatsappPhone(value) {
  const phone = value?.trim() ?? "";
  if (!/^\+?[\d().\s-]+$/u.test(phone)) return "";
  const digits = phone.replace(/\D/g, "");
  const nationalNumber = /^[1-9]{2}(?:[2-5]\d{7}|9\d{8})$/u;
  if (!phone.startsWith("+") && nationalNumber.test(digits)) return `55${digits}`;
  const international = digits.startsWith("00") ? digits.slice(2) : digits;
  if (international.startsWith("55")) return nationalNumber.test(international.slice(2)) ? international : "";
  return (phone.startsWith("+") || digits.startsWith("00")) && /^[1-9]\d{7,14}$/u.test(international) ? international : "";
}

function shareStatus(message, isError = false) {
  const status = byId("quote-share-status");
  status.classList.toggle("form-error", isError);
  status.textContent = message;
}

function updateWhatsappLink(share) {
  const url = new URL(`https://wa.me/${share.phone}`);
  url.searchParams.set("text", byId("quote-share-message").value);
  byId("quote-share-whatsapp").href = url.href;
}

function showWhatsappFallback(share) {
  byId("quote-share-recipient").textContent = share.phone
    ? "Confira se a conversa aberta corresponde ao telefone do orçamento."
    : "Escolha o destinatário no WhatsApp.";
  byId("quote-share-fallback").hidden = false;
  updateWhatsappLink(share);
}

function canShareQuoteFile(file) {
  try {
    return !!file && typeof navigator.share === "function" && navigator.canShare?.({ files: [file] }) === true;
  } catch {
    return false;
  }
}

function resetQuoteShare() {
  state.quoteShare?.request.abort();
  state.quoteShare = null;
  byId("quote-share-message").value = "";
  byId("quote-share-whatsapp").removeAttribute("href");
  byId("quote-share-recipient").textContent = "";
}

function openQuoteShare(number, clientName, file = null) {
  const dialog = byId("quote-share-dialog");
  if (dialog.open) return;
  resetQuoteShare();
  const share = { number, clientName, file, request: new AbortController(), phone: "", edited: false };
  state.quoteShare = share;
  byId("quote-share-title").textContent = `Compartilhar orçamento nº ${quoteNumber(number)}`;
  byId("quote-share-message").value = quoteShareMessage(clientName);
  byId("quote-share-download").href = issuedPdfPath(number, true);
  void prepareQuoteShare(share);
  dialog.showModal();
}

async function prepareQuoteShare(share) {
  const message = byId("quote-share-message");
  const native = byId("quote-share-native");
  const retry = byId("quote-share-retry");
  native.hidden = true;
  native.disabled = true;
  retry.hidden = true;
  byId("quote-share-fallback").hidden = true;
  message.disabled = true;
  byId("quote-share-copy").disabled = true;
  shareStatus("Preparando PDF e mensagem…");
  const signal = share.request.signal;
  const [contact, pdf] = await Promise.allSettled([
    api(`/api/quotes/${share.number}`, { signal }),
    share.file ?? fetchIssuedPdf(share.number, signal)
  ]);
  if (!isCurrentQuoteShare(share)) return;
  const errors = [];
  if (contact.status === "fulfilled") {
    share.phone = whatsappPhone(contact.value?.clientPhone);
    if (!share.edited) message.value = quoteShareMessage(contact.value?.clientName);
  } else {
    errors.push("Não foi possível consultar os dados do cliente.");
  }
  if (pdf.status === "fulfilled") share.file = pdf.value;
  else errors.push("Não foi possível preparar o PDF. Tente novamente ou baixe o arquivo.");
  if (canShareQuoteFile(share.file)) {
    native.hidden = false;
    native.disabled = false;
  } else {
    showWhatsappFallback(share);
  }
  message.disabled = false;
  byId("quote-share-copy").disabled = false;
  retry.hidden = errors.length === 0;
  shareStatus(errors.join(" "), errors.length > 0);
}

function isCurrentQuoteShare(share) {
  return state.quoteShare === share && byId("quote-share-dialog").open;
}

async function shareQuoteNatively() {
  const share = state.quoteShare;
  const button = byId("quote-share-native");
  if (!share?.file || button.disabled) return;
  button.disabled = true;
  shareStatus("");
  try {
    await navigator.share({
      files: [share.file],
      title: `Orçamento nº ${quoteNumber(share.number)}`,
      text: byId("quote-share-message").value
    });
  } catch (error) {
    if (!isCurrentQuoteShare(share)) return;
    if (error.name === "AbortError") {
      shareStatus("Compartilhamento cancelado ou sem aplicativos disponíveis.");
      showWhatsappFallback(share);
    } else {
      button.hidden = true;
      shareStatus("Não foi possível abrir o menu de compartilhamento. Use o WhatsApp Web e anexe o PDF manualmente.", true);
      showWhatsappFallback(share);
    }
  } finally {
    if (isCurrentQuoteShare(share)) button.disabled = button.hidden;
  }
}

async function copyQuoteMessage() {
  const share = state.quoteShare;
  const message = byId("quote-share-message");
  try {
    await navigator.clipboard.writeText(message.value);
    if (isCurrentQuoteShare(share)) shareStatus("Mensagem copiada.");
  } catch {
    if (!isCurrentQuoteShare(share)) return;
    message.focus();
    message.select();
    let copied = false;
    try {
      copied = document.execCommand?.("copy");
    } catch {
      // The text remains selected for manual copying.
    }
    shareStatus(copied ? "Mensagem copiada." : "Texto selecionado. Use a opção Copiar do navegador ou do dispositivo.");
  }
}

function generateRequestId() {
  if (crypto.randomUUID) return crypto.randomUUID();
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, byte => byte.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

function setQuoteBusy(busy) {
  state.issueBusy = busy;
  for (const control of byId("client-form").elements) control.disabled = busy;
  for (const id of ["draft-notes", "item-cancel"]) {
    const control = byId(id);
    if (control) control.disabled = busy;
  }
  renderManualCatalog();
  renderManualRows();
  renderSummary();
  renderAIItems();
}

async function issueQuote() {
  if (state.issueBusy || state.issueConfirmation || state.aiBusy || draftUi.busy || draftUi.switching) return;
  let body = state.quoteAttempt;
  if (!body) {
    if (!state.preview || missingPrices() || hasUnreviewedInterpretation()) return;
    const form = byId("client-form");
    const name = byId("client-name");
    name.value = name.value.trim();
    if (!form.reportValidity()) return;
    body = { client: readQuoteClient(form), items: draftItems(), reviewToken: state.preview.reviewToken,
      customer: quoteCustomerSelection(), requestId: generateRequestId() };
    queueDraftSave();
  }
  const origin = quoteScreenPosition();
  setQuoteBusy(true);
  let issued;
  try {
    if (!state.quoteAttempt) {
      if (!await flushDraft() || draftUi.current?.version == null) {
        toast("Sincronize o rascunho antes de emitir o orçamento.");
        return;
      }
      body.draftId = draftUi.current.id;
      body.draftVersion = draftUi.current.version;
      state.quoteAttempt = body;
    }
    const result = await api("/api/quotes", { method: "POST", body });
    if (!Number.isSafeInteger(result?.number) || result.number < 1
        || typeof result.clientName !== "string" || !Number.isInteger(result.quantity) || result.quantity < 1
        || !["LOJISTA", "CONSUMIDOR_FINAL"].includes(result.priceTier)
        || typeof result.total !== "string" || !/^\d+(?:\.\d+)?$/u.test(result.total)) {
      throw new RequestError(0, "O servidor não confirmou a emissão. Tente novamente.");
    }
    issued = result;
    completeDraftIssue();
  } catch (error) {
    toast(error.message);
    if (body.customer?.register && error.status === 409) void quoteCustomerPicker.checkPhone();
    if ([400, 404, 409, 422].includes(error.status)) {
      state.quoteAttempt = null;
      if (body.draftId && (error.status === 409 || error.status === 404)) await checkDraftAfterIssue(body);
      await loadCatalog();
      await updatePreview();
    }
  } finally {
    setQuoteBusy(false);
    renderDraftList();
    updateDraftStatus();
  }
  if (issued) {
    showQuoteConfirmation(issued, origin);
    if (state.route?.page === "history") void loadHistory(0);
  }
}

function quoteScreenPosition() {
  return { x: window.scrollX, y: window.scrollY, focus: document.activeElement, page: state.route?.page };
}

function showQuoteConfirmation(quote, origin) {
  const position = origin.page === state.route?.page ? origin : quoteScreenPosition();
  const confirmation = { quote, ...position };
  state.issueConfirmation = confirmation;
  byId("quote-issue").disabled = true;
  byId("quote-success-number").textContent = quoteNumber(quote.number);
  byId("quote-success-client").textContent = quote.clientName;
  byId("quote-success-quantity").textContent = unitLabel(quote.quantity);
  byId("quote-success-tier").textContent = priceTierLabel(quote.priceTier);
  byId("quote-success-total").textContent = moneyFormat.format(quote.total);
  byId("quote-success-view").href = issuedPdfPath(quote.number);
  byId("quote-success-download").href = issuedPdfPath(quote.number, true);
  const dialog = byId("quote-success");
  dialog.showModal();
  byId("quote-success-title").focus({ preventScroll: true });
  dialog.scrollTop = 0;
  window.scrollTo(confirmation.x, confirmation.y);
}

function closeQuoteConfirmation(restorePosition = true) {
  const confirmation = state.issueConfirmation;
  if (!confirmation) return;
  state.issueConfirmation = null;
  byId("quote-success").close();
  renderSummary();
  renderDraftList();
  updateDraftStatus();
  if (restorePosition) {
    const active = confirmation.focus;
    const focus = quotePage(state.route?.page) ? byId("quote-summary-title")
      : active?.isConnected && active !== document.body && !active.disabled ? active : byId("main");
    focus.focus({ preventScroll: true });
    window.scrollTo(confirmation.x, confirmation.y);
  }
}

function formattedDate(value) {
  if (!value) return "—";
  const local = /^\d{4}-\d{2}-\d{2}(?:T[0-9:.]+)?$/.test(value);
  const date = new Date(local ? `${value.includes("T") ? value : `${value}T12:00:00`}-03:00` : value);
  return Number.isNaN(date.getTime()) ? "—" : dateFormat.format(date);
}

async function loadHistory(page) {
  const revision = ++state.historyRevision;
  const content = byId("history-content");
  const pages = byId("history-pages");
  content.replaceChildren(emptyState("Carregando orçamentos", "Aguarde enquanto os registros são consultados.", "archive"));
  pages.hidden = true;
  try {
    const records = await api(`/api/quotes?page=${page}&size=20`);
    if (revision !== state.historyRevision) return;
    if (!records.content.length) {
      content.replaceChildren(emptyState("Nenhum orçamento emitido", "Os próximos orçamentos aparecerão aqui com o respectivo PDF.", "archive", link("Criar orçamento", "#novo-orcamento")));
      return;
    }
    const rows = records.content.map(record => {
      const pdfActions = element("div", "history-quote-actions");
      pdfActions.append(pdfLink(record.number, false, true), pdfLink(record.number, true, true), quoteShareButton(record.number, record.clientName));
      pdfActions.append(orderButton("Ver estado comercial", record.number, "detail", true));
      const duplicate = element("button", "button secondary", "Duplicar");
      duplicate.type = "button";
      duplicate.setAttribute("aria-label", `Duplicar orçamento ${quoteNumber(record.number)}`);
      duplicate.addEventListener("click", async () => {
        if (duplicate.disabled) return;
        duplicate.disabled = true;
        try {
          duplicate.dataset.requestId ||= generateRequestId();
          const copy = await api(`/api/quotes/${record.number}/duplicate`, { method: "POST",
            body: { id: duplicate.dataset.requestId } });
          toast(copy.unavailable.length ? "Rascunho criado. Revise as combinações indisponíveis." : "Rascunho criado com os preços vigentes.");
          await openDraft(copy.id);
        } catch (error) {
          toast(error.message);
        } finally {
          duplicate.disabled = false;
        }
      });
      pdfActions.append(duplicate);
      const status = orderStatusBadge(record.status);
      return [[`Nº ${quoteNumber(record.number)}`, "cell-name"], [record.clientName], [formattedDate(record.createdAt)], [numberFormat.format(record.quantity), "numerical"], [moneyFormat.format(record.total), "numerical"], [status], [pdfActions, "cell-actions"]];
    });
    content.replaceChildren(table("Orçamentos emitidos", [["Orçamento"], ["Cliente"], ["Data"], ["Unidades", "numerical"], ["Total", "numerical"], ["Status"], ["PDF", "cell-actions"]], rows, "history-table"));
    if (records.totalPages > 1) {
      const previous = action("Página anterior", "chevron-left", () => void loadHistory(records.number - 1));
      const next = action("Próxima página", "chevron-right", () => void loadHistory(records.number + 1));
      previous.disabled = records.number < 1;
      next.disabled = records.number >= records.totalPages - 1;
      pages.replaceChildren(previous, element("span", "", `Página ${records.number + 1} de ${records.totalPages}`), next);
      pages.hidden = false;
    }
  } catch (error) {
    if (revision !== state.historyRevision) return;
    const retry = element("button", "button secondary", "Tentar novamente");
    retry.type = "button";
    retry.addEventListener("click", () => void loadHistory(page));
    content.replaceChildren(emptyState("Histórico indisponível", error.message, "archive", retry));
  }
}

function normalizedText(value) {
  return value.normalize("NFD").replace(/\p{Diacritic}/gu, "").toLocaleLowerCase("pt-BR").trim();
}

function renderCatalog() {
  const content = byId("catalog-content");
  const input = byId("catalog-search");
  input.disabled = !state.catalog?.length;
  if (!state.catalog) {
    content.replaceChildren(emptyState(state.catalogError ? "Catálogo indisponível" : "Carregando catálogo", state.catalogError ?? "Aguarde enquanto os modelos são consultados.", "boxes"));
    return;
  }
  const search = normalizedText(input.value);
  const filter = byId("catalog-status").value;
  const products = state.catalog.filter(product => normalizedText(product.name).includes(search)
    && (!filter || product.active === (filter === "active")));
  if (!products.length) {
    content.replaceChildren(emptyState(search ? "Nenhum modelo encontrado" : "Nenhum modelo cadastrado", search ? "Tente buscar por outro nome." : "Cadastre um modelo para disponibilizar iscas no orçamento.", "boxes"));
    return;
  }
  const priceRange = prices => {
    const known = prices.filter(value => value != null).map(Number).sort((a, b) => a - b);
    if (!known.length) return "A definir";
    return moneyFormat.format(known[0]) + (known[0] === known.at(-1) ? "" : ` a ${moneyFormat.format(known.at(-1))}`)
      + (known.length < prices.length ? " · há valores a definir" : "");
  };
  const cards = element("div", "catalog-cards");
  for (const product of products) {
    const variants = product.variants;
    const card = element("article", "catalog-card");
    const weights = new Set(variants.map(variant => variant.weight)).size;
    const colors = new Set(variants.map(variant => variant.color)).size;
    const tools = element("div", "draft-actions");
    const edit = element("button", "button secondary", "Editar");
    edit.type = "button";
    edit.setAttribute("aria-label", `Editar ${product.name}`);
    edit.addEventListener("click", () => openCatalogEditor(product));
    const prices = element("button", "button secondary", "Preços");
    prices.type = "button";
    prices.addEventListener("click", () => {
      history.pushState(null, "", "#precos");
      void navigate().then(() => {
        byId("price-model").value = String(product.id);
        renderPrices();
      });
    });
    tools.append(edit, prices);
    card.append(element("h2", "", product.name), element("span", `state-pill${product.active ? " active" : ""}`, product.active ? "Ativo" : "Inativo"),
      element("p", "", `${weights} gramaturas · ${colors} cores · ${variants.length} combinações`),
      element("p", "", `Consumidor final: ${priceRange(variants.map(variant => variant.retailPrice))}`),
      element("p", "", `Lojista: ${priceRange(variants.map(variant => variant.wholesalePrice))}`), tools);
    cards.append(card);
  }
  content.replaceChildren(cards);
}

function openCatalogEditor(product = null) {
  state.catalogModel = product;
  state.catalogVariants = product?.variants.map(({ weight, color, active }) => ({ weight, color, active })) ?? [];
  byId("catalog-dialog-heading").textContent = product ? "Editar modelo" : "Novo modelo";
  byId("catalog-name").value = product?.name ?? "";
  byId("catalog-active").checked = product?.active ?? true;
  byId("catalog-weights").value = "";
  byId("catalog-colors").value = "";
  byId("catalog-initial-prices").hidden = Boolean(product);
  for (const id of ["catalog-retail", "catalog-wholesale"]) {
    byId(id).value = "";
    byId(id).required = !product;
  }
  formFeedback("catalog-error", "");
  renderCatalogVariants();
  byId("catalog-dialog").showModal();
  byId("catalog-name").focus();
}

function renderCatalogVariants() {
  const wrapper = byId("dialog-variants");
  if (!state.catalogVariants.length) {
    wrapper.replaceChildren();
    return;
  }
  const variants = [...state.catalogVariants].sort((first, second) => first.weight - second.weight || first.color.localeCompare(second.color, "pt-BR"));
  const rows = variants.map(variant => {
    const active = document.createElement("input");
    active.type = "checkbox";
    active.checked = variant.active;
    active.setAttribute("aria-label", `Ativar ${variant.weight} g, cor ${variant.color}`);
    active.addEventListener("change", () => { variant.active = active.checked; });
    const activeTarget = element("label", "table-check-target");
    activeTarget.append(active);
    const remove = action(`Remover ${variant.weight} g, cor ${variant.color}`, "trash", () => {
      state.catalogVariants = state.catalogVariants.filter(entry => entry !== variant);
      renderCatalogVariants();
    }, "danger");
    active.disabled = state.catalogSaving;
    remove.disabled = state.catalogSaving;
    return [[`${variant.weight} g`], [variant.color], [activeTarget], [remove, "cell-actions"]];
  });
  wrapper.replaceChildren(table("Combinações do modelo", [["Gramatura"], ["Cor"], ["Ativa"], ["Ações", "cell-actions"]], rows, "variant-table"));
}

function addCombinations() {
  const weightField = byId("catalog-weights");
  const colorField = byId("catalog-colors");
  const weightsText = weightField.value.split(/[,;\s]+/u).filter(Boolean);
  const weights = [...new Set(weightsText.map(Number))];
  const colors = [...new Set(colorField.value.toUpperCase().split(/[,;\s]+/u).filter(Boolean)
    .map(code => /^\d$/u.test(code) ? code.padStart(2, "0") : code))];
  if (!weights.length || !colors.length || weightsText.some(weight => !/^\d+$/u.test(weight)) || weights.some(weight => !Number.isInteger(weight) || weight < 1 || weight > 2147483647)) {
    formFeedback("catalog-error", "Informe pelo menos uma gramatura inteira positiva e um código de cor.");
    return false;
  }
  if (colors.some(color => !/^[A-Z0-9][A-Z0-9-]{0,15}$/u.test(color))) {
    formFeedback("catalog-error", "Use até 16 letras, números ou hífens em cada código de cor. Comece com uma letra ou um número.");
    return false;
  }
  const entries = new Map(state.catalogVariants.map(variant => [keyForVariant(variant), variant]));
  for (const weight of weights) {
    for (const color of colors) {
      const variant = { weight, color, active: true };
      if (!entries.has(keyForVariant(variant))) entries.set(keyForVariant(variant), variant);
      if (entries.size > 2000) {
        formFeedback("catalog-error", "Cada modelo admite até 2.000 combinações.");
        return false;
      }
    }
  }
  state.catalogVariants = [...entries.values()];
  weightField.value = "";
  colorField.value = "";
  formFeedback("catalog-error", "");
  renderCatalogVariants();
  return true;
}

function setCatalogSaving(saving) {
  state.catalogSaving = saving;
  for (const control of byId("catalog-form").elements) control.disabled = saving;
  byId("catalog-save").textContent = saving ? "Salvando…" : "Salvar modelo";
}

async function saveProduct(event) {
  event.preventDefault();
  if (state.catalogSaving) return;
  if (byId("catalog-weights").value.trim() || byId("catalog-colors").value.trim()) {
    formFeedback("catalog-error", "Gere e revise a prévia das novas combinações antes de salvar.");
    return;
  }
  const name = byId("catalog-name").value.trim();
  const active = byId("catalog-active").checked;
  const model = state.catalogModel;
  if (!name) {
    formFeedback("catalog-error", "Informe o nome do modelo.");
    return;
  }
  formFeedback("catalog-error", "");
  setCatalogSaving(true);
  try {
    const prices = model ? null : {
      retailPrice: normalizeMoney(byId("catalog-retail").value),
      wholesalePrice: normalizeMoney(byId("catalog-wholesale").value)
    };
    if (!model && (!prices.retailPrice || !prices.wholesalePrice || !state.catalogVariants.length)) {
      throw new Error("Informe os dois preços e inclua pelo menos uma combinação.");
    }
    await api(model ? `/api/catalog/${model.id}` : "/api/catalog", {
      method: model ? "PUT" : "POST", body: { name, active, variants: state.catalogVariants, prices, expectedVersion: model?.version ?? null }
    });
    byId("catalog-dialog").close();
    toast(model ? "Modelo atualizado." : "Modelo cadastrado.");
    await loadCatalog();
    if (state.route?.page === "manual") await updatePreview();
  } catch (error) {
    formFeedback("catalog-error", error.message);
  } finally {
    setCatalogSaving(false);
  }
}

function renderPriceModels() {
  const select = byId("price-model");
  const products = (state.catalog ?? []).filter(product => product.variants.length);
  const choices = products.map(product => [product.id, product.active ? product.name : `${product.name} (inativo)`]);
  setOptions(select, choices, state.catalog ? "Selecione o modelo" : state.catalogError ? "Catálogo indisponível" : "Carregando…", select.value);
  if (!select.value && products.length) select.value = String(products[0].id);
  select.disabled = !products.length || state.priceBusy;
  renderPrices();
}

function editPrice(variant, field, value) {
  const current = state.priceEdits.get(variant.id) ?? { variant };
  current.variant = variant;
  const original = priceInputValue(variant[field]);
  if (value === original) delete current[field];
  else current[field] = value;
  if (current.retailPrice === undefined && current.wholesalePrice === undefined) state.priceEdits.delete(variant.id);
  else state.priceEdits.set(variant.id, current);
  byId("price-save").disabled = !state.priceEdits.size || state.priceBusy;
}

function priceInputValue(price) {
  return price == null ? "" : Number(price).toFixed(2).replace(".", ",");
}

function syncPriceInput(input, variant, field) {
  const value = state.priceEdits.get(variant.id)?.[field] ?? priceInputValue(variant[field]);
  if (input !== document.activeElement && input.value !== value) input.value = value;
  input.disabled = state.priceBusy;
}

function renderPrices() {
  const product = state.catalog?.find(entry => String(entry.id) === byId("price-model").value);
  const content = byId("price-content");
  byId("price-save").disabled = !state.priceEdits.size || state.priceBusy;
  if (!product) {
    content.replaceChildren(emptyState(state.catalogError ? "Catálogo indisponível" : "Nenhum modelo disponível", state.catalogError ?? "As combinações cadastradas aparecem aqui para configuração dos preços.", "tag", state.catalog ? link("Abrir catálogo", "#catalogo") : null));
    return;
  }
  const variants = [...product.variants].sort((first, second) => first.weight - second.weight || first.color.localeCompare(second.color, "pt-BR"));
  const caption = `Preços de ${product.name}`;
  const currentTable = content.querySelector(".price-table");
  const currentRows = currentTable?.tBodies[0].rows;
  if (currentTable?.dataset.productId === String(product.id) && currentRows?.length === variants.length
    && variants.every((variant, index) => currentRows[index].dataset.variantId === String(variant.id))) {
    currentTable.setAttribute("aria-label", caption);
    variants.forEach((variant, index) => {
      const row = currentRows[index];
      const pending = state.priceEdits.get(variant.id);
      if (pending) pending.variant = variant;
      const [retail, wholesale] = row.querySelectorAll(".price-entry input");
      syncPriceInput(retail, variant, "retailPrice");
      syncPriceInput(wholesale, variant, "wholesalePrice");
      const status = row.querySelector(".state-pill");
      const active = variant.active && product.active;
      status.classList.toggle("active", active);
      const statusText = active ? "Ativa" : "Inativa";
      if (status.textContent !== statusText) status.textContent = statusText;
    });
    return;
  }
  const inputFor = (variant, field, label) => {
    const input = document.createElement("input");
    input.type = "text";
    input.inputMode = "decimal";
    input.autocomplete = "off";
    input.maxLength = 13;
    input.placeholder = "Sem preço";
    syncPriceInput(input, variant, field);
    input.setAttribute("aria-label", `Preço de ${label} em reais, ${variant.weight} g, cor ${variant.color}`);
    input.addEventListener("input", () => {
      const latest = state.catalog?.find(entry => entry.id === product.id)?.variants.find(entry => entry.id === variant.id);
      if (latest) editPrice(latest, field, input.value);
    });
    return input;
  };
  const rows = variants.map(variant => [
    [`${variant.weight} g`, "cell-name"],
    [variant.color],
    [inputFor(variant, "retailPrice", "consumidor final"), "price-entry"],
    [inputFor(variant, "wholesalePrice", "lojista"), "price-entry"],
    [element("span", `state-pill${variant.active && product.active ? " active" : ""}`, variant.active && product.active ? "Ativa" : "Inativa")]
  ]);
  const listing = table(caption, [["Gramatura"], ["Cor"], ["Consumidor final (R$)"], ["Lojista (R$)"], ["Combinação"]], rows, "price-table");
  const listingTable = listing.querySelector("table");
  listingTable.dataset.productId = String(product.id);
  variants.forEach((variant, index) => { listingTable.tBodies[0].rows[index].dataset.variantId = String(variant.id); });
  content.replaceChildren(listing);
}

function normalizeMoney(value) {
  const amount = value.trim().replace(",", ".");
  if (!amount) return null;
  if (!/^\d{1,10}(?:\.\d{1,2})?$/u.test(amount)) throw new Error("Informe um valor de até 10 dígitos e duas casas decimais, ou deixe o campo vazio.");
  return amount;
}

async function savePrices(event) {
  event.preventDefault();
  if (!state.priceEdits.size || state.priceBusy) return;
  formFeedback("price-error", "");
  let variants;
  try {
    variants = [...state.priceEdits].map(([id, edit]) => ({
      id,
      retailPrice: edit.retailPrice === undefined ? edit.variant.retailPrice : normalizeMoney(edit.retailPrice),
      wholesalePrice: edit.wholesalePrice === undefined ? edit.variant.wholesalePrice : normalizeMoney(edit.wholesalePrice)
    }));
  } catch (error) {
    formFeedback("price-error", error.message);
    return;
  }
  state.priceBusy = true;
  const save = byId("price-save");
  save.lastChild.textContent = "Salvando…";
  renderPriceModels();
  try {
    await api("/api/catalog/prices", { method: "PUT", body: { variants } });
    state.priceEdits.clear();
    toast("Preços atualizados.");
    await loadCatalog();
    if (state.route?.page === "manual") await updatePreview();
  } catch (error) {
    formFeedback("price-error", error.message);
  } finally {
    state.priceBusy = false;
    save.lastChild.textContent = "Salvar preços";
    renderPriceModels();
  }
}

async function loadSettings() {
  try {
    const settings = await api("/api/settings");
    state.threshold = settings.wholesaleThreshold;
    byId("settings-threshold").value = state.threshold;
    byId("settings-threshold").disabled = false;
    byId("settings-save").disabled = false;
    formFeedback("settings-error", "");
    renderSummary();
  } catch (error) {
    formFeedback("settings-error", error.message);
  }
}

async function saveSettings(event) {
  event.preventDefault();
  if (state.settingsBusy) return;
  const field = byId("settings-threshold");
  const save = byId("settings-save");
  const wholesaleThreshold = Number(field.value);
  state.settingsBusy = true;
  save.disabled = true;
  field.disabled = true;
  formFeedback("settings-error", "");
  try {
    const result = await api("/api/settings", { method: "PUT", body: { wholesaleThreshold } });
    state.threshold = result.wholesaleThreshold;
    field.value = state.threshold;
    renderSummary();
    if (state.route?.page === "manual") await updatePreview();
    toast("Quantidade mínima atualizada.");
  } catch (error) {
    formFeedback("settings-error", error.message);
  } finally {
    state.settingsBusy = false;
    save.disabled = false;
    field.disabled = false;
  }
}

function appendChat(text, role = "assistant", questions = []) {
  const log = byId("chat-log");
  const followLatest = role === "admin" || log.scrollHeight - log.scrollTop - log.clientHeight < 48;
  const row = element("div", `chat-row ${role}`);
  const bubble = element("div", "chat-bubble");
  bubble.append(element("p", "", text));
  if (questions.length) {
    const list = element("ul");
    list.append(...questions.map(question => element("li", "", question)));
    bubble.append(list);
  }
  row.append(element("strong", "", role === "admin" ? "Você" : "URUTAU AI"), bubble);
  log.append(row);
  if (followLatest) log.scrollTop = log.scrollHeight;
  return row;
}

function clearOrderConversation() {
  state.conversation = null;
  byId("chat-message").value = "";
  byId("ai-change-message").value = "";
  initializeChat();
  renderAIControl();
}

function initializeChat() {
  byId("chat-log").replaceChildren();
  appendChat("Cole a mensagem de pedido aqui. Vou identificar as iscas e perguntar o que estiver faltando.");
}

async function checkAI() {
  if (state.aiStatusLoading) return;
  state.aiStatusLoading = true;
  const warning = byId("ai-warning");
  try {
    const status = await api("/api/ai");
    state.aiOnline = true;
    warning.replaceChildren();
    warning.hidden = status.configured;
    if (!status.configured) {
      warning.append(element("span", "", "Pedidos do catálogo funcionam. Configure o provedor de IA para interpretar mensagens livres."), link("Criar orçamento manual", "#novo-orcamento"));
    }
  } catch (error) {
    state.aiOnline = false;
    const retry = element("button", "text-button", "Tentar novamente");
    retry.type = "button";
    retry.addEventListener("click", () => void checkAI());
    warning.replaceChildren(element("span", "", error.message), retry);
    warning.hidden = false;
  } finally {
    state.aiStatusLoading = false;
    renderAIControl();
  }
}

function renderAIControl() {
  const blocked = state.aiBusy || state.issueBusy || !state.aiOnline;
  for (const id of ["chat-message", "ai-change-message", "chat-send", "ai-change-send", "ai-new"]) byId(id).disabled = blocked;
  byId("chat-send").querySelector("span").textContent = state.aiBusy ? "Processando…" : "Interpretar com URUTAU AI";
  byId("ai-change-send").querySelector("span").textContent = state.aiBusy ? "Processando…" : "Interpretar alteração";
  renderAIItems();
}

function hasAiProposal() {
  return draftUi.aiNotes !== null || draftUi.pendingAi.length > 0;
}

let proposedEstimate = null;
let proposalRevision = 0;

async function updateProposalEstimate() {
  const revision = ++proposalRevision;
  proposedEstimate = null;
  renderAIItems();
  if (!hasAiProposal() || !draftUi.pendingAi.length) return;
  const items = draftUi.pendingAi.map(({ variantId, quantity }) => ({ variantId, quantity }));
  try {
    const estimate = await api("/api/quotes/preview", { method: "POST", body: { items } });
    if (revision === proposalRevision) {
      proposedEstimate = estimate;
      renderAIItems();
    }
  } catch {
    // Reviewed estimates and final issuance still use the authoritative quote endpoint.
  }
}

function renderAIItems() {
  if (draftUi.source !== "IA") return;
  const proposed = hasAiProposal();
  const items = proposed ? draftUi.pendingAi : state.draft;
  const quantity = items.reduce((sum, item) => sum + item.quantity, 0);
  byId("ai-count").textContent = proposed ? `Proposta para revisão · ${unitLabel(quantity)}`
    : items.length ? unitLabel(quantity) : "Nenhum item identificado";
  renderQuoteRows(byId("ai-items"), items, proposed ? proposedEstimate : state.preview, false);
  const notes = proposed ? draftUi.aiNotes ?? draftUi.notes : draftUi.notes;
  byId("ai-notes-section").hidden = !notes;
  byId("ai-notes").textContent = notes;
  const pending = proposed || draftUi.aiQuestions.length > 0;
  byId("ai-review-actions").hidden = !pending;
  byId("ai-review").hidden = !proposed;
  byId("ai-review").disabled = !proposed || draftUi.aiQuestions.length > 0 || state.aiBusy || state.issueBusy;
  byId("ai-discard").disabled = state.aiBusy || state.issueBusy;
  window.urutauPwa?.renderUpdate();
}

function discardAiProposal() {
  if (state.aiBusy || state.issueBusy || state.issueConfirmation || draftUi.busy) return;
  draftUi.pendingAi = [];
  draftUi.aiNotes = null;
  draftUi.aiQuestions = [];
  draftUi.aiPrevious = "";
  void updateProposalEstimate();
  renderSummary();
  queueDraftSave();
}

function startConversation() {
  if (state.aiBusy || state.issueBusy || state.issueConfirmation || draftUi.busy || draftUi.switching || !state.aiOnline) return;
  if (hasUnreviewedInterpretation() && !window.confirm("Iniciar outra interpretação e descartar a proposta em revisão?")) return;
  clearOrderConversation();
  discardAiProposal();
}

async function sendMessage(event) {
  event.preventDefault();
  if (state.aiBusy || state.issueBusy || state.issueConfirmation || draftUi.busy || draftUi.switching || !state.aiOnline) return;
  const editing = event.currentTarget.id === "ai-change-form";
  const field = byId(editing ? "ai-change-message" : "chat-message");
  const message = field.value.trim();
  if (!message) return;
  appendChat(message, "admin");
  field.value = "";
  state.aiBusy = true;
  renderAIControl();
  const progress = appendChat("Interpretando a mensagem…");
  const basis = hasAiProposal() ? draftUi.pendingAi : state.draft;
  const workspace = { items: basis.map(({ variantId, quantity }) => ({ variantId, quantity })),
    notes: draftUi.aiNotes ?? draftUi.notes, previous: draftUi.aiPrevious, questions: draftUi.aiQuestions };
  try {
    if (!state.conversation) state.conversation = await api("/api/ai/conversations", { method: "POST" });
    const response = await api(`/api/ai/conversations/${encodeURIComponent(state.conversation.id)}/messages`,
      { method: "POST", body: { message, workspace } });
    draftUi.aiQuestions = response.questions;
    draftUi.aiPrevious = response.previous;
    if (response.questions.length) {
      appendChat("Preciso esclarecer alguns detalhes antes de alterar o orçamento.", "assistant", response.questions);
    } else {
      draftUi.pendingAi = response.items;
      draftUi.aiNotes = response.notes;
      appendChat("Confira a proposta em Itens interpretados e confirme em Revisar orçamento.");
      void updateProposalEstimate();
    }
  } catch (error) {
    field.value = message;
    if (error.status === 404) state.conversation = null;
    appendChat(error.message, "error");
  } finally {
    progress.remove();
    state.aiBusy = false;
    renderAIControl();
    renderSummary();
    queueDraftSave();
  }
}

function reviewConversation() {
  if (!hasAiProposal() || draftUi.aiQuestions.length || state.aiBusy || state.issueBusy || state.issueConfirmation || draftUi.busy) return;
  state.draft = draftUi.pendingAi.map(item => ({ ...item }));
  if (draftUi.aiNotes !== null) draftUi.notes = draftUi.aiNotes;
  discardAiProposal();
  draftChanged();
  toast("Proposta aplicada. Confira o orçamento antes de emitir.");
}

function bindManualEditor() {
  byId("item-model").addEventListener("change", () => {
    clearDraftEditor();
    state.selectedColors.clear();
    renderManualCatalog({ weight: "" });
  });
  byId("item-weight").addEventListener("change", () => {
    clearDraftEditor();
    state.selectedColors.clear();
    renderManualCatalog();
  });
  byId("item-colors").addEventListener("change", changeColorSelection);
  byId("item-colors").addEventListener("input", changeColorQuantity);
  byId("item-quantity").addEventListener("input", () => updateItemControls());
  byId("item-select-all").addEventListener("click", () => {
    const quantity = byId("item-quantity").value;
    for (const choice of byId("item-colors").querySelectorAll("[name='quote-color']:not(:checked)")) {
      state.selectedColors.set(Number(choice.value), quantity);
    }
    updateItemControls();
  });
  byId("item-clear-all").addEventListener("click", () => {
    state.selectedColors.clear();
    updateItemControls();
  });
  byId("item-apply-quantity").addEventListener("click", () => {
    const quantity = byId("item-quantity").value;
    if (readItemQuantity(quantity) === null) return;
    for (const id of state.selectedColors.keys()) state.selectedColors.set(id, quantity);
    updateItemControls();
  });
  byId("item-form").addEventListener("submit", addItems);
  byId("item-cancel").addEventListener("click", resetItemEditor);
  bindManualDraftEvents();
}

function bindEvents() {
  document.querySelectorAll("svg.icon").forEach(node => { node.setAttribute("aria-hidden", "true"); });
  document.querySelector(".skip-link").addEventListener("click", event => {
    event.preventDefault();
    byId("main").focus();
  });
  addEventListener("hashchange", navigate);
  byId("nav-toggle").addEventListener("click", () => navOpen(byId("nav-toggle").getAttribute("aria-expanded") === "false"));
  for (const id of ["nav-overlay", "nav-close"]) {
    byId(id).addEventListener("click", () => navOpen(false, true));
  }
  byId("sidebar").addEventListener("click", event => {
    const routeLink = event.target.closest("a[href^='#']");
    if (routeLink && byId("sidebar").classList.contains("is-open")) navOpen(false, routeLink.hash === location.hash);
  });
  drawerLayout.addEventListener("change", () => navOpen(false));
  addEventListener("keydown", drawerKeydown);
  byId("client-form").addEventListener("submit", event => event.preventDefault());
  const previewDialog = byId("preview-pdf-dialog");
  byId("quote-preview-pdf").addEventListener("click", () => void showPdfPreview());
  byId("preview-pdf-close").addEventListener("click", () => previewDialog.close());
  previewDialog.addEventListener("close", () => { if (!previewDialog.open) resetPdfPreview(); });
  const issuedDialog = byId("issued-pdf-dialog");
  byId("issued-pdf-close").addEventListener("click", () => issuedDialog.close());
  issuedDialog.addEventListener("close", () => { if (!issuedDialog.open) resetIssuedViewer(); });
  byId("issued-pdf-share").addEventListener("click", () => {
    const viewer = state.issuedViewer;
    if (viewer) openQuoteShare(viewer.number, null, viewer.file);
  });
  const shareDialog = byId("quote-share-dialog");
  byId("quote-share-close").addEventListener("click", () => shareDialog.close());
  shareDialog.addEventListener("close", () => { if (!shareDialog.open) resetQuoteShare(); });
  byId("quote-share-message").addEventListener("input", () => {
    const share = state.quoteShare;
    if (!share) return;
    share.edited = true;
    if (!byId("quote-share-fallback").hidden) updateWhatsappLink(share);
  });
  byId("quote-share-copy").addEventListener("click", () => void copyQuoteMessage());
  byId("quote-share-native").addEventListener("click", () => void shareQuoteNatively());
  byId("quote-share-retry").addEventListener("click", () => {
    if (state.quoteShare) void prepareQuoteShare(state.quoteShare);
  });
  byId("quote-success-close").addEventListener("click", () => closeQuoteConfirmation());
  byId("quote-success").addEventListener("cancel", event => {
    event.preventDefault();
    closeQuoteConfirmation();
  });
  byId("quote-success-share").addEventListener("click", event => {
    const quote = state.issueConfirmation?.quote;
    if (quote) {
      event.currentTarget.focus({ preventScroll: true });
      openQuoteShare(quote.number, quote.clientName);
    }
  });
  byId("quote-success-view").addEventListener("click", event => {
    const quote = state.issueConfirmation?.quote;
    if (quote) {
      event.currentTarget.focus({ preventScroll: true });
      viewIssuedPdf(event, quote.number);
    }
  });
  byId("quote-issue").addEventListener("click", () => void issueQuote());
  byId("catalog-search").addEventListener("input", renderCatalog);
  byId("catalog-status").addEventListener("change", renderCatalog);
  byId("catalog-new").addEventListener("click", () => openCatalogEditor());
  ["catalog-close", "catalog-cancel"].forEach(id => byId(id).addEventListener("click", () => byId("catalog-dialog").close()));
  byId("catalog-dialog").addEventListener("cancel", event => { if (state.catalogSaving) event.preventDefault(); });
  byId("add-combinations").addEventListener("click", addCombinations);
  byId("catalog-form").addEventListener("submit", saveProduct);
  byId("price-model").addEventListener("change", renderPrices);
  byId("price-form").addEventListener("submit", savePrices);
  byId("settings-form").addEventListener("submit", saveSettings);
  byId("ai-new").addEventListener("click", () => void startConversation());
  byId("chat-form").addEventListener("submit", sendMessage);
  byId("ai-change-form").addEventListener("submit", sendMessage);
  byId("ai-review").addEventListener("click", reviewConversation);
  byId("ai-discard").addEventListener("click", discardAiProposal);
  bindDraftEvents();
  bindOrderEvents();
  bindProductionEvents();
  bindCustomerEvents();
  bindAssistant();
}

async function loadSession() {
  try {
    state.session = await api("/api/session");
    byId("assistant-launch").hidden = false;
    byId("account-name").textContent = state.session.username;
    byId("account-avatar").textContent = state.session.username.slice(0, 2).toLocaleUpperCase("pt-BR");
    byId("logout-csrf").value = state.session.csrfToken;
    navigate();
    if (state.route?.page !== "settings") void loadSettings();
  } catch (error) {
    if (error.status !== 401) toast(error.message);
  }
}

function hasUnfinishedQuote() {
  return state.issueBusy || draftIsDirty() || state.draft.length > 0 || state.editingItem !== null || state.selectedColors.size > 0
    || Object.values(readQuoteClient(byId("client-form"))).some(Boolean)
    || assistantHasConversation() || state.aiBusy || hasUnreviewedInterpretation()
    || Boolean(byId("chat-message").value.trim() || byId("ai-change-message").value.trim());
}

function initialize() {
  bindEvents();
  const today = new Date();
  byId("header-date").dateTime = today.toISOString();
  byId("header-date").textContent = currentDateFormat.format(today);

  initializeChat();
  renderManualRows();
  navigate();
  window.urutauPwa?.setUpdateGuard(hasUnfinishedQuote);
  addEventListener("urutau:online", async () => {
    await loadSession();
    queueDraftSave();
  });
  void loadSession();
}

initialize();
