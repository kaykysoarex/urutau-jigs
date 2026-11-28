"""Audita o layout em motores reais, com dados sintéticos em um H2 descartável."""

import argparse
import json
import re
import sys
from pathlib import Path

from playwright.sync_api import Error as PlaywrightError
from playwright.sync_api import expect, sync_playwright

sys.dont_write_bytecode = True
from test_bulk_colors import choose, color, isolated_application, login, quantity
from test_whatsapp_share import issue_quote, variant_for_fixture


WIDTHS = (320, 360, 375, 390, 393, 414, 430, 480, 768, 1024, 1280, 1440, 1920)
LANDSCAPES = ((568, 320), (844, 390), (1024, 600))
ROUTES = {
    "manual": "#item-model:not(:disabled)",
    "ai": "#chat-message:not(:disabled)",
    "history": "#history-content tbody tr",
    "catalog": "#catalog-content .catalog-card, #catalog-content tbody tr",
    "prices": "#price-content tbody tr input",
    "settings": "#settings-threshold:not(:disabled)",
}
SCREENSHOTS = {(320, 720), (390, 844), (844, 390), (1440, 900)}
LONG_MODEL = "ÉdicaoEspecialResponsividade" * 4
LONG_CLIENT = "João Teste de Mobilidade " + "SobrenomeExtraordinariamenteLongo" * 2
LONG_MESSAGE = ("Pedido para revisão\n" + "CaiaqueTestedeQuebra" * 16 + "\n🌊")
ORDER = f"• Spinner | 12g | Cor 03 | Qtd: 2\n• {LONG_MODEL} | 12g | Cor 01 | Qtd: 3"
SHARE = "Compartilhar pelo WhatsApp"


