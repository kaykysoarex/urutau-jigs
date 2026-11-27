"""Exercita a confirmação da emissão em telas pequenas e no desktop."""

from argparse import ArgumentParser
from pathlib import Path
import re
import sys

from playwright.sync_api import expect, sync_playwright

sys.dont_write_bytecode = True
from test_bulk_colors import choose, color, isolated_application, login, quantity, submit_and_check_preview
from test_whatsapp_share import browser_fixture, history_record, variant_for_fixture


ISSUE_URL = "**/api/quotes"
CLIENT = "Cliente de teste " + "NomeExtenso" * 7


def make_draft(page, units):
    variant_id = variant_for_fixture(page)
    choose(page, "Spinner", 12)
    color(page, "03").check()
    quantity(page, "03").fill(str(units))
    submit_and_check_preview(page, page.locator("#item-add").click, {variant_id: units})
    page.locator("#client-name").fill(CLIENT)
    page.locator("#client-phone").fill("+55 (11) 98765-4321")
    page.locator("#draft-notes").fill("Observação sobre o pedido")
    choose(page, "Spinner", 12)
    color(page, "03").check()
    page.locator("#item-quantity").fill("17")


def assert_clean_form(page, ai=False):
    for selector in ("#client-name", "#client-phone"):
        expect(page.locator(selector)).to_have_value("")
    expect(page.locator("#quote-customer-link")).to_be_hidden()
    if ai:
        expect(page.locator("#item-form, #draft-notes")).to_have_count(0)
        expect(page.locator("#ai-items tbody tr")).to_have_count(0)
    else:
        for selector in ("#draft-notes", "#item-model", "#item-weight"):
            expect(page.locator(selector)).to_have_value("")
        expect(page.locator("#item-quantity")).to_have_value("1")
        expect(page.locator("#item-form input[type=checkbox]:checked")).to_have_count(0)
        expect(page.locator("#manual-items tbody tr")).to_have_count(0)
    expect(page.locator("#summary-quantity")).to_have_text("0 unidades")
    expect(page.locator("#summary-tier")).to_have_text("A definir")
    expect(page.locator("#summary-total")).to_have_text(re.compile(r"R\$\s+0,00"))
    expect(page.locator("#quote-issue")).to_be_disabled()
    assert page.evaluate("draftUi.current === null && state.quoteAttempt === null")


def document_bottom(page):
    return page.evaluate("document.documentElement.scrollHeight - window.innerHeight")


def check_failure(page):
    cases = [
        ({"status": 500, "json": {"message": "Falha de teste"}}, "Falha de teste"),
        ({"status": 200, "content_type": "text/html", "body": "Sem confirmação"},
         "O servidor não confirmou a emissão. Tente novamente."),
        ({"status": 422, "json": {"message": "Validação de teste"}}, "Validação de teste"),
    ]
    for reply, message in cases:
        page.route(ISSUE_URL, lambda route: route.fulfill(**reply))
        try:
            page.locator("#quote-issue").click()
            expect(page.locator("#toast")).to_have_text(message)
            expect(page.locator("#quote-success")).not_to_be_visible()
            expect(page.locator("#quote-issue")).to_be_enabled()
            expect(page.locator("#client-name")).to_have_value(CLIENT)
            expect(page.locator("#summary-quantity")).to_have_text("30 unidades")
        finally:
            page.unroute(ISSUE_URL)


