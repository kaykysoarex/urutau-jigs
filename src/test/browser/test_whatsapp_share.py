"""Testa o compartilhamento de orçamentos emitidos sem enviar mensagens."""

from argparse import ArgumentParser
from decimal import Decimal
from hashlib import sha256
from pathlib import Path
import re
import sys
from urllib.parse import parse_qs, urlsplit

from playwright.sync_api import expect, sync_playwright

# A suíte compartilha o processo Java e a sessão de teste da suíte de orçamentos.
sys.dont_write_bytecode = True
from test_bulk_colors import choose, color, find_product, isolated_application, login, put_setting, submit_and_check_preview


SHARE_LABEL = "Compartilhar pelo WhatsApp"
CLIENT = "Pessoa QA & Silva"
PHONE = "+55 (11) 98765-4321"
MESSAGE = (
    "{greeting}\n\n"
    "Segue o orçamento solicitado das iscas artesanais URUTAU JIGS.\n\n"
    "Qualquer dúvida, estamos à disposição.\n\n"
    "Obrigado pela preferência!\n"
    "URUTAU JIGS"
)


def browser_fixture(context, base_url, mode):
    # Impede qualquer navegação externa real durante a suíte.
    context.route(
        lambda url: url.startswith(("http://", "https://")) and not url.startswith(base_url + "/"),
        lambda route: route.abort(),
    )
    context.add_init_script("""
        window.shareFixture = { calls: [], clipboard: [], mode: "%s" };
        Object.defineProperty(navigator, "canShare", { configurable: true, value(data) {
            return shareFixture.mode !== "unsupported" && data.files?.length === 1;
        } });
        Object.defineProperty(navigator, "share", { configurable: true, value(data) {
            const [file] = data.files;
            const call = {
                name: file.name, type: file.type, text: data.text, title: data.title,
                hasUrl: Object.hasOwn(data, "url"),
                activation: navigator.userActivation.isActive,
            };
            shareFixture.calls.push(call);
            if (shareFixture.mode === "cancel") {
                return Promise.reject(new DOMException("Cancelado", "AbortError"));
            }
            if (shareFixture.mode === "reject") {
                return Promise.reject(new DOMException("Indisponível", "NotAllowedError"));
            }
            shareFixture.lastShare = file.arrayBuffer().then(bytes => crypto.subtle.digest("SHA-256", bytes)).then(digest => {
                call.sha256 = Array.from(new Uint8Array(digest), value => value.toString(16).padStart(2, "0")).join("");
            });
            return shareFixture.lastShare;
        } });
        Object.defineProperty(navigator, "clipboard", { configurable: true, value: {
            async writeText(text) { shareFixture.clipboard.push(text); }
        } });
        """ % mode)


def variant_for_fixture(page):
    spinner = find_product(page, "Spinner")
    return next(item["id"] for item in spinner["variants"] if item["weight"] == 12 and item["color"] == "03")


def issue_quote(page, variant_id, client, phone="", check_preview=False):
    choose(page, "Spinner", 12)
    color(page, "03").check()
    page.get_by_label("Quantidade da cor 03", exact=True).fill("2")
    submit_and_check_preview(page, page.locator("#item-add").click, {variant_id: 2})
    page.locator("#client-name").fill(client)
    page.locator("#client-phone").fill(phone)

    if check_preview:
        with page.expect_response("**/api/quotes/preview/pdf") as response:
            page.locator("#quote-preview-pdf").click()
        assert response.value.ok and response.value.body().startswith(b"%PDF-")
        preview = page.locator("#preview-pdf-dialog")
        expect(preview.get_by_role("link", name="Baixar PDF")).to_be_visible()
        expect(preview.get_by_role("button", name=SHARE_LABEL)).to_have_count(0)
        page.locator("#preview-pdf-close").click()
        expect(preview).not_to_be_visible()

    with page.expect_response(lambda response: response.url.endswith("/api/quotes")
                              and response.request.method == "POST") as pending:
        page.locator("#quote-issue").click()
    response = pending.value
    assert response.status == 201, (response.status, response.text())
    quote = response.json()
    expect(page.locator("#quote-success")).to_contain_text(f'{quote["number"]:05}')
    return quote


