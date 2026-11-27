
"use strict";

const draftDate = new Intl.DateTimeFormat("pt-BR", {
  dateStyle: "short", timeStyle: "short", timeZone: "America/Sao_Paulo"
});
const draftApiPath = "/api/quote-drafts";
const draftUi = { entries: [], current: null, busy: false, listRevision: 0, unavailableEditor: null, pendingAi: [] };

function draftContent() {
  const editor = draftUi.unavailableEditor;
  const colors = new Map(editor?.colors.map(({ variantId, quantity }) => [variantId, quantity]));
  for (const [id, quantity] of state.selectedColors) colors.set(id, quantity);
  return {
    client: readQuoteClient(byId("client-form")),
    items: draftItems(),
    notes: byId("draft-notes").value,
    editor: {
      modelId: Number(byId("item-model").value) || editor?.modelId || null,
      weight: Number(byId("item-weight").value) || editor?.weight || null,
      defaultQuantity: draftEditorAmount(byId("item-quantity").value),
      editingId: state.editingItem,
      editingQuantity: state.editingQuantity === null ? null : draftEditorAmount(state.editingQuantity),
      colors: [...colors].map(([variantId, quantity]) => ({ variantId, quantity: draftEditorAmount(quantity) }))
    },
    pendingAi: draftUi.pendingAi.map(({ variantId, quantity }) => ({ variantId, quantity })),
    customer: quoteCustomer.ref
  };
}

function draftEditorAmount(value) {
  return /^[0-9eE+.-]{0,24}$/u.test(value) ? value : "";
}

function meaningfulDraft({ client, items, notes, editor, pendingAi }) {
  return Object.values(client).some(Boolean) || items.length > 0 || notes.trim() !== "" || pendingAi.length > 0
    || editor.modelId !== null || editor.defaultQuantity !== "1" || editor.editingId !== null;
}

function newDraftSync(id = generateRequestId(), version = null) {
  return { id, version, synced: null, timer: null, saving: null, discarding: false, conflict: false, error: false };
}

function draftIsDirty(draft = draftUi.current) {
  if (!draft || state.issueConfirmation) return false;
  const content = draftContent();
  return JSON.stringify(content) !== draft.synced && (draft.version !== null || meaningfulDraft(content));
}

function draftOffline() {
  return !navigator.onLine || window.urutauPwa?.isOffline();
}

function updateDraftStatus() {
  const draft = draftUi.current;
  const dirty = draftIsDirty(draft);
  byId("draft-offline-note").hidden = !dirty;
  if (state.issueBusy || state.issueConfirmation) return;
  const status = byId("draft-save-status");
  const offline = dirty && draftOffline();
  const message = offline ? "Sem conexão — sincronização pendente"
    : draft?.conflict ? "A versão deste rascunho mudou ou ele foi encerrado em outro dispositivo."
      : draft?.error && dirty ? "Não foi possível salvar. Tentar novamente."
        : draft?.saving ? "Salvando rascunho..."
          : draft?.version != null ? dirty ? "Alterações aguardando salvamento" : "Rascunho salvo automaticamente"
            : "";
  if (status.textContent !== message) status.textContent = message;
  byId("draft-retry").hidden = !dirty || Boolean(draft.conflict || draft.saving || (!draft.error && !offline));
  byId("draft-conflict").hidden = !draft?.conflict;
  byId("draft-loss-warning").hidden = !dirty || (!offline && !draft.error && !draft.conflict);
  byId("draft-ai-review").hidden = !draftUi.pendingAi.length;
  byId("draft-editor-clear").hidden = !draftUi.unavailableEditor;
  window.urutauPwa?.renderUpdate();
}

function queueDraftSave() {
  if (!state.session || state.issueConfirmation) return;
  if (!draftUi.current && meaningfulDraft(draftContent())) draftUi.current = newDraftSync();
  const draft = draftUi.current;
  if (draft) {
    clearTimeout(draft.timer);
    if (draftIsDirty(draft) && !draft.discarding && !draft.conflict && !draftOffline()) {
      draft.timer = setTimeout(() => void flushDraft(false), 1000);
    }
  }
  updateDraftStatus();
}

