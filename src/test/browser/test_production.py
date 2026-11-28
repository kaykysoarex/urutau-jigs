"""Exercita a fila operacional em dois contextos autenticados e gera capturas."""
from argparse import ArgumentParser
from pathlib import Path
import sys

from playwright.sync_api import expect, sync_playwright

sys.dont_write_bytecode = True
from test_bulk_colors import isolated_application, login
from test_orders import issue
from test_quote_drafts import fail_next_request


def post(page, path, body):
    csrf = page.request.get("/csrf").json()
    response = page.request.post(path, headers={csrf["headerName"]: csrf["token"]}, data=body)
    assert response.ok, (response.status, response.text())
    return response.json()


def send(page, text):
    page.locator("#assistant-message").fill(text)
    with page.expect_response("**/api/assistant/messages") as response:
        page.locator("#assistant-send").click()
    assert response.value.ok


def prefix_date(page):
    return page.evaluate("""() => {
        const parts = new Intl.DateTimeFormat("en", {
          timeZone: "America/Sao_Paulo", year: "numeric", month: "2-digit", day: "2-digit"
        }).formatToParts(new Date());
        const values = Object.fromEntries(parts.map(({type, value}) => [type, value]));
        return [values.year, values.month, values.day].join("-");
    }""")


def exercise(page, other, user, password, width, output):
    login(page, user, password)
    login(other, user, password)
    first_name, second_name = f"Cliente Oficina {width}", f"Cliente Prazo {width}"
    first = issue(page, first_name)["number"]
    second = issue(page, second_name)["number"]
    issue(page, f"Orçamento pendente {width}")
    page.goto("/#pedidos")
    card = page.locator("#orders-list .order-card").filter(has_text=first_name)
    card.get_by_role("button", name="Confirmar pedido").click()
    page.locator("#order-expected").fill(prefix_date(page))
    page.locator("#order-notes").fill("Separar por cor")
    page.locator("#order-submit").click()
    expect(page.locator("#order-dialog")).to_contain_text("Em produção")
    page.locator("#order-close").click()

    page.goto("/#producao")
    expect(page.locator("#production-orders")).to_have_text("1")
    expect(page.locator("#production-first")).to_contain_text(first_name)
    expect(page.locator("#production-dueToday")).to_have_text("1")
    confirm = post(other, f"/api/orders/{second}/confirm", {"expectedOn": "2020-01-02"})
    second_number = confirm["commercial"]["orderNumber"]
    page.locator("#production-refresh").click()
    expect(page.locator("#production-orders")).to_have_text("2")
    expect(page.locator("#production-units")).to_have_text("10")
    expect(page.locator("#production-overdue")).to_have_text("1")
    assert page.evaluate("document.documentElement.scrollWidth <= innerWidth + 1")

    cards = page.locator("#production-list .production-card")
    expect(cards.first).to_contain_text(first_name)
    filter_field = page.locator("#production-deadline")
    filter_field.select_option("ATRASADO")
    expect(cards).to_have_count(1)
    expect(cards.first).to_contain_text("2º na fila")
    expect(cards.first).to_contain_text(second_name)
    filter_field.select_option("")
    search = page.locator("#production-search")
    search.fill(f"{second_number:05}")
    expect(cards).to_have_count(1)
    expect(cards.first).to_contain_text(second_name)
    search.fill("Spinner")
    expect(cards).to_have_count(2)
    search.fill("combinação inexistente")
    expect(page.locator("#production-feedback")).to_contain_text("Nenhum pedido corresponde")
    search.fill("")
    expect(cards).to_have_count(2)

    fail_next_request(page, "GET", "/api/production")
    page.locator("#production-refresh").click()
    expect(page.locator("#production-feedback")).to_contain_text("Fila indisponível")
    expect(cards).to_have_count(0)
    expect(page.locator("#production-orders")).to_have_text("—")
    page.locator("#production-refresh").click()
    expect(cards).to_have_count(2)
    if output:
        page.evaluate("window.scrollTo(0, 0)")
        page.screenshot(path=output / f"fila-{width}.png", full_page=True, animations="disabled")

    cards.first.get_by_role("button", name="Ver detalhes da produção").click()
    modal = page.locator("#production-dialog")
    expect(modal).to_contain_text("Lista de fabricação")
    expect(modal).to_contain_text("Spinner")
    expect(modal).to_contain_text("12 g")
    expect(modal).to_contain_text("Cor 03")
    expect(modal).to_contain_text("5 unidades")
    expect(modal).to_contain_text("Separar por cor")
    assert modal.evaluate("dialog => dialog.scrollWidth <= dialog.clientWidth + 1")
    if output:
        modal.screenshot(path=output / f"detalhes-{width}.png", animations="disabled")

    modal.get_by_role("button", name="Abrir em Pedidos").click()
    expect(page.locator("#order-dialog")).to_contain_text(first_name)
    expect(page.locator('[data-page="orders"]')).to_have_attribute("aria-current", "page")
    page.locator("#order-close").click()
    page.locator("#assistant-launch").click()
    send(page, "Abra a fila de produção.")
    page.locator("#assistant-log").get_by_role("button", name="Abrir Produção", exact=True).click()
    expect(page.locator('[data-page="production"]')).to_have_attribute("aria-current", "page")
    page.locator("#assistant-launch").click()
    send(page, "Qual é o primeiro pedido da fila?")
    page.locator("#assistant-log").get_by_role("button", name="Ver detalhes da produção").click()
    expect(modal).to_contain_text(first_name)
    page.locator("#production-close").click()

    post(other, f"/api/orders/{first}/deliver", {})
    page.evaluate("() => document.dispatchEvent(new Event('visibilitychange'))")
    expect(page.locator("#production-orders")).to_have_text("1")
    expect(cards.first).to_contain_text("1º na fila")
    expect(cards.first).to_contain_text(second_name)
    post(other, f"/api/orders/{second}/cancel", {"reason": "Teste isolado"})
    page.locator("#production-refresh").click()
    expect(page.locator("#production-feedback")).to_contain_text("A fila está vazia")
    expect(page.locator("#production-orders")).to_have_text("0")
    assert other.request.get(f"/api/orders/{first}").json()["commercial"]["fulfillment"] == "ENTREGUE"

    if width == 1440:
        for index in range(1, 14):
            quote = issue(other, f"Paginação Operacional {index:02d}")["number"]
            post(other, f"/api/orders/{quote}/confirm", {})
        page.locator("#production-refresh").click()
        expect(cards).to_have_count(12)
        page.get_by_role("button", name="Próxima página da produção").click()
        expect(cards).to_have_count(1)
        expect(cards.first).to_contain_text("13º na fila")
        expect(cards.first).to_contain_text("Paginação Operacional 13")
        search.fill("Paginação Operacional 13")
        expect(cards.first).to_contain_text("13º na fila")
        expect(page.locator("#production-pages")).to_be_hidden()


def main():
    parser = ArgumentParser(description=__doc__)
    parser.add_argument("jar", type=Path)
    parser.add_argument("--engine", choices=("chromium", "webkit"), default="chromium")
    parser.add_argument("--channel")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if args.output:
        args.output.mkdir(parents=True, exist_ok=True)
    with isolated_application(args.jar.resolve(strict=True)) as (url, user, password), sync_playwright() as runtime:
        with getattr(runtime, args.engine).launch(channel=args.channel) as browser:
            for width, height in [(320, 680), (390, 844), (844, 390), (1440, 900)]:
                mobile = width < 500 or height < 500
                with browser.new_context(base_url=url, viewport={"width": width, "height": height},
                                         is_mobile=mobile, has_touch=mobile) as primary, browser.new_context(base_url=url) as secondary:
                    if width == 844:
                        primary.add_init_script("Object.defineProperty(navigator, 'standalone', { value: true });")
                    exercise(primary.new_page(), secondary.new_page(), user, password, width,
                             args.output if width in (390, 1440) else None)
                    print(f"{args.engine} {width}x{height}: fila, filtros, detalhes e segundo dispositivo validados.", flush=True)


if __name__ == "__main__":
    main()