GEOMETRY = """
({selector, mobile, modal}) => {
    const scope = document.querySelector(selector);
    const html = document.documentElement;
    const viewport = window.visualViewport;
    const visible = node => {
        const styles = getComputedStyle(node);
        return node.getClientRects().length && styles.visibility !== 'hidden'
            && styles.display !== 'none' && !node.closest('[hidden]');
    };
    const name = node => node.id || node.getAttribute('aria-label')
        || node.getAttribute('name') || node.textContent.trim().slice(0, 64) || node.tagName;
    const issues = new Set();
    const right = viewport ? viewport.offsetLeft + viewport.width : html.clientWidth;
    const left = viewport?.offsetLeft || 0;
    const matches = (query) => [...scope.querySelectorAll(query)].filter(visible);
    const displayedRect = (node, rect) => {
        const result = {left: rect.left, right: rect.right, top: rect.top, bottom: rect.bottom};
        // Do not count the unseen part of a control scrolled behind a dialog header or footer.
        for (let parent = node.parentElement; parent; parent = parent.parentElement) {
            const styles = getComputedStyle(parent);
            const clipX = /^(auto|scroll|hidden|clip|overlay)$/.test(styles.overflowX);
            const clipY = /^(auto|scroll|hidden|clip|overlay)$/.test(styles.overflowY);
            if (!clipX && !clipY) continue;
            const bounds = parent.getBoundingClientRect();
            const x = bounds.left + parent.clientLeft;
            const y = bounds.top + parent.clientTop;
            if (clipX) {
                result.left = Math.max(result.left, x);
                result.right = Math.min(result.right, x + parent.clientWidth);
            }
            if (clipY) {
                result.top = Math.max(result.top, y);
                result.bottom = Math.min(result.bottom, y + parent.clientHeight);
            }
        }
        return result;
    };
    const documentOverflow = html.scrollWidth - html.clientWidth;
    if (documentOverflow > 1) issues.add('rolagem horizontal do documento +' + documentOverflow + ' px');
    for (const wrapper of matches('.scroll-table, .dialog-variants, .chat-bubble, .ai-item-list li')) {
        const overflow = wrapper.scrollWidth - wrapper.clientWidth;
        if (overflow > 1) {
            const label = wrapper.matches('.scroll-table, .dialog-variants') ? 'tabela' : 'bloco de texto';
            issues.add(label + ' exige rolagem horizontal +' + overflow + ' px');
        }
    }
    if (mobile) {
        for (const input of matches('input:not([type=hidden]):not([type=checkbox]):not([type=radio]), select, textarea')) {
            const size = parseFloat(getComputedStyle(input).fontSize);
            if (!input.disabled && !input.readOnly && size < 15.95)
                issues.add('campo editável ' + name(input) + ' com fonte ' + size + ' px');
        }
        for (const button of matches('button, a.button, a.icon-button, #navigation a.nav-link')) {
            if (button.disabled || button.closest('.nav-overlay')) continue;
            const rect = button.getBoundingClientRect();
            if (rect.width < 43.5 || rect.height < 43.5)
                issues.add('alvo de toque ' + name(button) + ' com ' + Math.round(rect.width)
                    + '×' + Math.round(rect.height) + ' px');
        }
    }
    const controls = [];
    for (const node of matches('button, a.button, a.icon-button, input:not([type=hidden]), select, textarea')) {
        // As tabelas que estouram são reportadas no contêiner, sem repetir um diagnóstico por célula.
        const wrapper = node.closest('.scroll-table');
        if (wrapper && wrapper.scrollWidth > wrapper.clientWidth + 1) continue;
        const rect = node.getBoundingClientRect();
        if (rect.width <= 0 || rect.height <= 0 || node.id === 'nav-overlay') continue;
        if (rect.x < left - 1 || rect.right > right + 1)
            issues.add('controle além da largura visível: ' + name(node));
        const displayed = displayedRect(node, rect);
        if (displayed.right > displayed.left && displayed.bottom > displayed.top)
            controls.push({node, rect: displayed});
    }
    for (let index = 0; index < controls.length; index++) {
        const {node: first, rect: a} = controls[index];
        for (const {node: second, rect: b} of controls.slice(index + 1)) {
            if (Math.min(a.right, b.right) - Math.max(a.left, b.left) > 2
                && Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top) > 2
                && !first.contains(second) && !second.contains(first)) {
                issues.add('controles sobrepostos: ' + name(first) + ' e ' + name(second));
            }
        }
    }
    if (modal) {
        const rect = scope.getBoundingClientRect();
        const top = viewport?.offsetTop || 0;
        const bottom = top + (viewport?.height || html.clientHeight);
        if (rect.top < top - 1 || rect.bottom > bottom + 1 || rect.left < left - 1 || rect.right > right + 1)
            issues.add('modal excede área visível');
    }
    return [...issues];
}
"""


class Audit:
    def __init__(self, output, engine, baseline):
        self.output = output.resolve() if output else None
        self.engine = engine
        self.baseline = baseline
        self.results = []
        self.width = self.height = 0

    def viewport(self, page, width, height):
        self.width, self.height = width, height
        page.set_viewport_size({"width": width, "height": height})

    def measure(self, page, name, scope, mobile, modal=False, extra=()):
        issues = page.evaluate(GEOMETRY, {"selector": scope, "mobile": mobile, "modal": modal})
        self.results.append({"width": self.width, "height": self.height, "screen": name,
                             "findings": [*issues, *extra]})

    def screenshot(self, target, name, force=False):
        if not self.output or not (force or (self.width, self.height) in SCREENSHOTS):
            return
        path = self.output / f"{self.engine}-{self.width}x{self.height}-{name}.png"
        target.screenshot(path=path, animations="disabled")

    def finish(self):
        if self.output:
            (self.output / f"{self.engine}-audit.json").write_text(
                json.dumps({"engine": self.engine, "baseline": self.baseline, "screens": self.results},
                           ensure_ascii=False, indent=2) + "\n", encoding="utf-8",
            )
        failing = [entry for entry in self.results if entry["findings"]]
        print(f"{self.engine}: {len(self.results)} inspeções; {len(failing)} com achados.", flush=True)
        for entry in failing[:20]:
            print(f"{entry['width']}×{entry['height']} {entry['screen']}: {', '.join(entry['findings'][:5])}",
                  flush=True)
        if failing and not self.baseline:
            raise AssertionError(f"{len(failing)} inspeções com problemas de layout")


