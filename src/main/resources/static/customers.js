
"use strict";

const customerUi = { revision: 0, dialogRevision: 0, page: 0, current: null, opener: null, busy: false };
const quoteCustomer = { ref: null, master: null };
let quoteCustomerPicker;

function customerButton(label, callback, style = "secondary") {
  const button = element("button", `button ${style}`, label);
  button.type = "button";
  button.addEventListener("click", callback);
  return button;
}

function customerCheck(label) {
  const input = element("input");
  input.type = "checkbox";
  const wrapper = element("label", "customer-check");
  wrapper.append(input, element("span", "", label));
  return { input, wrapper };
}

function customerSearch(path, body) {
  return api(`/api/customers/${path}`, { method: "POST", body });
}

function customerLocation(data) {
  return [data.city, data.state].filter(Boolean).join(" – ") || "Localidade não informada";
}

function customerWhatsApp(data) {
  if (!data.whatsapp) return element("span", "order-meta", "WhatsApp indisponível: telefone não informado ou inválido.");
  const anchor = link("Abrir WhatsApp", `https://wa.me/${data.whatsapp}`, "button whatsapp-button");
  anchor.target = "_blank";
  anchor.rel = "noopener noreferrer";
  return anchor;
}

function customerCandidates(host, records, onPick) {
  for (const record of records) {
    const label = [record.name, record.establishment, record.phone, record.city, record.state,
      record.active ? "" : "Inativo"].filter(Boolean).join(" · ");
    host.append(customerButton(label, () => onPick(record)));
  }
}

function mountCustomerPicker(host, options) {
  const input = element("input");
  input.type = "search";
  input.maxLength = 120;
  const label = element("label", "field");
  label.append(element("span", "", "Buscar cliente por nome, estabelecimento ou telefone"), input);
  const hits = element("div", "customer-candidates");
  const duplicates = element("div", "customer-candidates");
  const message = element("p", "order-meta");
  message.setAttribute("role", "status");
  const current = element("p", "order-meta");
  const register = customerCheck("Cadastrar cliente ao concluir");
  const shared = customerCheck("Confirmo que o telefone é compartilhado");
  const clear = customerButton("Limpar vínculo", () => {
    setCurrent(null);
    options.onClear?.();
    input.focus();
  });
  let chosen = null, revision = 0, phoneRevision = 0, timer;

  function setCurrent(record) {
    chosen = record;
    input.value = "";
    register.input.checked = shared.input.checked = false;
    shared.wrapper.hidden = true;
    current.textContent = record ? `Cadastro selecionado: ${record.name}` : "";
    clear.hidden = !record;
    hits.replaceChildren();
    duplicates.replaceChildren();
    message.textContent = "";
    revision++;
    phoneRevision++;
  }

  function pick(record) {
    if (!record.active) {
      message.textContent = "Reative este cadastro no módulo Clientes para vinculá-lo.";
      return;
    }
    setCurrent(record);
    options.onPick?.(record);
    clear.focus({ preventScroll: true });
  }

  async function checkPhone() {
    const version = ++phoneRevision;
    duplicates.replaceChildren();
    shared.wrapper.hidden = true;
    shared.input.checked = false;
    const phone = options.phone?.()?.trim();
    if (!register.input.checked || !phone) return;
    try {
      const records = await customerSearch("phone-check", { phone });
      if (version !== phoneRevision || !input.isConnected || !register.input.checked) return;
      if (!records.length) return;
      duplicates.append(element("p", "notice", "Este telefone já está cadastrado. Use um cadastro ou confirme que o telefone é compartilhado."));
      customerCandidates(duplicates, records, pick);
      shared.wrapper.hidden = false;
    } catch (error) {
      if (version === phoneRevision) message.textContent = error.message;
    }
  }

  input.addEventListener("input", () => {
    const search = input.value.trim();
    const version = ++revision;
    clearTimeout(timer);
    hits.replaceChildren();
    message.textContent = "";
    if (search.length < 2) return;
    timer = setTimeout(async () => {
      try {
        const records = await customerSearch("matches", { search });
        if (version !== revision || !input.isConnected) return;
        if (records.length) customerCandidates(hits, records, pick);
        else message.textContent = "Nenhum cliente ativo encontrado.";
      } catch (error) {
        if (version === revision) message.textContent = error.message;
      }
    }, 250);
  });
  register.input.addEventListener("change", () => {
    if (register.input.checked && chosen) {
      chosen = null;
      current.textContent = "";
      clear.hidden = true;
      options.onClear?.();
    }
    void checkPhone();
  });
  register.wrapper.hidden = !options.allowRegister;
  host.replaceChildren(label, hits, current, clear, register.wrapper, duplicates, shared.wrapper, message);
  setCurrent(null);
  if (options.suggestPhone && options.phone?.()?.trim()) {
    input.value = options.phone().trim();
    input.dispatchEvent(new Event("input"));
  }
  return {
    setCurrent, checkPhone,
    selection() {
      if (chosen) return { id: chosen.id };
      return register.input.checked ? { register: true, allowSharedPhone: shared.input.checked } : null;
    }
  };
}

