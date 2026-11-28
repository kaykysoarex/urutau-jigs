"use strict";

const productionUi = { page: 0, query: null, timer: null, detailRequest: null, order: null, quote: null, opener: null };
const productionDeadline = {
  ATRASADO: "Atrasado", VENCE_HOJE: "Vence hoje", PROXIMO: "Prazo próximo",
  DENTRO_DO_PRAZO: "Dentro do prazo", SEM_PRAZO: "Sem prazo definido"
};

function productionBadge(record) {
  const status = element("span", "state-pill production-deadline", productionDeadline[record.deadline]);
  status.dataset.deadline = record.deadline;
  return status;
}

function productionCard(record) {
  const card = element("li", "panel panel-body order-card production-card");
  card.dataset.productionNumber = record.orderNumber;
  if (record.position === 1) card.classList.add("production-card-first");
  const header = element("div", "order-card-heading");
  header.append(element("h2", "", record.clientName), element("span", "production-position", record.position + "º na fila"));
  const badges = element("div", "production-badges");
  badges.append(element("span", "state-pill active", "Em produção"), productionBadge(record));
  const meta = element("p", "order-meta", "Confirmação: " + orderDate(record.confirmedAt)
    + (record.expectedOn ? " · Prazo comercial: " + orderCalendar(record.expectedOn) : ""));
  const demand = record.units === 1 ? "1 isca demandada" : numberFormat.format(record.units) + " iscas demandadas";
  const totals = element("p", "production-demand", demand + " · " + moneyFormat.format(record.total));
  const open = element("button", "button secondary", "Ver detalhes da produção");
  open.type = "button";
  open.setAttribute("aria-haspopup", "dialog");
  open.addEventListener("click", () => void showProductionOrder(record.orderNumber, open));
  card.append(header, element("p", "order-meta", "Pedido nº " + quoteNumber(record.orderNumber)), badges, meta, totals, open);
  return card;
}

function showProductionMetrics(indicators) {
  for (const [id, key] of [
    ["production-orders", "orders"], ["production-units", "demandedUnits"], ["production-overdue", "overdue"],
    ["production-upcoming", "upcoming"], ["production-dueToday", "dueToday"]
  ]) {
    byId(id).textContent = indicators ? numberFormat.format(indicators[key]) : "—";
  }
  const first = byId("production-first");
  const next = indicators?.first;
  const label = next ? "Nº " + quoteNumber(next.orderNumber) + " · " + next.clientName : indicators ? "Fila vazia" : "—";
  if (next && first.querySelector("button")?.dataset.number === String(next.orderNumber)) {
    first.querySelector("button").textContent = label;
    return;
  }
  if (!next) {
    first.textContent = label;
    return;
  }
  const button = element("button", "text-button", label);
  button.type = "button";
  button.dataset.number = next.orderNumber;
  button.setAttribute("aria-haspopup", "dialog");
  button.addEventListener("click", () => void showProductionOrder(next.orderNumber, button));
  first.replaceChildren(button);
}

function renderProductionPage(result) {
  const { indicators, page, pages, content } = result;
  const list = byId("production-list");
  const pager = byId("production-pages");
  const focused = document.activeElement?.closest("[data-production-number]")?.dataset.productionNumber;
  const pageAction = document.activeElement?.dataset.productionPage;
  showProductionMetrics(indicators);
  list.replaceChildren(...content.map(productionCard));
  byId("production-checked").textContent = "Atualizada em " + orderDate(result.checkedAt);
  byId("production-checked").hidden = false;
  formFeedback("production-feedback", content.length ? "" : indicators.orders
    ? "Nenhum pedido corresponde aos filtros." : "A fila está vazia. Pedidos confirmados aparecerão aqui.");

  pager.hidden = pages < 2;
  const previous = action("Página anterior da produção", "chevron-left", () => void refreshProduction(page - 1));
  const next = action("Próxima página da produção", "chevron-right", () => void refreshProduction(page + 1));
  previous.dataset.productionPage = "previous";
  next.dataset.productionPage = "next";
  previous.disabled = page === 0;
  next.disabled = page + 1 >= pages;
  pager.replaceChildren(previous, element("span", "", "Página " + (page + 1) + " de " + pages), next);
  if (focused) {
    const card = [...list.children].find(row => row.dataset.productionNumber === focused);
    (card?.querySelector("button") ?? byId("production-title")).focus({ preventScroll: true });
  } else if (pageAction) {
    const button = pageAction === "next" ? next : previous;
    (button.disabled || pager.hidden ? byId("production-title") : button).focus({ preventScroll: true });
  }
}

