"""Valida a edição por gramatura e os preços usados pelos dois geradores."""
import argparse
from pathlib import Path
import sys

from playwright.sync_api import expect, sync_playwright

sys.dont_write_bytecode = True
from test_bulk_colors import choose, color, find_product, isolated_application, login, quantity


def add_model(page, name):
    csrf = page.request.get("/csrf").json()
    response = page.request.post("/api/catalog", headers={csrf["headerName"]: csrf["token"]}, data={
        "name": name, "active": True,
        "prices": {"retailPrice": 27, "wholesalePrice": 17},
        "variants": [{"weight": grams, "color": code, "active": True}
                     for grams in (10, 14) for code in ("01", "03", "08")],
    })
    assert response.status == 201, response.text()
    return response.json()


def no_horizontal_scroll(locator):
    assert locator.evaluate("(node) => node.scrollWidth <= node.clientWidth + 1")


def exercise(page, name, output, mode):
    product = add_model(page, name)
    unrelated = find_product(page, "Urunaja")
    page.goto("/#precos")
    card = page.locator(f'.price-model-card[data-model="{product["id"]}"]')
    summary = card.locator("summary")
    expect(summary).to_contain_text("27,00")
    expect(summary).to_contain_text("17,00")
    assert card.get_attribute("open") is None
    summary.click()
    rows = card.locator(".price-weight-list .price-editor")
    expect(rows).to_have_count(2)
    expect(rows.locator("input")).to_have_count(4)
    expect(rows.get_by_role("heading")).to_have_text(["10 g", "14 g"])
    no_horizontal_scroll(page.locator("html"))
    if output:
        card.screenshot(path=output / f"{mode}-precos.png")

    row = rows.first
    row.get_by_role("textbox", name="Consumidor final", exact=False).fill("30,50")
    row.get_by_role("button", name="Salvar preços", exact=False).click()
    dialog = page.locator("#price-confirm")
    expect(dialog).to_contain_text("3 combinações")
    expect(dialog).to_contain_text("27,00")
    expect(dialog).to_contain_text("30,50")
    expect(dialog).not_to_contain_text("14 g")
    no_horizontal_scroll(dialog)
    page.locator("#price-cancel").click()
    expect(dialog).not_to_be_visible()
    assert find_product(page, name)["weightPrices"][0]["retailValues"] == [27]
    expect(row.get_by_role("textbox", name="Consumidor final", exact=False)).to_have_value("30,50")
    row.get_by_role("button", name="Salvar preços", exact=False).click()
    page.locator("#price-accept").click()
    expect(dialog).not_to_be_visible()
    revised = find_product(page, name)
    assert [row["retailValues"] for row in revised["weightPrices"]] == [[30.5], [27]]
    assert {item["retailPrice"] for item in revised["variants"] if item["weight"] == 10} == {30.5}
    assert revised["defaultRetailPrice"] == 27

    bulk = card.locator(".price-editor-bulk")
    bulk.get_by_role("textbox", name="Consumidor final", exact=False).fill("32,50")
    bulk.get_by_role("textbox", name="Lojista", exact=False).fill("18,00")
    bulk.get_by_role("button", name="Aplicar a todas as gramaturas").click()
    expect(dialog).to_contain_text("6 combinações")
    expect(dialog).to_contain_text("10 g")
    expect(dialog).to_contain_text("14 g")
    if output:
        dialog.screenshot(path=output / f"{mode}-confirmacao.png")
    page.locator("#price-accept").click()
    expect(summary).to_contain_text("32,50")
    expect(summary).to_contain_text("18,00")
    assert find_product(page, "Urunaja") == unrelated

    page.goto("/#novo-orcamento")
    choose(page, name, 10)
    for code, units in (("01", "2"), ("08", "3")):
        color(page, code).check()
        quantity(page, code).fill(units)
    with page.expect_response(lambda response: response.url.endswith("/api/quotes/preview")
                              and response.request.method == "POST") as request:
        page.locator("#item-add").click()
    estimate = request.value.json()
    assert estimate["quantity"] == 5 and float(estimate["total"]) == 162.50
    assert {float(item["unitPrice"]) for item in estimate["items"]} == {32.50}

    page.goto("/#novo-orcamento-ia")
    page.locator("#chat-message").fill(f"• {name} | 14g | Cor 03 | Qtd: 30\n*Total: 30 unidades*")
    page.locator("#chat-send").click()
    expect(page.locator("#ai-review")).to_be_enabled()
    page.locator("#ai-review").click()
    expect(page.locator("#summary-total")).to_contain_text("540,00")
    no_horizontal_scroll(page.locator("html"))


def main():
    cli = argparse.ArgumentParser()
    cli.add_argument("jar", type=Path)
    cli.add_argument("--engine", choices=("chromium", "webkit"), default="chromium")
    cli.add_argument("--channel")
    cli.add_argument("--output", type=Path)
    args = cli.parse_args()
    if args.output:
        args.output.mkdir(parents=True, exist_ok=True)

    with isolated_application(args.jar.resolve(strict=True)) as (url, username, password), sync_playwright() as runtime:
        with getattr(runtime, args.engine).launch(channel=args.channel) as browser:
            for width, height, name, layout in ((320, 720, "Preço Fictício Móvel", "mobile"),
                                               (1440, 900, "Preço Fictício Desktop", "desktop")):
                context = browser.new_context(base_url=url, viewport={"width": width, "height": height},
                    is_mobile=width == 320, has_touch=width == 320, service_workers="block")
                try:
                    page = context.new_page()
                    login(page, username, password)
                    exercise(page, name, args.output, args.engine + "-" + layout)
                    print(f"{args.engine} {width}x{height}: edição, confirmação, lote e cálculos manual e IA validados.", flush=True)
                finally:
                    context.close()


if __name__ == "__main__":
    main()