function showQuoteCustomerLink() {
  const host = byId("quote-customer-link");
  const { ref, master } = quoteCustomer;
  host.replaceChildren();
  host.hidden = !ref;
  if (!ref) return;
  host.append(element("p", "order-meta", `Cliente vinculado: ${master?.name ?? byId("client-name").value}`),
    customerButton("Desvincular cadastro", () => {
      clearQuoteCustomer();
      queueDraftSave();
    }));
  if (!master) return;
  if (!master.active) {
    host.append(element("p", "notice", "Este cadastro está inativo. Reative-o ou desvincule-o para emitir."));
  } else if (master.version !== ref.version) {
    host.append(element("p", "notice", "O cadastro foi atualizado desde a seleção."),
      customerButton("Usar dados atuais do cadastro", () => {
        if (window.confirm("Substituir os dados deste orçamento pelos dados atuais do cadastro?")) applyQuoteCustomer(master);
      }));
  }
}

function clearQuoteCustomer() {
  restoreQuoteCustomer(null, null);
}

function restoreQuoteCustomer(ref, master) {
  quoteCustomer.ref = ref ?? null;
  quoteCustomer.master = master ?? null;
  quoteCustomerPicker?.setCurrent(master);
  showQuoteCustomerLink();
}

function applyQuoteCustomer(record) {
  const form = byId("client-form");
  for (const key of ["name", "phone", "city", "state"]) form.elements.namedItem(key).value = record[key] ?? "";
  restoreQuoteCustomer({ id: record.id, version: record.version }, record);
  queueDraftSave();
}

function quoteCustomerSelection() {
  return quoteCustomer.ref ? { id: quoteCustomer.ref.id } : quoteCustomerPicker.selection();
}

function customerPageButtons(host, page, pages, actionForPage) {
  const previous = action("Página anterior", "chevron-left", () => actionForPage(page - 1));
  const next = action("Próxima página", "chevron-right", () => actionForPage(page + 1));
  previous.disabled = page <= 0;
  next.disabled = page + 1 >= pages;
  host.replaceChildren(previous, element("span", "", `Página ${page + 1} de ${pages}`), next);
  host.hidden = pages < 2;
}

async function loadCustomers(page) {
  const revision = ++customerUi.revision;
  const filter = byId("customer-filter").value;
  formFeedback("customers-feedback", "Carregando clientes…");
  try {
    const result = await customerSearch("search", {
      search: byId("customer-search").value.trim(), active: filter === "" ? null : filter === "true", page
    });
    if (revision !== customerUi.revision) return;
    if (result.pages > 0 && page >= result.pages) return void loadCustomers(result.pages - 1);
    customerUi.page = page;
    const cards = result.content.map(({ customer, sales }) => {
      const row = element("li", "panel panel-body order-card");
      const heading = element("div", "order-card-heading");
      heading.append(element("h2", "", customer.name), element("span", "state-pill", customer.active ? "Ativo" : "Inativo"));
      const actions = element("div", "order-actions");
      actions.append(customerButton("Ver cadastro", () => void openCustomer(customer.id)), customerWhatsApp(customer));
      row.append(heading, element("p", "order-meta", customerLocation(customer)),
        element("strong", "", `${sales.orders} ${sales.orders === 1 ? "pedido confirmado" : "pedidos confirmados"} · ${moneyFormat.format(sales.approvedTotal)}`), actions);
      return row;
    });
    byId("customers-list").replaceChildren(...cards);
    customerPageButtons(byId("customers-pages"), page, result.pages, loadCustomers);
    formFeedback("customers-feedback", cards.length ? "" : "Nenhum cadastro encontrado.");
  } catch (error) {
    if (revision === customerUi.revision) formFeedback("customers-feedback", error.message);
  }
}

function showCustomerDialog(title) {
  const dialog = byId("customer-dialog");
  if (!dialog.open) {
    customerUi.opener = document.activeElement;
    dialog.showModal();
  }
  byId("customer-title").textContent = title;
  dialog.scrollTop = 0;
  byId("customer-title").focus({ preventScroll: true });
}

