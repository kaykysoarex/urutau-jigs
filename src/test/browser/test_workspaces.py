
"""Exercita os dois geradores e a assistência de sessão em um JAR isolado."""
from argparse import ArgumentParser
from pathlib import Path
import sys

from playwright.sync_api import expect, sync_playwright

sys.dont_write_bytecode = True
from test_bulk_colors import choose, color, isolated_application, login, quantity
from test_quote_drafts import fail_next_request, saved


def visit(page, route):
    page.goto("/#" + route)


def capture_page(page, path):
    page.evaluate("window.scrollTo(0, 0)")
    page.wait_for_function("window.scrollY === 0")
    page.screenshot(path=path, full_page=True)


def accept_controlled_provider_reply(page):
    spinner = next(product for product in page.request.get("/api/catalog").json() if product["name"] == "Spinner")
    variant = next(item for item in spinner["variants"] if item["weight"] == 12 and item["color"] == "03")
    item = {"variantId": variant["id"], "model": "Spinner", "weight": 12, "color": "03", "quantity": 5}
    page.evaluate("""item => {
      const fetchOriginal = window.fetch;
      window.fetch = function(input, options) {
        const url = new URL(input instanceof Request ? input.url : input, location.href);
        if (options?.method === "POST" && url.pathname.startsWith("/api/ai/conversations/")
            && url.pathname.endsWith("/messages")) {
          window.fetch = fetchOriginal;
          return Promise.resolve(Response.json({ id: url.pathname.split("/").at(-2),
            items: [item], questions: [], needsClarification: false }));
        }
        return fetchOriginal.call(this, input, options);
      };
    }""", item)
    page.locator("#chat-message").fill("Gostaria de cinco iscas desse modelo na cor combinada.")
    page.locator("#chat-send").click()
    expect(page.locator("#ai-review")).to_be_enabled()
    expect(page.locator("#ai-items")).to_contain_text("Spinner")
    page.once("dialog", lambda dialog: dialog.accept())
    page.locator("#ai-new").click()
    expect(page.locator("#ai-review")).to_be_disabled()
    expect(page.locator("#chat-message")).to_have_value("")


def exercise(page, user, password, output, label):
    name = f"Manual QA {label}"
    page.locator("#client-name").fill(name)
    assert page.locator("#page-manual #chat-form").count() == 0
    assert page.locator("#client-city, #client-state").count() == 0
    choose(page, "Spinner", 12)
    color(page, "03").check()
    page.locator("#item-add").click()
    saved(page)
    if output:
        capture_page(page, output / f"manual-{label}.png")

    visit(page, "novo-orcamento-ia")
    expect(page.locator("#ai-title")).to_be_visible()
    expect(page.locator("#client-name")).to_have_value("")
    page.locator("#client-name").fill(f"IA QA {label}")
    chat = page.locator("#chat-message")
    chat.fill("Quero cinco Spinner de 12 gramas na cor 03, por favor.")
    page.locator("#chat-send").click()
    expect(page.locator("#chat-log .error")).to_be_visible()
    accept_controlled_provider_reply(page)
    chat.fill("• Spinner | 12g | Cor 03 | Qtd: 5\n\n*Total: 5 unidades*")
    page.locator("#chat-send").click()
    expect(page.locator("#ai-review")).to_be_enabled()
    page.locator("#ai-review").click()
    expect(page.locator("#summary-quantity")).to_have_text("5 unidades")
    page.locator("#manual-items").get_by_role("button", name="Editar", exact=False).click()
    quantity(page, "03").fill("7")
    page.locator("#item-add").click()
    expect(page.locator("#summary-quantity")).to_have_text("7 unidades")
    saved(page)
    if output:
        capture_page(page, output / f"orcamento-ia-{label}.png")

    visit(page, "novo-orcamento")
    expect(page.locator("#client-name")).to_have_value(name)
    expect(page.locator("#summary-quantity")).to_have_text("1 unidade")
    visit(page, "novo-orcamento-ia")
    expect(page.locator("#summary-quantity")).to_have_text("7 unidades")
    page.locator("#quote-issue").click()
    expect(page.locator("#quote-success")).to_be_visible()
    assert page.evaluate("location.hash") == "#novo-orcamento-ia"
    page.locator("#quote-success-close").click()

    visit(page, "novo-orcamento")
    expect(page.locator("#client-name")).to_have_value(name)
    page.once("dialog", lambda dialog: dialog.accept())
    page.locator("#draft-transfer").click()
    page.wait_for_url("**/#novo-orcamento-ia")
    expect(page.locator("#client-name")).to_have_value(name)
    history = [draft for draft in page.request.get("/api/quote-drafts").json() if draft["clientName"] == name]
    assert len(history) == 1 and history[0]["source"] == "IA"

    launch = page.locator("#assistant-launch")
    launch.click()
    modal = page.locator("#assistant-panel")
    expect(modal).to_be_visible()
    expect(page.locator("#assistant-context")).to_have_text("Novo orçamento com IA")
    fail_next_request(page, "POST", "/api/assistant/messages")
    page.locator("#assistant-message").fill("Qual é a quantidade mínima para lojista?")
    page.locator("#assistant-send").click()
    expect(page.locator("#assistant-feedback")).to_contain_text("Falha de teste")
    page.locator("#assistant-send").click()
    expect(modal).to_contain_text("30 unidades")
    if output:
        modal.screenshot(path=output / f"assistente-{label}.png")
    page.locator("#assistant-minimize").click()
    visit(page, "pedidos")
    launch.click()
    expect(page.locator("#assistant-context")).to_have_text("Pedidos")
    expect(modal).to_contain_text("30 unidades")
    page.locator("#assistant-message").fill("Cancele todos os pedidos")
    page.locator("#assistant-send").click()
    expect(modal).to_contain_text("ação do administrador")
    page.locator("#assistant-new").click()
    expect(modal).not_to_contain_text("30 unidades")
    page.locator("#assistant-message").fill("Como emitir um orçamento?")
    page.locator("#assistant-send").click()
    expect(modal).to_contain_text("Informe o nome do cliente")
    page.locator("#assistant-close").click()
    page.locator("#logout-form").evaluate("form => form.requestSubmit()")
    page.wait_for_url("**/login.html?saiu")
    assert page.locator("#assistant-launch").count() == 0
    login(page, user, password)
    page.locator("#assistant-launch").click()
    expect(page.locator("#assistant-feedback")).to_be_empty()
    expect(page.locator("#assistant-log")).not_to_contain_text("Informe o nome do cliente")
    page.locator("#assistant-close").click()
    assert page.evaluate("document.documentElement.scrollWidth <= innerWidth + 1")


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
                with browser.new_context(base_url=url, viewport={"width": width, "height": height},
                                         is_mobile=width < 500 or height < 500, has_touch=width < 500 or height < 500) as context:
                    if width == 320 or height == 390:
                        context.add_init_script('Object.defineProperty(navigator, "standalone", { value: true });')
                    page = context.new_page()
                    page.goto("/")
                    expect(page.locator("#assistant-launch")).to_have_count(0)
                    login(page, user, password)
                    exercise(page, user, password, args.output if width in (390, 1440) else None, width)
                    print(f"{args.engine} {width}x{height}: geradores, rascunhos independentes e assistente validados.", flush=True)


if __name__ == "__main__":
    main()