async function flushDraft(saveAll = true) {
  const draft = draftUi.current;
  if (!draft) return true;
  if (draft.discarding) return false;
  clearTimeout(draft.timer);
  if (draft.saving) {
    if (!saveAll) return draft.saving;
    await draft.saving;
    if (draftUi.current !== draft || draft.error || draft.conflict) return false;
    return flushDraft();
  }
  if (!draftIsDirty(draft)) return !draft.conflict;
  if (draft.conflict || draftOffline()) {
    updateDraftStatus();
    return false;
  }
  const content = draftContent();
  const snapshot = JSON.stringify(content);
  draft.error = false;
  draft.saving = (async () => {
    try {
      const saved = await api(`${draftApiPath}/${draft.id}`, { method: "PUT", body: { version: draft.version, content } });
      draft.version = saved.version;
      draft.synced = snapshot;
      rememberDraft(saved);
    } catch (error) {
      draft.conflict = error.status === 409 || error.status === 404;
      draft.error = !draft.conflict;
    } finally {
      draft.saving = null;
      updateDraftStatus();
    }
    return !draft.error && !draft.conflict;
  })();
  updateDraftStatus();
  if (!await draft.saving) return false;
  if (draftIsDirty(draft)) {
    if (saveAll) return flushDraft();
    queueDraftSave();
  }
  return true;
}

async function checkDraftAfterIssue({ draftId, draftVersion }) {
  const draft = draftUi.current;
  if (draft?.id !== draftId) return;
  try {
    const opened = await api(`${draftApiPath}/${draftId}`);
    draft.conflict = opened.summary.version !== draftVersion;
  } catch (error) {
    if (error.status === 404) draft.conflict = true;
  }
  updateDraftStatus();
}

function renderDraftList() {
  if (state.issueBusy || state.issueConfirmation) return;
  const container = byId("draft-list");
  const active = document.activeElement;
  const focusedId = container.contains(active) ? active.dataset.draftAction : null;
  const latest = draftUi.entries[0];
  byId("draft-latest").hidden = !latest;
  byId("draft-list-toggle").hidden = !latest;
  if (!latest) byId("draft-list-toggle").open = false;
  container.hidden = !byId("draft-list-toggle").open;
  container.replaceChildren(...draftUi.entries.map(draft => {
    const row = element("li", "draft-list-row");
    const description = element("div");
    const total = draft.estimatedTotal != null ? ` · Estimativa ${moneyFormat.format(draft.estimatedTotal)}` : "";
    const date = element("time", "", draftDate.format(new Date(draft.updatedAt)));
    date.dateTime = draft.updatedAt;
    description.append(element("strong", "", draft.clientName || "Cliente não informado"),
      element("small", "", `${unitLabel(draft.quantity)} · ${draft.combinations} ${draft.combinations === 1 ? "combinação" : "combinações"}${total}`), date);
    const actions = element("div", "draft-actions");
    for (const [label, action, handler] of [
      ["Continuar", "open", () => openDraft(draft.id)], ["Descartar", "discard", () => discardDraft(draft)]
    ]) {
      const button = element("button", "button secondary", label);
      button.type = "button";
      button.dataset.draftAction = `${draft.id}-${action}`;
      button.addEventListener("click", () => void handler());
      actions.append(button);
    }
    row.append(description, actions);
    return row;
  }));
  if (focusedId) {
    const button = [...container.querySelectorAll("button")].find(node => node.dataset.draftAction === focusedId);
    (button ?? byId("draft-new")).focus({ preventScroll: true });
  }
}

function rememberDraft(saved) {
  draftUi.listRevision++;
  draftUi.entries = [saved, ...draftUi.entries.filter(draft => draft.id !== saved.id)]
    .sort((a, b) => Date.parse(b.updatedAt) - Date.parse(a.updatedAt));
  renderDraftList();
}

