
"use strict";

const ordersUi = { loading: 0, timer: null, detail: null, detailRevision: 0, mode: "detail", busy: false, opener: null, page: 0 };
const orderLabels = { PENDENTE: "Aguardando confirmação", CONFIRMADO: "Confirmado", CANCELADO: "Cancelado",
  EM_PRODUCAO: "Em produção", ENTREGUE: "Entregue" };
const orderDateTime = new Intl.DateTimeFormat("pt-BR", {
  dateStyle: "short", timeStyle: "short", timeZone: "America/Sao_Paulo"
});
const orderDate = value => value ? orderDateTime.format(new Date(value)) : "—";
const orderCalendar = value => value ? value.split("-").reverse().join("/") : "—";

function orderStatusBadge(status) {
  return element("span", `state-pill${status === "PENDENTE" ? " pending" : ""}`, orderLabels[status]);
}

function orderButton(label, quote, mode, compact = false) {
  const button = compact
    ? action(label, "receipt", () => void openCommercialOrder(quote, mode))
    : element("button", "button secondary", label);
  button.type = "button";
  button.setAttribute("aria-haspopup", "dialog");
  if (!compact) button.addEventListener("click", () => void openCommercialOrder(quote, mode));
  return button;
}

async function loadOrders(page) {
  const request = ++ordersUi.loading;
  const query = new URLSearchParams({
    search: byId("order-search").value.trim(),
    newest: byId("order-sort").value,
    page
  });
  const filter = byId("order-status").value;
  if (filter) query.set(["EM_PRODUCAO", "ENTREGUE"].includes(filter) ? "fulfillment" : "status", filter);
  const list = byId("orders-list");
  list.setAttribute("aria-busy", "true");
  formFeedback("orders-feedback", "Carregando registros comerciais…");
  try {
    const result = await api(`/api/orders?${query}`);
    if (request !== ordersUi.loading) return;
    if (result.pages > 0 && page >= result.pages) return void loadOrders(result.pages - 1);
    ordersUi.page = page;
    for (const key of ["pending", "inProduction", "delivered", "cancelled"]) {
      byId(`orders-${key}`).textContent = numberFormat.format(result.counts[key]);
    }
    const focused = document.activeElement?.closest?.("[data-order-number]")?.dataset.orderNumber;
    list.replaceChildren(...result.content.map(orderCard));
    if (focused) focusOrderCard(focused);
    formFeedback("orders-feedback", result.content.length ? "" : "Nenhum registro encontrado.");
    const pager = byId("orders-pages");
    pager.hidden = result.pages < 2;
    const previous = action("Página anterior", "chevron-left", () => void loadOrders(page - 1));
    const next = action("Próxima página", "chevron-right", () => void loadOrders(page + 1));
    previous.disabled = page === 0;
    next.disabled = page + 1 >= result.pages;
    pager.replaceChildren(previous, element("span", "", `Página ${page + 1} de ${result.pages}`), next);
  } catch (error) {
    if (request === ordersUi.loading) formFeedback("orders-feedback", error.message);
  } finally {
    if (request === ordersUi.loading) list.removeAttribute("aria-busy");
  }
}

function orderCard(record) {
  const row = element("li", "panel panel-body order-card");
  row.dataset.orderNumber = record.quoteNumber;
  const identification = record.orderNumber == null ? `Orçamento nº ${quoteNumber(record.quoteNumber)}`
    : `Pedido nº ${quoteNumber(record.orderNumber)} · Orçamento nº ${quoteNumber(record.quoteNumber)}`;
  const header = element("div", "order-card-heading");
  header.append(element("h2", "", record.clientName), orderStatusBadge(record.fulfillment ?? record.status));
  const actions = element("div", "order-actions");
  actions.append(orderButton("Ver detalhes", record.quoteNumber, "detail"), pdfLink(record.quoteNumber, false));
  if (record.status === "PENDENTE") actions.prepend(orderButton("Confirmar pedido", record.quoteNumber, "confirm"));
  row.append(header, element("p", "order-meta", identification),
    element("p", "order-meta", `${orderDate(record.date)} · ${unitLabel(record.quantity)}`),
    element("strong", "", moneyFormat.format(record.total)), actions);
  return row;
}

function focusOrderCard(number) {
  const card = [...byId("orders-list").children].find(row => row.dataset.orderNumber === String(number));
  (card?.querySelector("button") ?? byId("orders-title")).focus({ preventScroll: true });
}

function orderFacts(facts) {
  const details = element("dl", "order-facts");
  for (const [label, value] of facts) {
    const row = element("div");
    row.append(element("dt", "", label), element("dd", "", value));
    details.append(row);
  }
  return details;
}

