"""Valida a PWA em Chromium, com H2 isolado e um segundo worker servido localmente."""

from argparse import ArgumentParser
from contextlib import contextmanager
from http.client import HTTPConnection
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import struct
import sys
from threading import Thread
from time import time
from urllib.parse import urlsplit

from playwright.sync_api import expect, sync_playwright

sys.dont_write_bytecode = True
from test_bulk_colors import choose, color, find_product, isolated_application, login, put_setting, submit_and_check_preview
from test_whatsapp_share import issue_quote


WORKER = "/service-worker.js"
CACHE = "urutau-jigs-pwa-0.2.0"
NEXT_CACHE = "urutau-jigs-pwa-0.2.0-browser-next"
UNRELATED_CACHE = "qa-cache-fora-da-pwa"
PNG_ICONS = {
    "/icons/icon-192.png": 192,
    "/icons/icon-512.png": 512,
    "/icons/icon-maskable-512.png": 512,
    "/icons/apple-touch-icon.png": 180,
}
PUBLIC_CACHE_PATHS = {
    "/offline.html", "/pwa.js", "/app.css", "/login.js", "/icons.svg",
    "/assets/urutau-logo.png", "/manifest.webmanifest", "/icons/favicon.ico", *PNG_ICONS,
}
CONNECTION_PROBE = "/pwa.js?connection-check=1"
INSTALL_EVENT = """() => {
    const event = new Event("beforeinstallprompt", { cancelable: true });
    window.promptFixture = { calls: 0, activated: false };
    event.prompt = async () => {
        window.promptFixture.calls++;
        window.promptFixture.activated = navigator.userActivation.isActive;
        return { outcome: "dismissed" };
    };
    event.userChoice = Promise.resolve({ outcome: "dismissed" });
    window.dispatchEvent(event);
}"""


@contextmanager
def staged_origin(upstream_url):
    """Serve o mesmo endereço local durante a troca do worker e uma falha do proxy."""
    upstream = urlsplit(upstream_url)

    class PassThrough(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *_):
            pass

        def do_GET(self):
            self.forward()

        do_POST = do_PUT = do_HEAD = do_GET

        def forward(self):
            if self.server.unavailable:
                self.send_error(503)
                return
            exclusions = {"connection", "accept-encoding"}
            if urlsplit(self.path).path == WORKER and self.server.new_version:
                exclusions.update({"if-modified-since", "if-none-match"})
            request_headers = {
                key: value for key, value in self.headers.items()
                if key.lower() not in exclusions
            }
            length = int(self.headers.get("Content-Length", 0))
            body = self.rfile.read(length) if length else None
            connection = HTTPConnection(upstream.hostname, upstream.port, timeout=15)
            try:
                connection.request(self.command, self.path, body, request_headers)
                result = connection.getresponse()
                content = result.read()
                if urlsplit(self.path).path == WORKER and self.server.new_version and result.status == 200:
                    assert CACHE.encode() in content, "A versão inicial do cache não foi encontrada no worker."
                    content = content.replace(CACHE.encode(), NEXT_CACHE.encode())
                self.send_response_only(result.status, result.reason)
                for key, value in result.getheaders():
                    if key.lower() not in {"connection", "content-length", "transfer-encoding"}:
                        self.send_header(key, value)
                self.send_header("Content-Length", str(len(content)))
                self.end_headers()
                if content and self.command != "HEAD":
                    self.wfile.write(content)
            finally:
                connection.close()

    class TestOrigin(ThreadingHTTPServer):
        request_queue_size = 32

    with TestOrigin(("127.0.0.1", 0), PassThrough) as server:
        server.new_version = False
        server.unavailable = False
        thread = Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            yield server, f"http://127.0.0.1:{server.server_port}"
        finally:
            server.shutdown()
            thread.join()