def history_record(page, number):
    response = page.request.get("/api/quotes")
    assert response.ok, response.status
    content = response.json()
    record = next(item for item in content["content"] if item["number"] == number)
    return record, content["totalElements"]


def history_row(page, number):
    return page.locator("#history-content tbody tr").filter(has_text=f"Nº {number:05}")


def show_history(page):
    page.locator("a[data-page='history']").click()
    expect(page.locator("#history-content table")).to_be_visible()


def open_share(trigger, page):
    trigger.click()
    dialog = page.locator("#quote-share-dialog")
    expect(dialog).to_be_visible()
    return dialog


def close_share(page):
    page.locator("#quote-share-close").click()
    expect(page.locator("#quote-share-dialog")).not_to_be_visible()


def assert_responsive(page, dialog):
    assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth")
    box = dialog.bounding_box()
    assert box and box["x"] >= 0 and box["x"] + box["width"] <= page.viewport_size["width"] + 1, box


def native_sharing(page, variant_id):
    quote = issue_quote(page, variant_id, CLIENT, PHONE, check_preview=True)
    number = quote["number"]
    assert Decimal(quote["total"]) == Decimal("25.00")
    before = history_record(page, number)
    trigger = page.locator("#quote-success").get_by_role("button", name=SHARE_LABEL, exact=True)
    pdf_url = f"**/api/quotes/{number}/pdf"
    held_requests = []

    def hold_pdf(route):
        held_requests.append(route)

    page.route(pdf_url, hold_pdf)
    with page.expect_request(pdf_url):
        dialog = open_share(trigger, page)
    native = page.locator("#quote-share-native")
    message = page.locator("#quote-share-message")
    expect(native).to_be_disabled()
    expect(page.locator("#quote-share-status")).to_be_visible()
    assert page.evaluate("shareFixture.calls.length") == 0
    assert len(held_requests) == 1
    with page.expect_response(pdf_url) as pending:
        held_requests.pop().continue_()
    page.unroute(pdf_url, hold_pdf)
    response = pending.value
    assert response.ok and response.headers["content-type"].startswith("application/pdf")
    name = f"orcamento-urutau-{number:05}.pdf"
    assert name in response.headers["content-disposition"]
    expected_hash = sha256(response.body()).hexdigest()
    expect(native).to_be_enabled()
    expect(message).to_be_enabled()
    expect(message).to_have_value(MESSAGE.format(greeting=f"Olá, {CLIENT}! Tudo bem?"))
    message.fill("Mensagem editada para o menu nativo. 🐟")
    assert_responsive(page, dialog)
    native.tap()
    page.evaluate("() => shareFixture.lastShare")
    native_call = page.evaluate("shareFixture.calls[0]")
    assert native_call == {
        "name": name,
        "type": "application/pdf",
        "text": "Mensagem editada para o menu nativo. 🐟",
        "title": f"Orçamento nº {number:05}",
        "hasUrl": False,
        "activation": True,
        "sha256": expected_hash,
    }
    if dialog.is_visible():
        close_share(page)
    assert history_record(page, number) == before

    page.goto("/#meus-orcamentos")
    row = history_row(page, number)
    pdf_failure = lambda route: route.fulfill(status=503, content_type="application/json", body="{}")
    page.route(pdf_url, pdf_failure)
    open_share(row.get_by_role("button", name=SHARE_LABEL, exact=True), page)
    retry = page.locator("#quote-share-retry")
    expect(retry).to_be_visible()
    expect(page.locator("#quote-share-status")).to_contain_text("PDF")
    assert page.evaluate("shareFixture.calls.length") == 1
    page.unroute(pdf_url, pdf_failure)
    with page.expect_response(pdf_url):
        retry.tap()
    page.evaluate("shareFixture.mode = 'cancel'")
    native.tap()
    expect(dialog).to_be_visible()
    expect(page.locator("#quote-share-status")).to_contain_text("Compartilhamento cancelado")
    assert page.evaluate("shareFixture.calls.length") == 2
    page.evaluate("shareFixture.mode = 'reject'")
    native.tap()
    expect(page.locator("#quote-share-fallback")).to_be_visible()
    expect(page.locator("#quote-share-status")).to_contain_text("Não foi possível abrir")
    assert page.evaluate("shareFixture.calls.length") == 3
    close_share(page)
    assert history_record(page, number) == before
    return quote