async function openCommercialOrder(number, mode) {
  const dialog = byId("order-dialog");
  if (dialog.open) return;
  ordersUi.opener = document.activeElement;
  ordersUi.detail = null;
  byId("order-dialog-title").textContent = "Registro comercial";
  byId("order-dialog-body").replaceChildren(element("p", "", "Carregando…"));
  byId("order-form").hidden = true;
  dialog.showModal();
  const revision = ++ordersUi.detailRevision;
  try {
    const data = await api(`/api/orders/${number}`);
    if (dialog.open && revision === ordersUi.detailRevision) {
      ordersUi.detail = data;
      renderOrderDialog(data.commercial.status === "PENDENTE" ? mode : "detail");
    }
  } catch (error) {
    if (dialog.open && revision === ordersUi.detailRevision) byId("order-dialog-body").textContent = error.message;
  }
}

function renderOrderDialog(mode) {
  const data = ordersUi.detail;
  const record = data.commercial;
  ordersUi.mode = mode;
  const title = byId("order-dialog-title");
  title.textContent = { confirm: "Confirmar pedido", edit: "Editar pedido", cancel: "Cancelar registro", customer: "Vincular cadastro", deliver: "Marcar como entregue" }[mode]
    ?? (record.orderNumber == null ? "Detalhes do orçamento" : "Detalhes do pedido");
  const customer = data.client;
  const facts = [
    ["Orçamento", quoteNumber(record.quoteNumber)], ["Cliente", customer.name], ["Total de iscas", unitLabel(record.quantity)],
    ["Valor aprovado", moneyFormat.format(record.total)], ["Tabela comercial", priceTierLabel(record.priceTier)],
    ["Estado", orderLabels[record.fulfillment ?? record.status]]
  ];
  if (mode === "detail") {
    if (record.orderNumber != null) facts.unshift(["Pedido", quoteNumber(record.orderNumber)]);
    if (customer.phone) facts.push(["Telefone", customer.phone]);
    if (customer.city || customer.state) facts.push(["Cidade / UF", [customer.city, customer.state].filter(Boolean).join(" – ")]);
    if (data.confirmedAt) facts.push(["Confirmação", orderDate(data.confirmedAt)], ["Confirmado por", data.confirmedBy],
      ["Prazo previsto", orderCalendar(data.expectedOn)], ["Observações", data.notes || "—"]);
    if (record.deliveredAt) facts.push(["Entrega", orderDate(record.deliveredAt)], ["Entregue por", data.deliveredBy]);
    if (data.cancelledAt) facts.push(["Cancelamento", orderDate(data.cancelledAt)], ["Cancelado por", data.cancelledBy],
      ["Motivo", data.cancellationReason]);
  }
  const body = byId("order-dialog-body");
  body.replaceChildren(orderFacts(facts));
  if (mode === "detail") renderOrderDetail(body, data);
  byId("order-form").hidden = mode === "detail";
  byId("order-date-field").hidden = byId("order-notes-field").hidden = !["confirm", "edit"].includes(mode);
  const picker = byId("order-customer-picker");
  picker.hidden = data.customerId != null || !["confirm", "customer"].includes(mode);
  ordersUi.customerPicker = picker.hidden ? null : mountCustomerPicker(picker, { phone: () => data.client.phone, allowRegister: true, suggestPhone: true });
  byId("order-reason-field").hidden = mode !== "cancel";
  byId("order-reason").required = mode === "cancel";
  byId("order-expected").value = data.expectedOn ?? "";
  byId("order-notes").value = data.notes ?? "";
  byId("order-reason").value = "";
  byId("order-submit").textContent = { confirm: "Confirmar pedido", edit: "Salvar alterações", cancel: "Confirmar cancelamento", customer: "Vincular cliente", deliver: "Confirmar entrega" }[mode] ?? "";
  formFeedback("order-form-error", "");
  byId("order-dialog").scrollTop = 0;
  title.focus({ preventScroll: true });
}

