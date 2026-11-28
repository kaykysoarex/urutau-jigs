"""Valida propostas, catálogo dinâmico e navegação da assistente contra o JAR."""
from argparse import ArgumentParser
from pathlib import Path
import sys

from playwright.sync_api import expect, sync_playwright

sys.dont_write_bytecode = True
from test_bulk_colors import choose, isolated_application, login


def send(page, message):
    page.locator("#assistant-message").fill(message)
    with page.expect_response("**/api/assistant/messages") as pending:
        page.locator("#assistant-send").click()
    assert pending.value.ok
    expect(page.locator("#assistant-message")).to_have_value("")


def decide(page, label):
    with page.expect_response("**/api/assistant/proposals/*/decision") as pending:
        page.locator("#assistant-log").get_by_role("button", name=label, exact=True).click()
    assert pending.value.ok


def catalog_preview(page):
    page.goto("/#catalogo")
    page.locator("#catalog-new").click()
    page.locator("#catalog-name").fill("Prévia Fictícia QA")
    page.locator("#catalog-retail").fill("29,00")
    page.locator("#catalog-wholesale").fill("18,00")
    page.locator("#catalog-weights").fill("10, 15")
    page.locator("#catalog-colors").fill("1, 03")
    page.locator("#add-combinations").click()
    expect(page.locator("#dialog-variants tbody tr")).to_have_count(4)
    page.get_by_role("button", name="Remover 15 g, cor 03").click()
    expect(page.locator("#dialog-variants tbody tr")).to_have_count(3)
    page.locator("#catalog-save").click()
    expect(page.locator("#catalog-dialog")).to_be_hidden()
    lookup = next(entry for entry in page.request.get("/api/catalog").json() if entry["name"] == "Prévia Fictícia QA")
    assert {(variant["weight"], variant["color"]) for variant in lookup["variants"]} == {(10, "01"), (10, "03"), (15, "01")}
    assert lookup["createdAt"] and lookup["updatedAt"]


def exercise(page, label, output):
    name = f"Isca QA {label}"
    page.locator("#assistant-launch").click()
    send(page, "Altere a quantidade mínima de lojista para 44 iscas.")
    expect(page.locator("#assistant-log")).to_contain_text("Novo mínimo: 44")
    decide(page, "Cancelar")
    expect(page.locator("#assistant-log")).to_contain_text("Proposta cancelada.")
    assert page.request.get("/api/settings").json()["wholesaleThreshold"] == 30

    send(page, f"Cadastre um novo modelo chamado {name}, com gramaturas de 10g e 20g, "
               "cores 01 até 03, preço de lojista R$ 19,00 e consumidor final R$ 30,00.")
    expect(page.locator("#assistant-log")).to_contain_text("6 combinações novas")
    assert all(model["name"] != name for model in page.request.get("/api/catalog").json())
    if output:
        page.locator("#assistant-panel").screenshot(path=output / f"proposta-{label}.png")
    decide(page, "Confirmar")
    expect(page.locator("#assistant-log")).to_contain_text("Modelo cadastrado com 6 combinações.")
    expect(page.locator("#assistant-log").get_by_role("button", name="Confirmar", exact=True)).to_have_count(0)
    page.locator("#assistant-log").get_by_role("button", name="Abrir modelo").click()
    expect(page.locator("#catalog-dialog")).to_be_visible()
    expect(page.locator("#catalog-name")).to_have_value(name)
    page.locator("#catalog-close").click()
    expect(page.locator(".catalog-card").filter(has_text=name)).to_contain_text("19,00")
    if output:
        page.screenshot(path=output / f"catalogo-{label}.png", full_page=True)

    page.goto("/#novo-orcamento")
    choose(page, name, 20)
    expect(page.locator("#item-colors")).to_contain_text("03")
    page.goto("/#novo-orcamento-ia")
    expect(page.locator("#chat-send")).to_be_enabled()
    page.locator("#chat-message").fill(f"• {name} | 20g | Cor 03 | Qtd: 30")
    page.locator("#chat-send").click()
    expect(page.locator("#ai-items")).to_contain_text("570,00")
    page.locator("#ai-review").click()
    expect(page.locator("#summary-total")).to_contain_text("570,00")
    page.locator("#assistant-launch").click()
    expect(page.locator("#assistant-log")).to_contain_text("Modelo cadastrado")
    assert page.locator("#assistant-panel").evaluate("(root) => root.scrollWidth <= root.clientWidth + 1")
    if label == 1440:
        page.locator("#assistant-close").click()
        page.locator("#client-name").fill("Documento Fictício QA")
        with page.expect_response(lambda response: response.url.endswith("/api/quotes")
                                  and response.request.method == "POST") as issuing:
            page.locator("#quote-issue").click()
        assert issuing.value.status == 201
        number = issuing.value.json()["number"]
        page.locator("#quote-success-close").click()
        page.locator("#assistant-launch").click()
        send(page, f"Abra o orçamento número {number}.")
        pdf_link = page.locator("#assistant-log").get_by_role("button", name="Visualizar PDF", exact=True)
        with page.expect_response(f"**/api/quotes/{number}/pdf") as pdf:
            pdf_link.click()
        assert pdf.value.ok and pdf.value.body().startswith(b"%PDF")
        expect(page.locator("#issued-pdf-dialog")).to_be_visible()


def main():
    cli = ArgumentParser(description=__doc__)
    cli.add_argument("jar", type=Path)
    cli.add_argument("--engine", choices=("chromium", "webkit"), default="chromium")
    cli.add_argument("--channel")
    cli.add_argument("--output", type=Path)
    args = cli.parse_args()
    if args.output:
        args.output.mkdir(parents=True, exist_ok=True)
    with isolated_application(args.jar.resolve(strict=True)) as (url, user, password), sync_playwright() as runtime:
        with getattr(runtime, args.engine).launch(channel=args.channel) as browser:
            for width, height in [(320, 680), (390, 844), (1440, 900)]:
                with browser.new_context(base_url=url, viewport={"width": width, "height": height},
                                         is_mobile=width < 500, has_touch=width < 500) as context:
                    page = context.new_page()
                    login(page, user, password)
                    if width == 1440:
                        catalog_preview(page)
                    exercise(page, width, args.output if width in (390, 1440) else None)
                    print(f"{args.engine} {width}x{height}: cadastro, proposta, catálogo e orçamento validados.", flush=True)


if __name__ == "__main__":
    main()