def assert_whatsapp_link(link, phone, message):
    url = urlsplit(link.get_attribute("href"))
    assert (url.scheme, url.netloc, url.path) == ("https", "wa.me", f"/{phone}")
    assert parse_qs(url.query) == {"text": [message]}


def fallback_sharing(page, variant_id, first_quote):
    no_phone = issue_quote(page, variant_id, "Cliente QA sem telefone")
    first_number = first_quote["number"]
    assert no_phone["number"] == first_number + 1
    show_history(page)
    first_row = history_row(page, first_number)
    expect(first_row.get_by_role("link", name=re.compile("Baixar PDF"))).to_be_visible()
    first_row.get_by_text("Pendente", exact=True).wait_for()
    first_row.get_by_role("link", name=re.compile("Visualizar PDF")).click()
    viewer = page.locator("#issued-pdf-dialog")
    expect(viewer).to_be_visible()
    expect(page.locator("#issued-pdf-title")).to_contain_text(f"{first_number:05}")
    expect(page.locator("#issued-pdf-open")).to_have_attribute("href", f"/api/quotes/{first_number}/pdf")
    expect(page.locator("#issued-pdf-download")).to_have_attribute("href", f"/api/quotes/{first_number}/pdf?download=true")
    assert_responsive(page, viewer)

    dialog = open_share(page.locator("#issued-pdf-share"), page)
    message = page.locator("#quote-share-message")
    link = page.locator("#quote-share-whatsapp")
    expect(link).to_be_visible()
    expect(page.locator("#quote-share-native")).not_to_be_visible()
    expect(page.locator("#quote-share-fallback")).to_contain_text(re.compile(r"anex[ae].*manualmente", re.I))
    default_message = MESSAGE.format(greeting=f"Olá, {CLIENT}! Tudo bem?")
    expect(message).to_have_value(default_message)
    assert_whatsapp_link(link, "5511987654321", default_message)
    edited = "Olá! Atualização do orçamento & condições.\nObrigado!"
    message.fill(edited)
    page.locator("#quote-share-copy").click()
    assert page.evaluate("shareFixture.clipboard") == [edited]
    assert_whatsapp_link(link, "5511987654321", edited)
    assert "noopener" in link.get_attribute("rel").split()
    whatsapp_url = link.get_attribute("href")
    whatsapp_match = lambda url: url == whatsapp_url
    whatsapp_stub = lambda route: route.fulfill(status=200, content_type="text/plain", body="WhatsApp de teste")
    page.context.route(whatsapp_match, whatsapp_stub)
    with page.context.expect_page() as pending:
        link.click()
    popup = pending.value
    popup.wait_for_url(whatsapp_url)
    popup.close()
    page.context.unroute(whatsapp_match, whatsapp_stub)
    download = page.locator("#quote-share-download")
    with page.expect_download() as pending:
        download.click()
    assert pending.value.suggested_filename == f"orcamento-urutau-{first_number:05}.pdf"
    assert pending.value.path().read_bytes().startswith(b"%PDF-")
    close_share(page)
    if viewer.is_visible():
        page.locator("#issued-pdf-close").click()

    second_number = no_phone["number"]
    detail_url = f"**/api/quotes/{second_number}"
    legacy_fields = {"clientName": ""}

    def unnamed_client(route):
        response = route.fetch()
        detail = response.json()
        assert not detail.get("clientPhone")
        route.fulfill(response=response, json={**detail, **legacy_fields})

    # Simula um registro legado sem nome sem variar a validação de emissão.
    page.route(detail_url, unnamed_client)
    open_share(history_row(page, second_number).get_by_role("button", name=SHARE_LABEL, exact=True), page)
    generic = MESSAGE.format(greeting="Olá! Tudo bem?")
    expect(message).to_have_value(generic)
    expect(link).to_be_visible()
    assert_whatsapp_link(link, "", generic)
    expect(page.locator("#quote-share-recipient")).to_contain_text(re.compile("escolh|selecion", re.I))
    assert_responsive(page, dialog)
    close_share(page)
    legacy_fields["clientPhone"] = "(11) 0000-0000"
    open_share(history_row(page, second_number).get_by_role("button", name=SHARE_LABEL, exact=True), page)
    expect(link).to_be_visible()
    assert_whatsapp_link(link, "", generic)
    close_share(page)
    assert page.evaluate("shareFixture.calls") == []

    # Uma alteração posterior no catálogo não altera orçamento nem PDF já emitidos.
    original, count = history_record(page, first_number)
    put_setting(page, "/api/catalog/prices", {
        "variants": [{"id": variant_id, "retailPrice": 19.5, "wholesalePrice": 18}],
    })
    assert history_record(page, first_number) == (original, count)
    for key in ("number", "quantity", "total", "status"):
        assert original[key] == first_quote[key], (key, original)
    assert count == 2 and original["status"] == "PENDENTE"