def confirm_issue(page, units, mobile):
    issue = page.locator("#quote-issue")
    confirmation = page.locator("#quote-success")
    requests = []
    hold = lambda route: requests.append(route)
    page.route(ISSUE_URL, hold)
    page.evaluate("""() => {
        window.scrollTo(0, document.documentElement.scrollHeight);
        document.getElementById("quote-issue").addEventListener("click", () => {
            window.issueScroll = scrollY;
            window.issueBottom = document.documentElement.scrollHeight - innerHeight;
        }, {capture: true, once: true});
    }""")
    with page.expect_request(lambda request: request.url.endswith("/api/quotes") and request.method == "POST"):
        issue.click()
    expect(issue).to_be_disabled()
    expect(issue).to_contain_text("Emitindo")
    expect(confirmation).not_to_be_visible()
    issue.evaluate("(button) => { button.click(); button.click(); }")
    assert len(requests) == 1
    with page.expect_response(lambda response: response.url.endswith("/api/quotes")
                              and response.request.method == "POST") as pending:
        requests[0].continue_()
    page.unroute(ISSUE_URL, hold)
    response = pending.value
    assert response.status == 201, response.status
    quote = response.json()

    expect(confirmation).to_be_visible()
    expect(page.locator("#quote-success-title")).to_be_focused()
    expect(page.locator("#quote-success-title")).to_have_text("Orçamento emitido!")
    expect(page.locator("#quote-success-message")).to_have_text(
        "Seu orçamento foi gerado com sucesso e está pronto para ser enviado.")
    expect(page.locator("#quote-success-number")).to_have_text(f'{quote["number"]:05}')
    expect(page.locator("#quote-success-client")).to_have_text(CLIENT)
    expect(page.locator("#quote-success-quantity")).to_have_text(f"{units} unidades")
    expect(page.locator("#quote-success-tier")).to_have_text("Lojista" if units >= 30 else "Consumidor final")
    expect(page.locator("#quote-success-total")).to_have_text(re.compile(r"R\$\s+" + ("480,00" if units >= 30 else "52,00")))
    assert confirmation.locator("button, a").all_text_contents() == [
        "Enviar pelo WhatsApp", "Visualizar PDF", "Baixar PDF", "Fechar"]
    saved = page.evaluate("window.issueScroll")
    if mobile:
        assert saved > 0 and abs(saved - page.evaluate("window.issueBottom")) <= 1
    assert abs(page.evaluate("scrollY") - min(saved, document_bottom(page))) <= 1
    assert_clean_form(page)
    assert page.request.get("/api/quote-drafts").json() == []
    assert confirmation.evaluate("""dialog => {
        const bounds = dialog.getBoundingClientRect();
        const view = window.visualViewport;
        const share = dialog.querySelector("#quote-success-share").getBoundingClientRect();
        const close = dialog.querySelector("#quote-success-close").getBoundingClientRect();
        return bounds.left >= 0 && bounds.top >= 0 && bounds.right <= view.width + 1
            && bounds.bottom <= view.height + 1 && dialog.scrollWidth <= dialog.clientWidth + 1
            && Math.abs(share.width - close.width) <= 1 && share.height >= 44 && close.height >= 44;
    }""")
    return quote, saved


def check_actions(page, quote):
    number = quote["number"]
    confirmation = page.locator("#quote-success")
    share = page.locator("#quote-success-share")
    share.click()
    message = page.locator("#quote-share-message")
    expect(message).to_be_enabled()
    expect(message).to_have_value(re.compile(re.escape(CLIENT)))
    message.fill("Texto revisado do orçamento.")
    expect(page.locator("#quote-share-fallback")).to_be_visible()
    assert page.evaluate("shareFixture.calls.length") == 0
    page.locator("#quote-share-close").click()
    expect(share).to_be_focused()
    expect(confirmation).to_be_visible()

    view = page.locator("#quote-success-view")
    with page.expect_response(f"**/api/quotes/{number}/pdf") as pending:
        view.click()
    assert pending.value.body().startswith(b"%PDF-")
    expect(page.locator("#issued-pdf-dialog")).to_be_visible()
    expect(page.locator("#issued-pdf-title")).to_contain_text(f"{number:05}")
    page.locator("#issued-pdf-close").click()
    expect(view).to_be_focused()

    with page.expect_download() as pending_download:
        page.locator("#quote-success-download").click()
    download = pending_download.value
    assert download.suggested_filename == f"orcamento-urutau-{number:05}.pdf"
    assert download.path().read_bytes().startswith(b"%PDF-")
    expect(confirmation).to_be_visible()


