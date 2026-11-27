
"""Valida cadastro, conversão comercial e resgate do cliente com a interface real."""

from argparse import ArgumentParser
from pathlib import Path
import sys

from playwright.sync_api import expect, sync_playwright

sys.dont_write_bytecode = True
from test_bulk_colors import choose, color, isolated_application, login
from test_orders import issue
from test_quote_drafts import saved


def route(page, path):
    target = "#" + path
    if page.evaluate("location.hash") == target:
        page.reload()
    else:
        page.goto("/" + target)


def assert_mobile(page, modal=None):
    assert page.evaluate("document.documentElement.scrollWidth <= innerWidth + 1")
    if modal:
        assert page.locator(modal).evaluate("dialog => dialog.scrollWidth <= dialog.clientWidth + 1")


def fill_customer(page, name, phone):
    page.locator("#customer-new").click()
    editor = page.locator("#customer-form")
    editor.get_by_label("Nome ou razão social").fill(name)
    editor.get_by_label("Estabelecimento").fill("Loja de pesca QA")
    editor.get_by_label("Telefone ou WhatsApp").fill(phone)
    editor.get_by_label("Cidade").fill("Curitiba")
    editor.get_by_label("Estado").select_option("PR")


def current_card(page, name):
    return page.locator("#customers-list .order-card").filter(has=page.get_by_role("heading", name=name, exact=True))


def create_and_start(page, name, output):
    route(page, "clientes")
    suffix = int(name.rsplit(" ", 1)[1])
    phone = f"(41) 99990-{suffix:04}"
    fill_customer(page, name, phone)
    assert_mobile(page, "#customer-dialog")
    if output:
        page.screenshot(path=output / "cadastro-mobile.png")
    page.locator("#customer-save").click()
    expect(page.locator("#customer-title")).to_have_text(name)
    expect(page.locator("#customer-dialog").get_by_role("link", name="Abrir WhatsApp")).to_have_attribute(
        "href", f"https://wa.me/554199990{suffix:04}")
    page.locator("#customer-close").click()
    assert_mobile(page)
    if output:
        page.screenshot(path=output / "clientes-mobile.png", full_page=True,
                        style="#toast { visibility: hidden !important; }")

    fill_customer(page, "Possível duplicado", f"0055 41 99990-{suffix:04}")
    page.locator("#customer-save").click()
    expect(page.locator("#customer-duplicates")).to_be_visible()
    page.once("dialog", lambda dialog: dialog.accept())
    page.locator("#customer-duplicates").get_by_role("button", name=name, exact=False).click()
    expect(page.locator("#customer-title")).to_have_text(name)
    page.locator("#customer-dialog").get_by_role("button", name="Novo orçamento").click()
    expect(page.locator("#client-name")).to_have_value(name)
    expect(page.locator("#client-phone")).to_have_value(phone)
    expect(page.locator("#quote-customer-link")).to_contain_text(name)
    saved(page)


def edit_and_recover(page, name, output):
    revised = name + " Atualizado"
    route(page, "clientes")
    page.locator("#customer-search").fill("Loja de pesca QA")
    current_card(page, name).get_by_role("button", name="Ver cadastro").click()
    page.get_by_role("button", name="Editar cliente").click()
    page.locator("#customer-form").get_by_label("Nome ou razão social").fill(revised)
    page.locator("#customer-save").click()
    expect(page.locator("#customer-title")).to_have_text(revised)
    page.locator("#customer-close").click()
    route(page, "novo-orcamento")
    page.locator("#draft-latest").click()
    expect(page.locator("#client-name")).to_have_value(name)
    expect(page.locator("#quote-customer-link")).to_contain_text("O cadastro foi atualizado")
    if output:
        page.screenshot(path=output / "vinculo-mobile.png", full_page=True)
    page.once("dialog", lambda dialog: dialog.accept())
    page.get_by_role("button", name="Usar dados atuais do cadastro").click()
    expect(page.locator("#client-name")).to_have_value(revised)

    choose(page, "Spinner", 12)
    color(page, "03").check()
    page.locator("#item-add").click()
    issue_button = page.locator("#quote-issue")
    expect(issue_button).to_be_enabled()
    issue_button.click()
    expect(page.locator("#quote-success")).to_be_visible()
    issued = int(page.locator("#quote-success-number").inner_text())
    page.locator("#quote-success-close").click()
    route(page, "pedidos")
    row = page.locator("#orders-list .order-card").filter(has_text=revised)
    row.get_by_role("button", name="Confirmar pedido").click()
    page.locator("#order-submit").click()
    expect(page.locator("#order-dialog-title")).to_have_text("Detalhes do pedido")
    expect(page.locator("#order-dialog").get_by_role("button", name="Ver cadastro do cliente")).to_be_visible()
    assert page.request.get(f"/api/orders/{issued}").json()["customerId"] is not None
    page.locator("#order-close").click()
    return revised