function forgetDraft(id) {
  draftUi.listRevision++;
  draftUi.entries = draftUi.entries.filter(draft => draft.id !== id);
  renderDraftList();
}

async function refreshDraftList() {
  const revision = ++draftUi.listRevision;
  try {
    const entries = await api(draftApiPath);
    if (revision !== draftUi.listRevision) return;
    draftUi.entries = entries;
    renderDraftList();
    formFeedback("draft-list-error", "");
  } catch (error) {
    if (revision === draftUi.listRevision && !draftOffline()) formFeedback("draft-list-error", error.message);
  }
}

async function withDraftAction(action) {
  if (draftUi.busy || state.issueBusy || state.issueConfirmation || state.aiBusy) return;
  draftUi.busy = true;
  formFeedback("draft-list-error", "");
  try {
    await action();
  } catch (error) {
    formFeedback("draft-list-error", error.message);
  } finally {
    draftUi.busy = false;
    updateDraftStatus();
  }
}

function clearDraftEditor() {
  draftUi.unavailableEditor = null;
  byId("draft-editor-clear").hidden = true;
}

function resetDraftWorkspace() {
  clearTimeout(draftUi.current?.timer);
  draftUi.current = null;
  draftUi.pendingAi = [];
  state.draft = [];
  state.quoteAttempt = null;
  byId("client-form").reset();
  clearQuoteCustomer();
  formFeedback("draft-restore-notice", "");
  resetItemEditor();
  updateDraftStatus();
  void updatePreview();
}

function hydrateDraft(opened) {
  const { summary, content, variants, estimateChanged, editorUnavailable } = opened;
  const { editor } = content;
  resetDraftWorkspace();
  const catalog = new Map(variants.map(variant => [variant.variantId, variant]));
  const details = items => items.map(item => ({ ...catalog.get(item.variantId), ...item }));
  state.draft = details(content.items);
  draftUi.pendingAi = details(content.pendingAi);
  for (const [name, value] of Object.entries(content.client)) {
    byId("client-form").elements.namedItem(name).value = value ?? "";
  }
  byId("draft-notes").value = content.notes;
  restoreQuoteCustomer(content.customer, opened.customer);
  byId("item-quantity").value = editor.defaultQuantity;
  state.editingItem = editor.editingId;
  state.editingQuantity = editor.editingQuantity;
  byId("item-add").querySelector("span").textContent = state.editingItem === null ? "Adicionar ao orçamento" : "Salvar item";
  byId("item-cancel").hidden = state.editingItem === null;
  state.selectedColors = new Map(editor.colors.map(({ variantId, quantity }) => [variantId, quantity]));
  renderManualCatalog({ modelId: editor.modelId ?? "", weight: editor.weight ?? "" });
  if (editorUnavailable || editor.colors.some(color => !state.selectedColors.has(color.variantId))) {
    draftUi.unavailableEditor = { ...editor, colors: editor.colors.filter(color => !state.selectedColors.has(color.variantId)) };
  }
  const notices = [];
  if (estimateChanged) notices.push("Os preços ou o limite de lojista mudaram. Confira a estimativa atual.");
  if ([...state.draft, ...draftUi.pendingAi].some(item => !item.active)) {
    notices.push("Há itens indisponíveis no rascunho. Confira as combinações antes de emitir.");
  }
  if (draftUi.unavailableEditor) notices.push("Algumas seleções pendentes do catálogo estão indisponíveis.");
  formFeedback("draft-restore-notice", notices.join(" "));
  rememberDraft(summary);
  draftUi.current = newDraftSync(summary.id, summary.version);
  draftUi.current.synced = JSON.stringify(draftContent());
  updateDraftStatus();
  void updatePreview();
}

