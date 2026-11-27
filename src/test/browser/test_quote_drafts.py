
"""Exercita a recuperação de rascunhos em Chromium e WebKit contra o JAR."""

from argparse import ArgumentParser
from pathlib import Path
import sys

from playwright.sync_api import expect, sync_playwright

sys.dont_write_bytecode = True
from test_bulk_colors import choose, color, isolated_application, login, quantity

DRAFTS = "/api/quote-drafts"
SAVED = "Rascunho salvo automaticamente"
CLIENT = "Cliente fictício QA"


def drafts(page):
    response = page.request.get(DRAFTS)
    assert response.ok, response.status
    return response.json()


def saved(page):
    expect(page.locator("#draft-save-status")).to_have_text(SAVED)


def open_latest(page):
    page.locator("#draft-latest").click()
    saved(page)


def create(page):
    writes = []
    page.on("request", lambda request: writes.append(request) if request.method == "PUT" and DRAFTS in request.url else None)
    assert drafts(page) == []
    name = page.locator("#client-name")
    name.press_sequentially(CLIENT, delay=25)
    expect(name).to_be_focused()
    assert not writes, "O salvamento disparou enquanto o nome ainda estava sendo digitado."
    saved(page)
    expect(name).to_be_focused()
    assert len(writes) == 1

    page.locator("#client-phone").fill("(11) 90000-0000")
    page.locator("#draft-notes").fill("Linha 1\nLinha 2")
    choose(page, "Spinner", 12)
    color(page, "03").check()
    quantity(page, "03").fill("5")
    page.locator("#item-add").click()
    color(page, "08").check()
    quantity(page, "08").fill("")
    saved(page)
    stored = drafts(page)
    assert len(stored) == 1 and stored[0]["quantity"] == 5 and stored[0]["estimatedTotal"] == "130.00"
    return stored[0]["id"]


def assert_restored(page):
    expect(page.locator("#client-name")).to_have_value(CLIENT)
    expect(page.locator("#client-phone")).to_have_value("(11) 90000-0000")
    expect(page.locator("#draft-notes")).to_have_value("Linha 1\nLinha 2")
    expect(page.locator("#summary-quantity")).to_have_text("5 unidades")
    expect(page.locator("#item-weight")).to_have_value("12")
    expect(color(page, "08")).to_be_checked()
    expect(quantity(page, "08")).to_have_value("")


def conflicts(first, second, original):
    open_latest(second)
    assert_restored(second)
    first.locator("#draft-notes").fill("Observação no primeiro dispositivo")
    saved(first)
    second.locator("#draft-notes").fill("Versão local")
    expect(second.locator("#draft-conflict")).to_be_visible()
    second.once("dialog", lambda dialog: dialog.accept())
    second.locator("#draft-reload").click()
    saved(second)
    expect(second.locator("#draft-notes")).to_have_value("Observação no primeiro dispositivo")

    first.locator("#draft-notes").fill("Edição mais recente")
    saved(first)
    second.locator("#draft-notes").fill("Preservar esta edição")
    expect(second.locator("#draft-conflict")).to_be_visible()
    second.locator("#draft-copy").click()
    saved(second)
    records = drafts(second)
    assert len(records) == 2 and records[0]["id"] != original
    kept = second.request.get(f"{DRAFTS}/{original}").json()
    assert kept["content"]["notes"] == "Edição mais recente"
    return records[0]["id"]


def offline(context, page, draft_id):
    field = page.locator("#draft-notes")
    field.fill("Alteração ainda em memória")
    context.set_offline(True)
    expect(page.locator("#pwa-offline")).to_be_visible()
    expect(page.locator("#draft-offline-note")).to_contain_text("podem ser perdidas")
    assert page.evaluate("!dispatchEvent(new Event('beforeunload', {cancelable: true}))")
    context.set_offline(False)
    expect(page.locator("#pwa-offline")).not_to_be_visible(timeout=15000)
    saved(page)
    assert page.request.get(f"{DRAFTS}/{draft_id}").json()["content"]["notes"] == "Alteração ainda em memória"
    assert page.evaluate("dispatchEvent(new Event('beforeunload', {cancelable: true}))")
    assert page.evaluate("""async () => {
        const keys = await Promise.all((await caches.keys()).map(async key => (await caches.open(key)).keys()));
        return keys.flat().every(request => !new URL(request.url).pathname.startsWith('/api/'))
          && !JSON.stringify(localStorage).includes('Alteração ainda em memória');
    }""")