async function openCustomer(id) {
  const revision = ++customerUi.dialogRevision;
  customerUi.current = null;
  byId("customer-body").replaceChildren(element("p", "", "Carregando cadastro…"));
  byId("customer-form").hidden = true;
  showCustomerDialog("Cadastro do cliente");
  try {
    const detail = await api(`/api/customers/${id}`);
    if (revision === customerUi.dialogRevision && byId("customer-dialog").open) renderCustomer(detail);
  } catch (error) {
    if (revision === customerUi.dialogRevision) byId("customer-body").textContent = error.message;
  }
}

function renderCustomer(detail) {
  customerUi.current = detail;
  const { customer, indicators } = detail;
  const { sales } = indicators;
  byId("customer-form").hidden = true;
  const actions = element("div", "order-actions");
  actions.append(customerButton("Editar cliente", () => editCustomer(customer)),
    customerButton(customer.active ? "Desativar cliente" : "Reativar cliente", () => void activateCustomer(customer)),
    customerWhatsApp(customer));
  if (customer.active) actions.prepend(customerButton("Novo orçamento", () => void newQuoteForCustomer(customer), "primary"));
  const history = element("div", "customer-histories");
  for (const [label, orders] of [["Histórico de orçamentos", false], ["Histórico de pedidos", true]]) {
    const group = element("details", "order-audit");
    const content = element("div");
    content.hidden = true;
    group.append(element("summary", "", label), content);
    group.addEventListener("toggle", () => {
      content.hidden = !group.open;
      if (group.open && !content.hasChildNodes()) void loadCustomerHistory(customer.id, orders, 0, content);
    });
    history.append(group);
  }
  byId("customer-body").replaceChildren(orderFacts([
    ["Estabelecimento", customer.establishment || "—"], ["Telefone", customer.phone || "—"],
    ["E-mail", customer.email || "—"], ["Cidade / UF", customerLocation(customer)], ["Cadastro", orderDate(customer.createdAt)], ["Última atualização", orderDate(customer.updatedAt)],
    ["Situação", customer.active ? "Ativo" : "Inativo"], ["Observações", customer.notes || "—"],
    ["Orçamentos emitidos", indicators.quotes], ["Aguardando confirmação", indicators.pending],
    ["Pedidos confirmados", sales.orders], ["Iscas em pedidos confirmados", sales.units],
    ["Valor dos pedidos confirmados", moneyFormat.format(sales.approvedTotal)], ["Último pedido", orderDate(indicators.lastOrderAt)]
  ]), actions, history);
  showCustomerDialog(customer.name);
}

function refreshCustomerOrderStatus(record) {
  for (const card of byId("customer-body").querySelectorAll("[data-order-number]")) {
    if (card.dataset.orderNumber === String(record.quoteNumber)) {
      card.querySelector(".state-pill")?.replaceWith(orderStatusBadge(record.fulfillment ?? record.status));
    }
  }
}

async function loadCustomerHistory(id, orders, page, host) {
  host.replaceChildren(element("p", "order-meta", "Consultando histórico…"));
  try {
    const result = await api(`/api/customers/${id}/history?orders=${orders}&page=${page}`);
    const rows = element("ul", "orders-list");
    rows.append(...result.content.map(orderCard));
    const pager = element("div", "pagination");
    customerPageButtons(pager, page, result.pages, next => void loadCustomerHistory(id, orders, next, host));
    host.replaceChildren(result.content.length ? rows : element("p", "order-meta", "Nenhum registro vinculado."), pager);
  } catch (error) {
    host.replaceChildren(element("p", "form-error", error.message),
      customerButton("Tentar novamente", () => void loadCustomerHistory(id, orders, page, host)));
  }
}

async function newQuoteForCustomer(customer) {
  await withDraftAction(async () => {
    if (!await flushDraft()) {
      toast("Sincronize o rascunho atual antes de abrir outro orçamento.");
      return;
    }
    resetDraftWorkspace();
    applyQuoteCustomer(customer);
    byId("customer-dialog").close();
    if (byId("order-dialog").open) byId("order-dialog").close();
    location.hash = "novo-orcamento";
  });
}

function editCustomer(customer = null) {
  const form = byId("customer-form");
  form.reset();
  for (const key of ["name", "establishment", "phone", "email", "city", "state", "notes"]) {
    form.elements.namedItem(key).value = customer?.[key] ?? "";
  }
  byId("customer-body").replaceChildren();
  resetCustomerDuplicates();
  formFeedback("customer-form-error", "");
  form.hidden = false;
  showCustomerDialog(customer ? "Editar cliente" : "Cadastrar cliente");
}