function openDraft(id, ignoreConflict = false) {
  return withDraftAction(async () => {
    if (!ignoreConflict && !await flushDraft()) return;
    const snapshot = JSON.stringify(draftContent());
    const opened = await api(`${draftApiPath}/${id}`);
    await loadCatalog();
    if (!state.catalog) throw new Error("Não foi possível carregar o catálogo para abrir o rascunho.");
    if (snapshot !== JSON.stringify(draftContent())) {
      throw new Error("O orçamento atual mudou durante a recuperação. Tente abrir o rascunho novamente.");
    }
    hydrateDraft(opened);
    byId("client-name").focus({ preventScroll: true });
  });
}

function discardDraft(entry) {
  return withDraftAction(async () => {
    const current = draftUi.current;
    if (!window.confirm("Descartar este rascunho definitivamente?")) return;
    const editingThisDraft = current?.id === entry.id;
    const confirmed = editingThisDraft ? JSON.stringify(draftContent()) : null;
    if (editingThisDraft) {
      current.discarding = true;
      clearTimeout(current.timer);
      await current.saving;
    }
    const version = editingThisDraft ? current.version : entry.version;
    try {
      await api(`${draftApiPath}/${entry.id}?version=${version}`, { method: "DELETE" });
    } catch (error) {
      if (editingThisDraft) {
        current.discarding = false;
        current.conflict = error.status === 409 || error.status === 404;
        if (!current.conflict) queueDraftSave();
      }
      void refreshDraftList();
      throw error;
    }
    forgetDraft(entry.id);
    if (editingThisDraft && confirmed === JSON.stringify(draftContent())) {
      resetDraftWorkspace();
    } else if (editingThisDraft) {
      draftUi.current = null;
      queueDraftSave();
    }
  });
}

function bindDraftEvents() {
  for (const type of ["input", "change"]) {
    byId("client-form").addEventListener(type, queueDraftSave);
    byId("item-form").addEventListener(type, queueDraftSave);
  }
  for (const id of ["item-select-all", "item-clear-all", "item-apply-quantity", "item-cancel"]) {
    byId(id).addEventListener("click", queueDraftSave);
  }
  byId("draft-new").addEventListener("click", () => void withDraftAction(async () => {
    if (draftUi.current?.conflict) {
      if (!window.confirm("Iniciar outro orçamento e descartar as alterações abertas neste dispositivo?")) return;
    } else if (!await flushDraft()) {
      return;
    }
    resetDraftWorkspace();
    byId("client-name").focus({ preventScroll: true });
  }));
  byId("draft-list-toggle").addEventListener("toggle", event => { byId("draft-list").hidden = !event.currentTarget.open; });
  byId("draft-latest").addEventListener("click", () => {
    if (draftUi.entries[0]) void openDraft(draftUi.entries[0].id);
  });
  byId("draft-retry").addEventListener("click", async () => {
    if (draftOffline()) await window.urutauPwa?.checkConnection();
    await flushDraft();
  });
  byId("draft-reload").addEventListener("click", () => {
    if (draftUi.current?.conflict && window.confirm("Carregar a versão do servidor no lugar das alterações abertas neste dispositivo?")) {
      void openDraft(draftUi.current.id, true);
    }
  });
  byId("draft-copy").addEventListener("click", () => void withDraftAction(async () => {
    if (!meaningfulDraft(draftContent())) {
      formFeedback("draft-list-error", "Inclua informações antes de salvar outro rascunho.");
      return;
    }
    clearTimeout(draftUi.current?.timer);
    draftUi.current = newDraftSync();
    state.quoteAttempt = null;
    await flushDraft();
    void refreshDraftList();
  }));
  byId("draft-editor-clear").addEventListener("click", () => {
    clearDraftEditor();
    formFeedback("draft-restore-notice", "");
    queueDraftSave();
  });
  byId("draft-ai-review").addEventListener("click", () => reviewItems(draftUi.pendingAi));
  addEventListener("offline", updateDraftStatus);
  addEventListener("urutau:offline", updateDraftStatus);
  document.addEventListener("visibilitychange", () => {
    if (document.hidden && draftIsDirty()) void flushDraft(false);
  });
  addEventListener("beforeunload", event => {
    if (draftIsDirty()) {
      event.preventDefault();
      event.returnValue = true;
    }
  });
}