def fail_next_request(page, method, path):
    # Intercept at fetch to keep the service worker active in both engines.
    page.evaluate("""({method, path}) => {
        const original = window.fetch;
        window.fetch = function(input, options) {
            const url = new URL(input instanceof Request ? input.url : input, location.href);
            if (options?.method === method && url.pathname === path) {
                window.fetch = original;
                return Promise.resolve(Response.json({message: "Falha de teste"}, {status: 500}));
            }
            return original.call(this, input, options);
        };
    }""", {"method": method, "path": path})


def retry_save(page, draft_id):
    fail_next_request(page, "PUT", f"{DRAFTS}/{draft_id}")
    page.locator("#draft-notes").fill("Nova tentativa de sincronização")
    expect(page.locator("#draft-retry")).to_be_visible()
    page.locator("#draft-retry").click()
    saved(page)


def issue_and_discard(page, draft_id, untouched):
    fail_next_request(page, "POST", "/api/quotes")
    page.locator("#quote-issue").click()
    expect(page.locator("#toast")).to_have_text("Falha de teste")
    expect(page.locator("#quote-success")).not_to_be_visible()
    assert any(draft["id"] == draft_id for draft in drafts(page))
    page.locator("#quote-issue").click()
    expect(page.locator("#quote-success")).to_be_visible()
    assert [draft["id"] for draft in drafts(page)] == [untouched]
    page.locator("#quote-success-close").click()

    page.locator("#draft-list-toggle summary").click()
    discard = page.locator("#draft-list").get_by_role("button", name="Descartar")
    page.once("dialog", lambda dialog: dialog.dismiss())
    discard.click()
    assert len(drafts(page)) == 1
    page.once("dialog", lambda dialog: dialog.accept())
    discard.click()
    expect(page.locator("#draft-list-toggle")).not_to_be_visible()
    assert not drafts(page)


def ai_recovery(page):
    page.goto("/#novo-orcamento-ia")
    field = page.locator("#chat-message")
    field.fill("• Spinner | 12g | Cor 03 | Qtd: 5\n\n*Total: 5 unidades*")
    page.locator("#chat-send").click()
    expect(page.locator("#ai-review")).to_be_enabled()
    saved(page)
    draft_id = drafts(page)[0]["id"]
    assert page.request.get(f"{DRAFTS}/{draft_id}").json()["content"]["pendingAi"][0]["quantity"] == 5

    ai_requests = []
    page.on("request", lambda request: ai_requests.append(request) if "/api/ai/conversations" in request.url else None)
    page.reload()
    open_latest(page)
    page.locator("#draft-ai-review").click()
    expect(page.locator("#summary-quantity")).to_have_text("5 unidades")
    saved(page)
    content = page.request.get(f"{DRAFTS}/{draft_id}").json()["content"]
    assert len(content["items"]) == 1 and not content["pendingAi"]
    assert not ai_requests, "A recuperação não deve consultar a IA."


def main():
    parser = ArgumentParser(description=__doc__)
    parser.add_argument("jar", type=Path)
    parser.add_argument("--engine", choices=("chromium", "webkit"), default="chromium")
    parser.add_argument("--channel")
    args = parser.parse_args()
    jar = args.jar.resolve(strict=True)
    with isolated_application(jar) as (url, user, password), sync_playwright() as playwright:
        with getattr(playwright, args.engine).launch(channel=args.channel) as browser:
            with browser.new_context(base_url=url, viewport={"width": 320, "height": 660}, has_touch=True) as initial:
                page = initial.new_page()
                login(page, user, password)
                original = create(page)
                assert page.evaluate("document.documentElement.scrollWidth <= innerWidth")

            with browser.new_context(base_url=url, viewport={"width": 1280, "height": 850}) as first, \
                    browser.new_context(base_url=url, viewport={"width": 390, "height": 844}, has_touch=True) as second:
                second.add_init_script('Object.defineProperty(navigator, "standalone", { value: true });')
                desktop, mobile = first.new_page(), second.new_page()
                login(desktop, user, password)
                login(mobile, user, password)
                expect(mobile.locator("#pwa-install")).not_to_be_visible()
                open_latest(desktop)
                assert_restored(desktop)
                copy = conflicts(desktop, mobile, original)
                offline(second, mobile, copy)
                retry_save(mobile, copy)
                issue_and_discard(mobile, copy, original)
                ai_recovery(mobile)
    print(f"{args.engine}: salvamento, recuperação em outro dispositivo, conflito, offline, emissão e IA validados.", flush=True)


if __name__ == "__main__":
    main()