async function refreshProduction(page = productionUi.page, quiet = false) {
  if (state.route?.page !== "production" || !state.session) return;
  if (quiet && productionUi.query) return;
  productionUi.query?.abort();
  const controller = new AbortController();
  productionUi.query = controller;
  const query = new URLSearchParams({ page, search: byId("production-search").value.trim() });
  if (byId("production-deadline").value) query.set("deadline", byId("production-deadline").value);
  const list = byId("production-list");
  list.setAttribute("aria-busy", "true");
  if (!quiet) formFeedback("production-feedback", "Consultando a fila…");
  try {
    const result = await api("/api/production?" + query, { signal: controller.signal });
    if (productionUi.query !== controller) return;
    if (page > 0 && page >= result.pages) {
      return await refreshProduction(Math.max(0, result.pages - 1));
    }
    productionUi.page = page;
    renderProductionPage(result);
    if (productionUi.order !== null && byId("production-dialog").open) {
      void requestProductionDetail(productionUi.order);
    }
  } catch (error) {
    if (productionUi.query !== controller) return;
    showProductionMetrics(null);
    list.replaceChildren();
    byId("production-pages").hidden = true;
    byId("production-checked").hidden = true;
    formFeedback("production-feedback", "Fila indisponível. " + error.message);
    if (productionUi.order !== null) {
      productionUi.detailRequest?.abort();
      productionUi.detailRequest = null;
      showProductionDetailError("Atualize a fila para consultar o pedido.");
    }
  } finally {
    if (productionUi.query === controller) {
      productionUi.query = null;
      list.removeAttribute("aria-busy");
    }
  }
}

function renderProductionDetail(detail) {
  const { order, items } = detail;
  const content = byId("production-detail");
  byId("production-dialog-title").textContent = "Produção do pedido nº " + quoteNumber(order.orderNumber);
  const facts = orderFacts([
    ["Cliente", order.clientName], ["Telefone/WhatsApp", detail.phone || "—"],
    ["Posição", order.position + "º na fila"], ["Status", "Em produção"],
    ["Confirmação", orderDate(order.confirmedAt)], ["Prazo comercial previsto", orderCalendar(order.expectedOn)],
    ["Situação do prazo", productionDeadline[order.deadline]]
  ]);
  const heading = element("h3", "", "Lista de fabricação");
  const groups = element("div", "manufacturing-groups");
  const models = new Map();
  for (const item of items) {
    const name = item.model;
    if (!models.has(name)) models.set(name, new Map());
    const weights = models.get(name);
    const { weight } = item.combination;
    if (!weights.has(weight)) weights.set(weight, []);
    weights.get(weight).push(item);
  }
  for (const [name, weights] of models) {
    const model = element("section", "manufacturing-group");
    model.append(element("h4", "", name));
    for (const [weight, variants] of weights) {
      const list = element("ul", "manufacturing-colors");
      for (const item of variants) {
        const row = element("li", "");
        row.append(element("span", "", "Cor " + item.combination.color), element("strong", "", unitLabel(item.units)));
        if (!item.available) row.append(element("small", "manufacturing-inactive", "Combinação desativada no catálogo"));
        list.append(row);
      }
      model.append(element("p", "order-meta", weight + " g"), list);
    }
    groups.append(model);
  }
  const blocks = [facts, heading, groups, element("p", "production-demand", "Iscas demandadas: " + unitLabel(order.units))];
  if (detail.notes) {
    blocks.push(element("h3", "", "Observações comerciais"), element("p", "production-notes", detail.notes));
  }
  content.replaceChildren(...blocks);
  productionUi.quote = order.quoteNumber;
  byId("production-commercial").hidden = false;
}