def link_legacy(page, revised, output):
    name = f"Orçamento Antigo QA {revised}"
    old = issue(page, name)
    route(page, "pedidos")
    page.locator("#orders-list .order-card").filter(has_text=name).get_by_role(
        "button", name="Ver detalhes").click()
    page.locator("#order-dialog").get_by_role("button", name="Vincular cliente").click()
    picker = page.locator("#order-customer-picker")
    picker.get_by_label("Buscar cliente por nome, estabelecimento ou telefone").fill(revised)
    picker.get_by_role("button", name=revised, exact=False).click()
    page.locator("#order-submit").click()
    expect(page.locator("#order-dialog").get_by_role("button", name="Ver cadastro do cliente")).to_be_visible()
    assert page.request.get(f"/api/orders/{old['number']}").json()["client"]["name"] == name
    page.locator("#order-close").click()
    route(page, "clientes")
    current_card(page, revised).get_by_role("button", name="Ver cadastro").click()
    dialog = page.locator("#customer-dialog")
    expect(dialog).to_contain_text("R$ 26,00")
    dialog.get_by_text("Histórico de orçamentos").click()
    expect(dialog).to_contain_text(name)
    assert_mobile(page, "#customer-dialog")
    if output:
        page.screenshot(path=output / "historico-mobile.png")
    page.once("dialog", lambda dialog: dialog.accept())
    dialog.get_by_role("button", name="Desativar cliente").click()
    expect(dialog.get_by_role("button", name="Reativar cliente")).to_be_visible()
    page.once("dialog", lambda dialog: dialog.accept())
    dialog.get_by_role("button", name="Reativar cliente").click()
    expect(dialog.get_by_role("button", name="Desativar cliente")).to_be_visible()


def register_during_issue(page, name):
    page.locator("#customer-close").click()
    route(page, "novo-orcamento")
    page.locator("#client-name").fill(name)
    page.locator("#quote-customer-details summary").click()
    page.locator("#quote-customer-picker").get_by_label("Cadastrar cliente ao concluir").check()
    choose(page, "Spinner", 12)
    color(page, "03").check()
    page.locator("#item-add").click()
    page.locator("#quote-issue").click()
    expect(page.locator("#quote-success")).to_be_visible()
    quote_number = int(page.locator("#quote-success-number").inner_text())
    page.locator("#quote-success-close").click()
    customer = page.request.get(f"/api/orders/{quote_number}").json()["customerId"]
    detail = page.request.get(f"/api/customers/{customer}").json()
    assert detail["customer"]["name"] == name and detail["customer"]["whatsapp"] is None
    assert detail["indicators"]["pending"] == 1


def check_pagination(page):
    session = page.request.get("/api/session").json()
    headers = {session["csrfHeader"]: session["csrfToken"]}
    for index in range(17):
        response = page.request.post("/api/customers", headers=headers,
                                     data={"name": f"Paginação QA {index:02}", "city": "Itajaí", "state": "SC"})
        assert response.ok
    route(page, "clientes")
    page.locator("#customer-search").fill("Itajaí")
    expect(page.locator("#customers-list .order-card").first).to_contain_text("Paginação QA")
    expect(page.locator("#customers-list .order-card")).to_have_count(16)
    page.locator("#customers-pages").get_by_role("button", name="Próxima página").click()
    expect(page.locator("#customers-list .order-card")).to_have_count(1)
    expect(page.locator("#customers-pages")).to_contain_text("Página 2 de 2")


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
            for width, height in [(320, 680), (390, 844), (1440, 900)]:
                name = f"Cliente Fictício {width}"
                output = args.output if width == 390 else None
                options = {"base_url": url, "viewport": {"width": width, "height": height},
                           "is_mobile": width < 500, "has_touch": width < 500}
                with browser.new_context(**options) as first:
                    first.add_init_script('Object.defineProperty(navigator, "standalone", {value: true});')
                    page = first.new_page()
                    login(page, user, password)
                    create_and_start(page, name, output)
                with browser.new_context(**options) as second:
                    page = second.new_page()
                    login(page, user, password)
                    revised = edit_and_recover(page, name, output)
                    link_legacy(page, revised, output)
                    register_during_issue(page, f"Cadastro na emissão {width}")
                    if width == 1440:
                        check_pagination(page)
                print(f"{args.engine} {width}x{height}: clientes, vínculo, rascunho, pedidos e histórico validados.", flush=True)


if __name__ == "__main__":
    main()
