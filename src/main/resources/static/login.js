"use strict";

const message = document.getElementById("login-message");
const submit = document.getElementById("login-submit");
const form = document.getElementById("login-form");
const query = new URLSearchParams(location.search);

function show(text, success = false) {
  message.hidden = false;
  message.classList.toggle("success", success);
  message.textContent = text;
}

if (query.has("erro")) show("Usuário ou senha incorretos.");
if (query.has("saiu")) show("Sessão encerrada.", true);

async function loadToken() {
  try {
    const response = await fetch("/csrf", { headers: { Accept: "application/json" }, credentials: "same-origin", cache: "no-store" });
    if (!response.ok) throw new Error("Token indisponível");
    const { token, parameterName } = await response.json();
    if (!token || !parameterName) throw new Error("Token ausente");
    const input = document.getElementById("login-csrf");
    input.name = parameterName;
    input.value = token;
    submit.disabled = false;
  } catch {
    void window.urutauPwa?.checkConnection();
    show("Não foi possível iniciar a sessão. Atualize a página para tentar novamente.");
  }
}

form.addEventListener("submit", event => {
  if (submit.disabled || window.urutauPwa?.isOffline()) event.preventDefault();
  else submit.disabled = true;
});

addEventListener("urutau:online", () => { if (submit.disabled) void loadToken(); });
void loadToken();