function showProductionDetailError(message) {
  byId("production-detail").replaceChildren(element("p", "notice", message));
  byId("production-commercial").hidden = productionUi.quote === null;
}

async function requestProductionDetail(number) {
  productionUi.detailRequest?.abort();
  const controller = new AbortController();
  productionUi.detailRequest = controller;
  try {
    const detail = await api("/api/production/" + encodeURIComponent(number), { signal: controller.signal });
    if (productionUi.detailRequest === controller && byId("production-dialog").open) renderProductionDetail(detail);
  } catch (error) {
    if (productionUi.detailRequest === controller && byId("production-dialog").open) {
      showProductionDetailError(error.message);
    }
  } finally {
    if (productionUi.detailRequest === controller) productionUi.detailRequest = null;
  }
}

function showProductionOrder(number, opener = null) {
  if (state.route?.page !== "production" || !Number.isSafeInteger(number) || number < 1) return;
  const dialog = byId("production-dialog");
  productionUi.order = number;
  productionUi.quote = null;
  productionUi.opener = opener;
  byId("production-dialog-title").textContent = "Detalhes da produção";
  showProductionDetailError("Carregando pedido…");
  if (!dialog.open) dialog.showModal();
  byId("production-dialog-title").focus({ preventScroll: true });
  return requestProductionDetail(number);
}

function leaveProduction() {
  clearTimeout(productionUi.timer);
  productionUi.query?.abort();
  productionUi.detailRequest?.abort();
  productionUi.query = null;
  productionUi.detailRequest = null;
  byId("production-list").removeAttribute("aria-busy");
  if (byId("production-dialog").open) byId("production-dialog").close();
}

function bindProductionEvents() {
  const dialog = byId("production-dialog");
  byId("production-close").addEventListener("click", () => dialog.close());
  dialog.addEventListener("close", () => {
    productionUi.detailRequest?.abort();
    productionUi.detailRequest = null;
    productionUi.order = null;
    productionUi.quote = null;
    byId("production-detail").replaceChildren();
    if (state.route?.page === "production") {
      const opener = productionUi.opener?.isConnected ? productionUi.opener : byId("production-title");
      opener.focus({ preventScroll: true });
    }
    productionUi.opener = null;
  });
  byId("production-commercial").addEventListener("click", async () => {
    const quote = productionUi.quote;
    if (quote === null) return;
    dialog.close();
    history.pushState(null, "", "#pedidos");
    await navigate();
    await openCommercialOrder(quote, "detail");
  });
  byId("production-refresh").addEventListener("click", () => void refreshProduction());
  byId("production-filters").addEventListener("submit", event => {
    event.preventDefault();
    clearTimeout(productionUi.timer);
    void refreshProduction(0);
  });
  byId("production-search").addEventListener("input", () => {
    clearTimeout(productionUi.timer);
    productionUi.timer = setTimeout(() => void refreshProduction(0), 250);
  });
  byId("production-deadline").addEventListener("change", () => {
    clearTimeout(productionUi.timer);
    void refreshProduction(0);
  });
  document.addEventListener("visibilitychange", () => {
    if (!document.hidden && !window.urutauPwa?.isOffline()) void refreshProduction(productionUi.page, true);
  });
  setInterval(() => {
    if (!document.hidden && !window.urutauPwa?.isOffline()) void refreshProduction(productionUi.page, true);
  }, 30_000);
}