def recover_lost_confirmation(page, previous_count):
    issue = page.locator("#quote-issue")
    requests = []
    def lose_response(route):
        requests.append(route.request.post_data_json)
        response = route.fetch()
        assert response.status == 201
        route.abort()
    page.route(ISSUE_URL, lose_response, times=1)
    issue.click()
    expect(issue).to_contain_text("Tentar confirmar emissão")
    expect(page.locator("#quote-success")).to_be_hidden()
    expect(page.locator("#client-name")).to_have_value(CLIENT)
    expect(page.locator("#draft-notes")).to_have_value("Observação sobre o pedido")
    assert page.request.get("/api/quotes").json()["totalElements"] == previous_count + 1
    assert page.request.get("/api/quote-drafts").json() == []

    with page.expect_response(lambda response: response.url.endswith("/api/quotes")
                              and response.request.method == "POST") as pending:
        issue.click()
    response = pending.value
    assert response.status == 201
    assert response.request.post_data_json == requests[0]
    expect(page.locator("#quote-success")).to_be_visible()
    assert_clean_form(page)
    assert page.request.get("/api/quotes").json()["totalElements"] == previous_count + 1
    page.reload()
    assert_clean_form(page)
    expect(page.locator("#quote-success")).to_be_hidden()
    assert page.request.get("/api/quote-drafts").json() == []


def main():
    parser = ArgumentParser(description=__doc__)
    parser.add_argument("jar", type=Path)
    parser.add_argument("--engine", choices=("chromium", "webkit"), default="chromium")
    parser.add_argument("--channel", choices=("chrome", "msedge"))
    args = parser.parse_args()
    if args.channel and args.engine != "chromium":
        parser.error("--channel exige Chromium")
    with isolated_application(args.jar.resolve(strict=True)) as (url, user, password), sync_playwright() as runtime:
        with getattr(runtime, args.engine).launch(channel=args.channel) as browser:
            for width, height, mobile, standalone, units in (
                (320, 640, True, False, 30),
                (844, 390, True, True, 2),
                (1440, 900, False, False, 2),
            ):
                # Intercepta as respostas de emissão de forma consistente nos dois motores.
                with browser.new_context(base_url=url, viewport={"width": width, "height": height},
                                         is_mobile=mobile, has_touch=mobile, accept_downloads=True,
                                         service_workers="block") as context:
                    browser_fixture(context, url, "unsupported")
                    if standalone:
                        context.add_init_script('Object.defineProperty(navigator, "standalone", { value: true });')
                    page = context.new_page()
                    login(page, user, password)
                    make_draft(page, units)
                    previous_count = page.request.get("/api/quotes").json()["totalElements"]
                    if units == 30:
                        page.locator("#quote-preview-pdf").click()
                        expect(page.locator("#preview-pdf-dialog")).to_be_visible()
                        expect(page.locator("#client-name")).to_have_value(CLIENT)
                        expect(page.locator("#draft-notes")).to_have_value("Observação sobre o pedido")
                        expect(page.locator("#summary-quantity")).to_have_text("30 unidades")
                        page.locator("#preview-pdf-close").click()
                        check_failure(page)
                        assert page.request.get("/api/quotes").json()["totalElements"] == previous_count
                    quote, saved = confirm_issue(page, units, mobile)
                    original = history_record(page, quote["number"])
                    check_actions(page, quote)
                    if standalone:
                        page.keyboard.press("Escape")
                    else:
                        page.locator("#quote-success-close").click()
                    expect(page.locator("#quote-success")).not_to_be_visible()
                    expect(page.locator("#quote-summary-title")).to_be_focused()
                    expect(page.locator("#summary-quantity")).to_have_text("0 unidades")
                    assert abs(page.evaluate("scrollY") - min(saved, document_bottom(page))) <= 1
                    assert history_record(page, quote["number"]) == original
                    assert original[1] == previous_count + 1 and original[0]["status"] == "PENDENTE"
                    if units == 30:
                        make_draft(page, 2)
                        recover_lost_confirmation(page, previous_count + 1)
                    print(f"{args.engine} {width}x{height}, standalone={standalone}: confirmação e ações validadas", flush=True)


if __name__ == "__main__":
    main()