def open_route(page, name):
    nav = page.locator("#nav-toggle")
    if nav.is_visible() and nav.get_attribute("aria-expanded") == "false":
        nav.click()
    link = page.locator(f'[data-page="{name}"]')
    link.click()
    expect(link).to_have_attribute("aria-current", "page")
    expect(page.locator(ROUTES[name]).first).to_be_visible()
    if nav.is_visible():
        expect(nav).to_have_attribute("aria-expanded", "false")
    page.evaluate("window.scrollTo(0, 0)")


def make_context(browser, url, engine, device):
    options = {"base_url": url, "device_scale_factor": 1, "reduced_motion": "reduce",
               "service_workers": "block", "has_touch": device}
    if engine != "firefox":
        options["is_mobile"] = device
    return browser.new_context(**options)


def seed_catalog(page):
    response = page.request.get("/api/catalog")
    assert response.ok
    if any(product["name"] == LONG_MODEL for product in response.json()):
        return
    csrf = page.request.get("/csrf").json()
    result = page.request.post("/api/catalog", headers={csrf["headerName"]: csrf["token"]}, data={
        "name": LONG_MODEL, "active": True, "variants": [
            {"weight": 12, "color": "01", "active": True},
            {"weight": 16, "color": "02", "active": True},
        ],
    })
    assert result.ok, result.status


def seed_draft(page):
    choose(page, "Urunaja", 10)
    color(page, "01").check()
    color(page, "02").check()
    quantity(page, "01").fill("2")
    quantity(page, "02").fill("3")
    page.locator("#item-add").click()
    expect(page.locator("#summary-quantity")).to_have_text("5 unidades")
    expect(page.locator("#manual-items tbody tr")).to_have_count(2)


def public_geometry(audit, page, url, width, height):
    audit.viewport(page, width, height)
    mobile = width <= 768 or width > height and height <= 600
    for name, control in (("login", "#login-submit"), ("offline", "#pwa-retry")):
        page.goto(f"{url}/{name}.html")
        expect(page.locator(control)).to_be_enabled()
        audit.measure(page, name, "body", mobile)
        audit.screenshot(page, name)


def exercise_ai(page):
    page.locator("#chat-message").fill(ORDER)
    expect(page.locator("#chat-message")).to_have_value(ORDER)
    page.locator("#chat-send").click()
    expect(page.locator("#ai-review")).to_be_enabled()
    expect(page.locator("#ai-items")).to_contain_text(LONG_MODEL)
    expect(page.locator("#chat-log")).to_contain_text(LONG_MODEL)
    page.locator("#chat-message").fill(LONG_MESSAGE)
    expect(page.locator("#chat-message")).to_have_value(LONG_MESSAGE)


def measure_route(audit, page, name, mobile):
    open_route(page, name)
    audit.measure(page, name, f'[data-view="{name}"]', mobile)
    audit.screenshot(page, name)
    if name == "manual" and audit.width in (320, 390):
        audit.screenshot(page.locator("#manual-items"), "manual-itens")
    if name == "ai" and audit.width in (320, 390):
        audit.screenshot(page.locator("#chat-form"), "ai-digitacao")


def measure_dialog(audit, page, selector, button, close, name, mobile):
    dialog = page.locator(selector)
    expect(dialog).to_be_visible()
    expect(page.locator(button)).to_be_visible()
    audit.measure(page, name, selector, mobile, modal=True)
    audit.screenshot(dialog, name)
    page.locator(button).click(trial=True)
    page.locator(close).click()
    expect(dialog).not_to_be_visible()


