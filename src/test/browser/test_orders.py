
"""Valida pedidos comerciais pelo navegador e registra capturas com dados fictícios."""

from argparse import ArgumentParser
from pathlib import Path
import sys
from uuid import uuid4

from playwright.sync_api import expect, sync_playwright

sys.dont_write_bytecode = True
from test_bulk_colors import isolated_application, login


def issue(page, name):
    session = page.request.get("/api/session").json()
    headers = {session["csrfHeader"]: session["csrfToken"]}
    catalog = page.request.get("/api/catalog").json()
    spinner = next(model for model in catalog if model["name"] == "Spinner")
    variant = next(variant for variant in spinner["variants"] if variant["color"] == "03" and variant["weight"] == 12)
    items = [{"variantId": variant["id"], "quantity": 5}]
    preview = page.request.post("/api/quotes/preview", data={"items": items}, headers=headers).json()
    response = page.request.post("/api/quotes", headers=headers, data={
        "client": {"name": name, "city": "Goiânia", "state": "GO"}, "items": items,
        "requestId": str(uuid4()), "reviewToken": preview["reviewToken"]
    })
    assert response.status == 201
    return response.json()


def assert_layout(page):
    assert page.evaluate("document.documentElement.scrollWidth <= innerWidth + 1")
    if page.locator("#order-dialog").is_visible():
        assert page.locator("#order-dialog").evaluate(
            "dialog => dialog.scrollWidth <= dialog.clientWidth + 1 && dialog.getBoundingClientRect().width <= innerWidth")


def exercise(page, user, password, label, output):
    login(page, user, password)
    prefix = f"Cliente fictício {label}"
    quote = issue(page, f"{prefix} Silva")
    issue(page, f"{prefix} Costa")
    page.goto("/#pedidos")
    page.locator("#order-search").fill(prefix)
    expect(page.locator(".order-card")).to_have_count(2)
    page.locator("#order-sort").select_option("false")
    expect(page.locator(".order-card").first).to_contain_text("Silva")
    page.locator("#order-status").select_option("PENDENTE")
    first = page.locator(".order-card").filter(has_text=f"{prefix} Silva")
    expect(first).to_contain_text(f'{quote["number"]:05}')
    assert_layout(page)
    if output:
        page.screenshot(path=str(output / "pedidos-mobile.png"), full_page=True)
    first.get_by_role("button", name="Confirmar pedido").click()
    dialog = page.locator("#order-dialog")
    expect(dialog).to_be_visible()
    expect(page.locator("#order-dialog-title")).to_have_text("Confirmar pedido")
    page.locator("#order-expected").fill("2030-01-15")
    page.locator("#order-notes").fill("Separar em caixas")
    assert_layout(page)
    if output:
        page.screenshot(path=str(output / "confirmacao-mobile.png"))
    dialog.locator("#order-submit").click()
    expect(dialog).to_contain_text("Detalhes do pedido")
    expect(dialog.get_by_text("Em produção", exact=True)).to_be_visible()
    expect(dialog).to_contain_text("R$ 130,00")
    expect(dialog).to_contain_text("Spinner · 12 g · Cor 03")
    assert_layout(page)
    if output:
        page.screenshot(path=str(output / "detalhes-mobile.png"))
    dialog.get_by_role("link", name="Visualizar PDF").click()
    expect(page.locator("#issued-pdf-dialog")).to_be_visible()
    page.locator("#issued-pdf-close").click()
    expect(dialog).to_be_visible()
    with page.expect_download() as download:
        dialog.get_by_role("link", name="Baixar PDF").click()
    assert download.value.suggested_filename == f'orcamento-urutau-{quote["number"]:05}.pdf'
    assert download.value.path().read_bytes().startswith(b"%PDF-")
    dialog.get_by_role("button", name="Compartilhar pelo WhatsApp").click()
    expect(page.locator("#quote-share-dialog")).to_be_visible()
    expect(page.locator("#quote-share-message")).to_have_value(
        f"Olá, {prefix} Silva! Tudo bem?\n\nSegue o orçamento solicitado das iscas artesanais URUTAU JIGS.\n\n"
        "Qualquer dúvida, estamos à disposição.\n\nObrigado pela preferência!\nURUTAU JIGS"
    )
    page.locator("#quote-share-close").click()
    dialog.get_by_role("button", name="Editar prazo e observações").click()
    page.locator("#order-notes").fill("Pedido separado")
    dialog.locator("#order-submit").click()
    expect(dialog).to_contain_text("Pedido separado")
    dialog.get_by_text("Histórico de operações").click()
    expect(dialog).to_contain_text("Separar em caixas")
    dialog.get_by_role("button", name="Marcar como entregue").click()
    expect(page.locator("#order-dialog-title")).to_have_text("Marcar como entregue")
    dialog.locator("#order-submit").click()
    expect(dialog.get_by_text("Entregue", exact=True)).to_be_visible()
    expect(dialog.get_by_role("button", name="Marcar como entregue")).to_have_count(0)
    assert_layout(page)
    if output:
        page.screenshot(path=str(output / "entregue-mobile.png"))
    page.locator("#order-close").click()
    page.locator("#order-status").select_option("ENTREGUE")
    expect(first.get_by_text("Entregue", exact=True)).to_be_visible()
    first.get_by_role("button", name="Ver detalhes").click()
    dialog.get_by_role("button", name="Cancelar pedido").click()
    page.locator("#order-reason").fill("Cliente pediu o cancelamento")
    dialog.locator("#order-submit").click()
    expect(dialog).to_contain_text("Cliente pediu o cancelamento")
    page.locator("#order-close").click()
    page.locator("#order-status").select_option("CANCELADO")
    expect(page.locator(".order-card")).to_have_count(1)
    expect(page.locator(".order-card").first).to_contain_text("Cancelado")
    assert_layout(page)


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
            for width, height, mobile in [(320, 680, True), (390, 844, True), (1440, 900, False)]:
                with browser.new_context(base_url=url, viewport={"width": width, "height": height},
                                         is_mobile=mobile, has_touch=mobile, accept_downloads=True) as context:
                    if mobile:
                        context.add_init_script('Object.defineProperty(navigator, "standalone", { value: true });')
                    exercise(context.new_page(), user, password, width, args.output if width == 390 else None)
                    print(f"{args.engine} {width}x{height}: fluxo comercial, PDF e responsividade validados.", flush=True)


if __name__ == "__main__":
    main()