def wait_for_worker(page):
    page.evaluate("""async () => {
        let timeout;
        try {
            await Promise.race([
                (async () => {
                    await navigator.serviceWorker.ready;
                    if (!navigator.serviceWorker.controller) {
                        await new Promise(resolve => {
                            navigator.serviceWorker.addEventListener("controllerchange", resolve, { once: true });
                        });
                    }
                })(),
                new Promise((_, reject) => {
                    timeout = setTimeout(async () => {
                        const registration = await navigator.serviceWorker.getRegistration();
                        const states = [registration?.installing?.state, registration?.waiting?.state,
                            registration?.active?.state, ...(await caches.keys())];
                        reject(new Error("Service Worker não assumiu a página: " + JSON.stringify(states)));
                    }, 15000);
                })
            ]);
        } finally {
            clearTimeout(timeout);
        }
    }""")
    assert urlsplit(page.evaluate("navigator.serviceWorker.controller.scriptURL")).path == WORKER


def cached_paths(page):
    return page.evaluate("""async () => {
        const result = {};
        for (const name of await caches.keys()) {
            result[name] = (await (await caches.open(name)).keys()).map(request => new URL(request.url).pathname);
        }
        return result;
    }""")


def check_manifest(page, context):
    delivered = page.request.get("/manifest.webmanifest", max_redirects=0)
    assert delivered.ok and "application/manifest+json" in delivered.headers["content-type"], delivered.headers
    manifest = delivered.json()
    assert manifest["name"] == manifest["short_name"] == "URUTAU JIGS"
    assert (manifest["id"], manifest["start_url"], manifest["scope"]) == ("/", "/", "/")
    assert (manifest["display"], manifest["lang"]) == ("standalone", "pt-BR")
    icons = {icon["src"]: icon for icon in manifest["icons"]}
    for path, size in PNG_ICONS.items():
        response = page.request.get(path, max_redirects=0)
        assert response.ok, (path, response.status)
        png = response.body()
        assert png[:8] == b"\x89PNG\r\n\x1a\n" and struct.unpack(">II", png[16:24]) == (size, size)
        if size != 180:
            assert icons[path]["sizes"] == f"{size}x{size}"
            purpose = "maskable" if "maskable" in path else "any"
            assert purpose in icons[path].get("purpose", "any").split()
    favicon = page.request.get("/icons/favicon.ico", max_redirects=0)
    assert favicon.ok and favicon.body()[:4] == b"\x00\x00\x01\x00"
    expect(page.locator('link[rel="manifest"]')).to_have_attribute("href", "/manifest.webmanifest")
    expect(page.locator('link[rel="apple-touch-icon"]')).to_have_attribute("href", "/icons/apple-touch-icon.png")
    expect(page.locator('link[rel~="icon"]')).to_have_attribute("href", "/icons/favicon.ico")

    devtools = context.new_cdp_session(page)
    try:
        assert devtools.send("Page.getAppManifest")["errors"] == []
        errors = devtools.send("Page.getInstallabilityErrors")["installabilityErrors"]
        # Chromium reserva a instalação física para perfis comuns. Os demais critérios devem passar.
        assert all(error["errorId"] == "in-incognito" for error in errors), errors
    finally:
        devtools.detach()


def create_issued_quote(page):
    spinner = find_product(page, "Spinner")
    variant = next(item["id"] for item in spinner["variants"] if item["weight"] == 12 and item["color"] == "03")
    put_setting(page, "/api/catalog/prices", {
        "variants": [{"id": variant, "retailPrice": 12.5, "wholesalePrice": 10}]
    })
    quote = issue_quote(page, variant, "Cliente de teste da PWA")
    assert quote["number"] >= 1
    return quote["number"], variant