def exercise_dialogs(audit, page, mobile):
    open_route(page, "manual")
    page.locator("#quote-preview-pdf").click()
    measure_dialog(audit, page, "#preview-pdf-dialog", "#preview-pdf-download", "#preview-pdf-close",
                   "pdf-previa", mobile)
    open_route(page, "history")
    expect(page.locator("#history-content")).to_contain_text(LONG_CLIENT)
    row = page.locator("#history-content tbody tr").filter(has_text=LONG_CLIENT).first
    row.get_by_role("link", name=re.compile("Visualizar PDF")).click()
    measure_dialog(audit, page, "#issued-pdf-dialog", "#issued-pdf-download", "#issued-pdf-close", "pdf", mobile)
    row.get_by_role("button", name=SHARE).click()
    expect(page.locator("#quote-share-message")).to_be_enabled()
    expect(page.locator("#quote-share-message")).to_have_value(re.compile("Segue o orçamento"))
    measure_dialog(audit, page, "#quote-share-dialog", "#quote-share-whatsapp", "#quote-share-close", "whatsapp", mobile)
    open_route(page, "catalog")
    page.locator("#catalog-new").click()
    page.locator("#catalog-name").fill("Modelo sintético")
    page.locator("#catalog-weights").fill("10, 12")
    page.locator("#catalog-colors").fill("01, 02")
    page.locator("#add-combinations").click()
    expect(page.locator("#dialog-variants tbody tr")).to_have_count(4)
    measure_dialog(audit, page, "#catalog-dialog", "#catalog-save", "#catalog-cancel", "catalogo-modal", mobile)
    # Abre a ajuda de instalação local sem exigir um prompt nativo do sistema operacional.
    page.locator("#pwa-ios-guide").evaluate("(node) => node.showModal()")
    measure_dialog(audit, page, "#pwa-ios-guide", "#pwa-ios-close", "#pwa-ios-close", "pwa-instalacao", mobile)


def exercise_mobile_interactions(audit, page):
    audit.viewport(page, 390, 844)
    open_route(page, "manual")
    nav = page.locator("#nav-toggle")
    nav.click()
    expect(page.locator("#sidebar")).to_be_visible()
    expect(nav).to_have_attribute("aria-expanded", "true")
    audit.measure(page, "menu", "#sidebar", True)
    audit.screenshot(page, "menu")
    page.keyboard.press("Escape")
    expect(nav).to_have_attribute("aria-expanded", "false")
    menu_issues = [] if nav.evaluate("(node) => document.activeElement === node") else [
        "o foco não foi devolvido ao botão do menu"]
    audit.measure(page, "menu-fechado", "body", True, extra=menu_issues)

    delayed = []
    def hold_catalog(route):
        if delayed:
            route.continue_()
        else:
            delayed.append((route, route.fetch()))

    page.route("**/api/catalog", hold_catalog)
    try:
        open_route(page, "prices")
        price = page.locator("#price-content input").first
        price.focus()
        price.fill("19,80")
        price.evaluate("(node) => { window.auditedPriceInput = node; }")
        if delayed:
            route, response = delayed[0]
            with page.expect_response("**/api/catalog"):
                route.fulfill(response=response)
            page.evaluate("() => new Promise(resolve => requestAnimationFrame(resolve))")
        focused = price.evaluate(
            "(node) => node === document.activeElement && node === window.auditedPriceInput")
        problems = [] if focused else ["campo de preço perdeu o foco ou foi recriado durante a digitação"]
        expect(price).to_have_value("19,80")
        expect(page.locator("#price-save")).to_be_enabled()
        audit.measure(page, "precos-durante-edicao", '[data-view="prices"]', True, extra=problems)
    finally:
        page.unroute("**/api/catalog", hold_catalog)

    open_route(page, "ai")
    chat = page.locator("#chat-message")
    chat.focus()
    chat.fill("Revisão com acentos e emoji:\n" + LONG_MESSAGE)
    audit.viewport(page, 390, 390)
    chat.scroll_into_view_if_needed()
    expect(chat).to_be_focused()
    expect(chat).to_have_value("Revisão com acentos e emoji:\n" + LONG_MESSAGE)
    page.locator("#chat-send").click(trial=True)
    audit.measure(page, "ai-viewport-reduzida", '[data-view="ai"]', True)
    audit.screenshot(page, "ai-viewport-reduzida", force=True)

    audit.viewport(page, 390, 844)
    open_route(page, "history")
    first_share = page.locator("#history-content").get_by_role("button", name=SHARE).first
    first_share.click()
    message = page.locator("#quote-share-message")
    expect(message).to_be_enabled()
    message.focus()
    message.fill("Olá, revisão do orçamento.\nCondição especial preservada.")
    audit.viewport(page, 390, 390)
    expect(message).to_be_focused()
    expect(message).to_have_value("Olá, revisão do orçamento.\nCondição especial preservada.")
    page.locator("#quote-share-whatsapp").click(trial=True)
    audit.measure(page, "whatsapp-viewport-reduzida", "#quote-share-dialog", True, modal=True)
    audit.screenshot(page.locator("#quote-share-dialog"), "whatsapp-viewport-reduzida", force=True)
    page.locator("#quote-share-close").click()
    expect(page.locator("#quote-share-dialog")).not_to_be_visible()