function resetCustomerDuplicates() {
  byId("customer-duplicates").replaceChildren();
  byId("customer-duplicates").hidden = true;
}

async function saveCustomer(event) {
  event.preventDefault();
  if (customerUi.busy) return;
  const form = byId("customer-form");
  const original = customerUi.current?.customer;
  const data = Object.fromEntries(new FormData(form));
  data.allowSharedPhone = Boolean(form.querySelector("#customer-share-phone")?.checked);
  customerUi.busy = true;
  const buttons = ["customer-save", "customer-close", "customer-back"].map(byId);
  buttons.forEach(button => { button.disabled = true; });
  try {
    const saved = await api(original ? `/api/customers/${original.id}` : "/api/customers", {
      method: original ? "PUT" : "POST", body: original ? { version: original.version, data } : data
    });
    toast(original ? "Cadastro atualizado." : "Cliente cadastrado.");
    if (state.route?.page === "customers") void loadCustomers(customerUi.page);
    await openCustomer(saved.id);
  } catch (error) {
    formFeedback("customer-form-error", error.message);
    if (error.status === 409 && data.phone && !data.allowSharedPhone) {
      try {
        const options = await customerSearch("phone-check", { phone: data.phone, except: original?.id });
        if (options.length && form.elements.namedItem("phone").value === data.phone) showDuplicateCustomers(options);
      } catch {
        // Preserve the original feedback.
      }
    }
  } finally {
    customerUi.busy = false;
    buttons.forEach(button => { button.disabled = false; });
  }
}

function showDuplicateCustomers(options) {
  const host = byId("customer-duplicates");
  host.hidden = false;
  const shared = customerCheck("Confirmo que o telefone é compartilhado");
  shared.input.id = "customer-share-phone";
  host.replaceChildren(element("p", "notice", "Cadastros com o mesmo telefone"));
  customerCandidates(host, options, record => {
    if (window.confirm("Abrir o cadastro existente e sair desta edição?")) void openCustomer(record.id);
  });
  host.append(shared.wrapper);
}

async function activateCustomer(customer) {
  if (customerUi.busy || !window.confirm(customer.active ? "Desativar este cliente?" : "Reativar este cliente?")) return;
  customerUi.busy = true;
  try {
    await api(`/api/customers/${customer.id}/active`, {
      method: "PUT", body: { version: customer.version, active: !customer.active }
    });
    toast(customer.active ? "Cliente desativado." : "Cliente reativado.");
    if (state.route?.page === "customers") void loadCustomers(customerUi.page);
    await openCustomer(customer.id);
  } catch (error) {
    toast(error.message);
  } finally {
    customerUi.busy = false;
  }
}

function bindCustomerEvents() {
  quoteCustomerPicker = mountCustomerPicker(byId("quote-customer-picker"), {
    allowRegister: true, phone: () => byId("client-phone").value, onPick: applyQuoteCustomer,
    onClear: () => { quoteCustomer.ref = quoteCustomer.master = null; showQuoteCustomerLink(); queueDraftSave(); }
  });
  byId("quote-customer-details").addEventListener("toggle", event => {
    byId("quote-customer-picker").hidden = !event.currentTarget.open;
  });
  byId("client-phone").addEventListener("change", () => void quoteCustomerPicker.checkPhone());
  const dialog = byId("customer-dialog");
  let searchTimer;
  byId("customer-filters").addEventListener("submit", event => event.preventDefault());
  byId("customer-search").addEventListener("input", () => {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(() => void loadCustomers(0), 250);
  });
  byId("customer-filter").addEventListener("change", () => void loadCustomers(0));
  byId("customer-new").addEventListener("click", () => { customerUi.current = null; editCustomer(); });
  byId("customer-form").addEventListener("submit", saveCustomer);
  byId("customer-form").elements.namedItem("phone").addEventListener("input", resetCustomerDuplicates);
  byId("customer-back").addEventListener("click", () => {
    if (customerUi.current) renderCustomer(customerUi.current);
    else dialog.close();
  });
  byId("customer-close").addEventListener("click", () => { if (!customerUi.busy) dialog.close(); });
  dialog.addEventListener("cancel", event => { if (customerUi.busy) event.preventDefault(); });
  dialog.addEventListener("close", () => {
    customerUi.dialogRevision++;
    (customerUi.opener?.isConnected ? customerUi.opener : state.route?.page === "customers"
      ? byId("customers-title") : byId("main")).focus({ preventScroll: true });
  });
}