def check_connectivity_and_auth(page, context, server, variant, quote_number):
    assert page.evaluate("""async number => {
        const paths = ["/api/session", "/api/catalog", "/api/ai", "/api/quotes", `/api/quotes/${number}/pdf`];
        const statuses = await Promise.all(paths.map(path => fetch(path).then(response => response.ok)));
        return statuses.every(Boolean);
    }""", quote_number)
    assert cached_paths(page) == {CACHE: sorted(PUBLIC_CACHE_PATHS)} or {
        name: set(paths) for name, paths in cached_paths(page).items()
    } == {CACHE: PUBLIC_CACHE_PATHS}
    # O Remember Me deve seguir funcionando quando só o cookie de sessão desaparece.
    remember = next(cookie for cookie in context.cookies() if cookie["name"] == "URUTAU_REMEMBER_ME")
    assert remember["httpOnly"] and remember["sameSite"] == "Lax"
    assert 89 * 86400 < remember["expires"] - time() <= 90 * 86400 + 60
    context.clear_cookies(name="JSESSIONID")
    page.reload()
    expect(page.locator("#item-model")).to_be_enabled()
    assert page.request.get("/api/session", max_redirects=0).ok

    choose(page, "Spinner", 12)
    color(page, "03").tap()
    page.get_by_label("Quantidade da cor 03", exact=True).fill("2")
    submit_and_check_preview(page, page.locator("#item-add").tap, {variant: 2})
    context.set_offline(True)
    try:
        modal = page.locator("#pwa-offline")
        expect(modal).to_be_visible()
        assert modal.evaluate("node => node.matches(':modal')")
        assert page.evaluate("""async () => {
            try { await fetch("/api/catalog"); return false; } catch { return true; }
        }""")
    finally:
        context.set_offline(False)
    expect(page.locator("#pwa-offline")).not_to_be_visible()
    expect(page.locator("#summary-quantity")).to_have_text("2 unidades")

    server.unavailable = True
    try:
        expect(page.locator("#pwa-offline")).not_to_be_visible()
        page.locator("#nav-toggle").tap()
        page.locator('a[data-page="history"]').tap()
        expect(page.locator("#pwa-offline")).to_be_visible()
        offline = page.goto("/")
        assert offline.from_service_worker
        expect(page.get_by_role("heading", name="Você está sem conexão.")).to_be_visible()
        expect(page.locator("#client-name")).to_have_count(0)
        assert page.evaluate("""async () => {
            const response = await fetch("/pwa.js?connection-check=1");
            return response.status;
        }""") == 503
        assert set(cached_paths(page)[CACHE]) == PUBLIC_CACHE_PATHS
    finally:
        server.unavailable = False
    page.locator("#pwa-retry").tap()
    expect(page.locator("#item-model")).to_be_enabled()
    assert page.request.get("/api/session").ok


def check_update(page, context, server):
    page.evaluate("name => caches.open(name)", UNRELATED_CACHE)
    page.locator("#client-name").fill("Rascunho em andamento")
    sibling = context.new_page()
    sibling.goto("/")
    expect(sibling.locator("#item-model")).to_be_enabled()
    sibling.locator("#client-name").fill("Rascunho na outra aba")
    server.new_version = True
    page.evaluate("async () => (await navigator.serviceWorker.ready).update()")
    notice = page.locator("#pwa-update-notice")
    button = page.locator("#pwa-update")
    expect(notice).to_be_visible()
    expect(notice).to_contain_text("Uma nova versão do URUTAU JIGS está disponível.")
    expect(button).to_be_disabled()
    expect(page.locator("#client-name")).to_have_value("Rascunho em andamento")
    assert set(cached_paths(page)) == {CACHE, NEXT_CACHE, UNRELATED_CACHE}
    page.locator("#client-name").fill("")
    expect(button).to_be_enabled()
    with page.expect_navigation():
        button.tap()
    expect(page.locator("#item-model")).to_be_enabled()
    wait_for_worker(page)
    assert {name: set(paths) for name, paths in cached_paths(page).items()} == {
        NEXT_CACHE: PUBLIC_CACHE_PATHS, UNRELATED_CACHE: set(),
    }
    expect(sibling.locator("#client-name")).to_have_value("Rascunho na outra aba")
    sibling.close()


def check_logout(page, context, quote_number):
    assert page.request.post("/api/quotes", data={}).status == 403
    page.locator("#nav-toggle").tap()
    page.get_by_role("button", name="Sair", exact=True).tap()
    expect(page.get_by_role("button", name="Entrar")).to_be_visible()
    assert "URUTAU_REMEMBER_ME" not in {cookie["name"] for cookie in context.cookies()}
    page.goto("/")
    expect(page.get_by_role("button", name="Entrar")).to_be_visible()
    for path in ("/api/session", f"/api/quotes/{quote_number}/pdf"):
        assert page.request.get(path, max_redirects=0).status == 401
    assert set(cached_paths(page)[NEXT_CACHE]) == PUBLIC_CACHE_PATHS