def matrix(browser, engine, url, username, password, audit):
    sizes = [(width, 720 if width < 390 else 844 if width < 768 else 900) for width in WIDTHS]
    sizes.extend(LANDSCAPES)
    for device in (True, False):
        selected = [(w, h) for w, h in sizes if (w <= 768 or h <= 600) == device]
        with make_context(browser, url, engine, device) as unauthenticated:
            anonymous = unauthenticated.new_page()
            for width, height in selected:
                public_geometry(audit, anonymous, url, width, height)
        with make_context(browser, url, engine, device) as context:
            context.add_init_script("""
                Object.defineProperty(navigator, "canShare", {configurable: true, value: () => false});
            """)
            page = context.new_page()
            page.set_default_timeout(6500)
            audit.viewport(page, *selected[0])
            login(page, username, password)
            seed_catalog(page)
            issue_quote(page, variant_for_fixture(page), LONG_CLIENT, "+55 11 90000-0123")
            seed_draft(page)
            open_route(page, "ai")
            exercise_ai(page)

            for width, height in selected:
                audit.viewport(page, width, height)
                for route in ROUTES:
                    measure_route(audit, page, route, device)
                if (width, height) in SCREENSHOTS:
                    exercise_dialogs(audit, page, device)
            if device:
                exercise_mobile_interactions(audit, page)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jar", type=Path)
    parser.add_argument("--engine", choices=("chromium", "firefox", "webkit"), default="chromium")
    parser.add_argument("--channel", choices=("chrome", "msedge", "chromium"))
    parser.add_argument("--output", type=Path)
    parser.add_argument("--baseline", action="store_true", help="Registra problemas do JAR de referência sem falhar.")
    args = parser.parse_args()
    if args.channel and args.engine != "chromium":
        parser.error("--channel exige o motor Chromium")
    if args.output:
        args.output.mkdir(parents=True, exist_ok=True)
    audit = Audit(args.output, args.engine, args.baseline)
    with isolated_application(args.jar.resolve(strict=True)) as (url, username, password), sync_playwright() as runtime:
        with getattr(runtime, args.engine).launch(channel=args.channel) as browser:
            try:
                matrix(browser, args.engine, url, username, password, audit)
            except (PlaywrightError, AssertionError):
                if args.output and audit.results:
                    (args.output / f"{args.engine}-partial.json").write_text(
                        json.dumps(audit.results, ensure_ascii=False, indent=2) + "\n", encoding="utf-8",
                    )
                raise
    audit.finish()


if __name__ == "__main__":
    main()