function renderOrderDetail(body, data) {
  const record = data.commercial;
  const items = element("ul", "order-lines");
  const sorted = [...data.items].sort((a, b) => a.model.localeCompare(b.model, "pt-BR") || a.weight - b.weight
    || a.color.localeCompare(b.color, "pt-BR", { numeric: true }));
  for (const item of sorted) {
    const row = element("li");
    row.append(element("strong", "", `${item.model} · ${item.weight} g · Cor ${item.color}`),
      element("span", "", `${unitLabel(item.quantity)} × ${moneyFormat.format(item.unitPrice)} = ${moneyFormat.format(item.subtotal)}`));
    items.append(row);
  }
  const actions = element("div", "order-actions");
  actions.append(pdfLink(record.quoteNumber, false), pdfLink(record.quoteNumber, true),
    quoteShareButton(record.quoteNumber, record.clientName));
  const transitions = record.status === "PENDENTE" ? [["Confirmar pedido", "confirm"], ["Cancelar orçamento", "cancel"]]
    : record.status === "CONFIRMADO" ? [["Editar prazo e observações", "edit"], ["Cancelar pedido", "cancel"]] : [];
  if (record.fulfillment === "EM_PRODUCAO") transitions.unshift(["Marcar como entregue", "deliver"]);
  for (const [label, mode] of transitions) {
    const button = element("button", "button secondary", label);
    button.type = "button";
    button.addEventListener("click", () => renderOrderDialog(mode));
    actions.append(button);
  }
  actions.append(data.customerId != null
    ? customerButton("Ver cadastro do cliente", () => {
        if (byId("customer-dialog").open) byId("order-dialog").close();
        void openCustomer(data.customerId);
      })
    : customerButton("Vincular cliente", () => renderOrderDialog("customer")));
  body.append(element("h3", "", "Itens aprovados"), items, actions);
  if (!data.events.length) return;
  const audit = element("details", "order-audit");
  const events = element("ul", "order-lines");
  const descriptions = { CONFIRMACAO: "Confirmação", EDICAO: "Edição", CANCELAMENTO: "Cancelamento", VINCULO: "Vínculo com cliente", ENTREGA: "Entrega" };
  for (const event of data.events) {
    const row = element("li", "", `${descriptions[event.action]} · ${orderDate(event.occurredAt)} · ${event.actor}`);
    if (event.expectedOn) row.append(element("span", "", `Prazo: ${orderCalendar(event.expectedOn)}`));
    if (event.notes) row.append(element("span", "", event.notes));
    if (event.reason) row.append(element("span", "", event.reason));
    events.append(row);
  }
  audit.append(element("summary", "", "Histórico de operações"), events);
  body.append(audit);
}

async function submitOrderChange(event) {
  event.preventDefault();
  if (ordersUi.busy || !ordersUi.detail) return;
  const isEdit = ordersUi.mode === "edit";
  const isCancel = ordersUi.mode === "cancel";
  const record = ordersUi.detail.commercial;
  const isLink = ordersUi.mode === "customer";
  const isDelivery = ordersUi.mode === "deliver";
  const suffix = isEdit ? "terms" : isCancel ? "cancel" : isLink ? "customer" : isDelivery ? "deliver" : "confirm";
  const terms = { expectedOn: byId("order-expected").value || null, notes: byId("order-notes").value };
  const reason = byId("order-reason").value.trim();
  if (isCancel && !reason) return formFeedback("order-form-error", "Informe o motivo do cancelamento.");
  const customer = ordersUi.customerPicker?.selection() ?? null;
  if (isLink && !customer) return formFeedback("order-form-error", "Selecione um cadastro ou marque a opção de cadastrar.");
  const body = isEdit ? { version: ordersUi.detail.version, terms } : isCancel ? { reason } : isLink ? customer : isDelivery ? undefined : { ...terms, customer };
  const form = byId("order-form");
  ordersUi.busy = true;
  for (const control of form.elements) control.disabled = true;
  byId("order-close").disabled = true;
  try {
    ordersUi.detail = await api(`/api/orders/${record.quoteNumber}/${suffix}`, { method: isEdit || isLink ? "PUT" : "POST", body });
    renderOrderDialog("detail");
    toast(isCancel ? "Registro cancelado." : isEdit ? "Alterações salvas." : isLink ? "Cliente vinculado." : isDelivery ? "Pedido marcado como entregue." : "Pedido confirmado com sucesso.");
    if (state.route?.page === "orders") void loadOrders(ordersUi.page);
    if (state.route?.page === "history") void loadHistory(0);
    if (isDelivery) refreshCustomerOrderStatus(ordersUi.detail.commercial);
  } catch (error) {
    formFeedback("order-form-error", error.message);
    if (customer?.register && error.status === 409) void ordersUi.customerPicker?.checkPhone();
  } finally {
    ordersUi.busy = false;
    for (const control of form.elements) control.disabled = false;
    byId("order-close").disabled = false;
  }
}

function bindOrderEvents() {
  const dialog = byId("order-dialog");
  byId("order-filters").addEventListener("submit", event => event.preventDefault());
  byId("order-search").addEventListener("input", () => {
    clearTimeout(ordersUi.timer);
    ordersUi.timer = setTimeout(() => void loadOrders(0), 250);
  });
  for (const id of ["order-status", "order-sort"]) {
    byId(id).addEventListener("change", () => void loadOrders(0));
  }
  byId("order-form").addEventListener("submit", submitOrderChange);
  byId("order-close").addEventListener("click", () => dialog.close());
  byId("order-back").addEventListener("click", () => renderOrderDialog("detail"));
  dialog.addEventListener("cancel", event => { if (ordersUi.busy) event.preventDefault(); });
  dialog.addEventListener("close", () => {
    if (ordersUi.opener?.isConnected) ordersUi.opener.focus({ preventScroll: true });
    else if (state.route?.page === "orders") focusOrderCard(ordersUi.detail?.commercial.quoteNumber);
    else byId("main").focus({ preventScroll: true });
    ordersUi.opener = null;
  });
}