def install_simulation(page):
    page.evaluate(INSTALL_EVENT)
    button = page.locator("#pwa-install")
    expect(button).to_be_visible()
    assert page.evaluate("window.promptFixture.calls") == 0
    button.tap()
    assert page.evaluate("window.promptFixture") == {"calls": 1, "activated": True}
    expect(button).not_to_be_visible()
    page.evaluate('window.dispatchEvent(new Event("appinstalled"))')
    expect(button).not_to_be_visible()


def check_install_modes(browser, base_url, username, password):
    iphone_ua = (
        "Mozilla/5.0 (iPhone; CPU iPhone OS 26_0 like Mac OS X) AppleWebKit/605.1.15 "
        "(KHTML, like Gecko) Version/26.0 Mobile/15E148 Safari/604.1"
    )
    for standalone in (False, True):
        with browser.new_context(
            base_url=base_url, user_agent=iphone_ua, viewport={"width": 375, "height": 812},
            is_mobile=True, has_touch=True,
        ) as context:
            context.add_init_script(f'Object.defineProperty(navigator, "standalone", {{value: {str(standalone).lower()}}});')
            page = context.new_page()
            login(page, username, password)
            page.locator("#nav-toggle").tap()
            button = page.locator("#pwa-install")
            if standalone:
                page.evaluate(INSTALL_EVENT)
                expect(button).not_to_be_visible()
                assert page.evaluate("window.promptFixture.calls") == 0
                page.locator('a[data-page="ai"]').tap()
                chat = page.locator("#chat-message")
                expect(chat).to_be_enabled()
                chat.fill("• Spinner | 12g | Cor 03 | Qtd: 3\n\n*Total: 3 unidades*")
                page.locator("#chat-send").tap()
                review = page.locator("#ai-review")
                expect(review).to_be_enabled()
                review.tap()
                expect(page.locator("#summary-quantity")).to_have_text("3 unidades")
            else:
                expect(button).to_be_visible()
                button.tap()
                guide = page.locator("#pwa-ios-guide")
                expect(guide).to_be_visible()
                expect(guide).to_contain_text("Safari")
                expect(guide).to_contain_text("Adicionar à Tela de Início")
                page.locator("#pwa-ios-close").tap()
                expect(guide).not_to_be_visible()
            assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth")


def main():
    parser = ArgumentParser(description=__doc__)
    parser.add_argument("jar", type=Path, help="Caminho do JAR executável gerado pelo Maven")
    parser.add_argument("--channel", choices=["chrome", "msedge"], help="Use um navegador já instalado")
    args = parser.parse_args()
    with isolated_application(args.jar.resolve(strict=True)) as (upstream, username, password), \
            staged_origin(upstream) as (server, base_url), sync_playwright() as playwright:
        with playwright.chromium.launch(channel=args.channel) as browser:
            with browser.new_context(
                base_url=base_url, viewport={"width": 375, "height": 812}, is_mobile=True, has_touch=True,
            ) as context:
                page = context.new_page()
                page.goto("/")
                expect(page.get_by_role("button", name="Entrar")).to_be_visible()
                wait_for_worker(page)
                check_manifest(page, context)
                print("OK manifesto, ícones e worker ativo no Chromium em perfil de teste", flush=True)
                login(page, username, password)
                page.locator("#nav-toggle").tap()
                install_simulation(page)
                page.locator("#nav-overlay").tap(position={"x": page.viewport_size["width"] - 20, "y": 40})
                number, variant = create_issued_quote(page)
                check_connectivity_and_auth(page, context, server, variant, number)
                print("OK restrição do cache, modo offline, recuperação e autenticação persistente", flush=True)
                check_update(page, context, server)
                check_logout(page, context, number)
                print("OK troca real entre workers, rascunhos preservados, logout e CSRF", flush=True)
            check_install_modes(browser, base_url, username, password)
            print("OK instalação assistida, instruções do Safari e standalone simulados em tela de 375 px", flush=True)


if __name__ == "__main__":
    main()