def assert_security(request, base_url, number, authenticated_page):
    anonymous = request.new_context(base_url=base_url)
    try:
        for path in (f"/api/quotes/{number}", f"/api/quotes/{number}/pdf?download=true"):
            response = anonymous.get(path, max_redirects=0)
            assert response.status == 401, (path, response.status)
            protected = authenticated_page.request.get(path)
            assert protected.ok and "no-store" in protected.headers["cache-control"]
    finally:
        anonymous.dispose()
    assert authenticated_page.request.post("/api/quotes", data={}).status == 403


def main():
    parser = ArgumentParser(description=__doc__)
    parser.add_argument("jar", type=Path, help="Caminho do JAR executável gerado pelo Maven")
    parser.add_argument("--channel", choices=["chrome", "msedge"], help="Use um navegador já instalado")
    args = parser.parse_args()
    with isolated_application(args.jar.resolve(strict=True)) as (url, username, password), sync_playwright() as playwright:
        browser = playwright.chromium.launch(channel=args.channel)
        mobile = browser.new_context(base_url=url, viewport={"width": 375, "height": 812}, is_mobile=True, has_touch=True)
        try:
            browser_fixture(mobile, url, "native")
            page = mobile.new_page()
            login(page, username, password)
            variant_id = variant_for_fixture(page)
            put_setting(page, "/api/catalog/prices", {
                "variants": [{"id": variant_id, "retailPrice": 12.5, "wholesalePrice": 10}],
            })
            quote = native_sharing(page, variant_id)
            assert_security(playwright.request, url, quote["number"], page)
            print("OK emissão, prévia protegida, PDF nativo por toque, erro e cancelamento, autenticação e CSRF", flush=True)
        finally:
            mobile.close()

        desktop = browser.new_context(base_url=url, viewport={"width": 1440, "height": 900}, accept_downloads=True)
        try:
            browser_fixture(desktop, url, "unsupported")
            page = desktop.new_page()
            login(page, username, password)
            fallback_sharing(page, variant_id, quote)
            print("OK histórico, visualizador, download, edição, cópia, telefone, registro legado e dados preservados", flush=True)
        finally:
            desktop.close()
        browser.close()


if __name__ == "__main__":
    main()
