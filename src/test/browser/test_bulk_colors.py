"""Exercita o gerador manual em Chromium contra o JAR, com banco H2 descartável."""

from argparse import ArgumentParser
from contextlib import contextmanager
from decimal import Decimal
from http.client import HTTPConnection
from pathlib import Path
import re
from secrets import token_urlsafe
import subprocess
from tempfile import TemporaryDirectory
from time import monotonic, sleep

from playwright.sync_api import expect, sync_playwright


@contextmanager
def isolated_application(jar):
    username = "qa-cores"
    password = token_urlsafe(18)
    with TemporaryDirectory(prefix="urutau-cores-") as directory:
        log = Path(directory) / "startup.log"
        with log.open("w", encoding="utf-8") as output:
            process = subprocess.Popen([
                "java", "-jar", str(jar), "--server.port=0", "--server.address=127.0.0.1",
                "--spring.profiles.active=default", "--spring.config.name=application", "--spring.config.location=classpath:/",
                "--server.servlet.session.cookie.secure=false", "--server.forward-headers-strategy=none",
                "--spring.datasource.url=jdbc:h2:mem:bulk_colors;MODE=MySQL;DATABASE_TO_LOWER=TRUE",
                "--spring.datasource.driver-class-name=org.h2.Driver", "--spring.datasource.username=sa", "--spring.datasource.password=",
                f"--urutau.admin.primary.username={username}", f"--urutau.admin.primary.password={password}",
                "--urutau.admin.secondary.username=qa-secundario",
                f"--urutau.admin.secondary.password={token_urlsafe(18)}",
                "--urutau.ai.gemini.api-key=", "--urutau.ai.openai.api-key=",
            ], cwd=directory, stdout=output, stderr=subprocess.STDOUT,
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
            try:
                yield wait_for_application(process, log), username, password
            finally:
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=10)


def wait_for_application(process, log):
    deadline = monotonic() + 60
    while process.poll() is None and monotonic() < deadline:
        match = re.search(r"Tomcat started on port (\d+)", log.read_text(encoding="utf-8", errors="replace"))
        if match:
            port = int(match[1])
            connection = HTTPConnection("127.0.0.1", port, timeout=1)
            try:
                connection.request("GET", "/login.html")
                if connection.getresponse().status == 200:
                    return f"http://127.0.0.1:{port}"
            except OSError:
                pass
            finally:
                connection.close()
        sleep(0.25)
    # A linha de comando inclui senhas temporárias. Relate o erro sem imprimi-la.
    raise RuntimeError(f"O JAR não ficou disponível para o teste (código {process.poll()}).")


def login(page, username, password):
    page.goto("/")
    page.get_by_label("Usuário").fill(username)
    page.get_by_label("Senha").fill(password)
    page.get_by_role("button", name="Entrar").click()
    expect(page.locator("#item-model")).to_be_enabled()


def put_setting(page, path, body):
    csrf_response = page.request.get("/csrf")
    assert csrf_response.ok, csrf_response.status
    csrf = csrf_response.json()
    response = page.request.put(path, data=body, headers={csrf["headerName"]: csrf["token"]})
    assert response.ok, (path, response.status)


def find_product(page, name):
    response = page.request.get("/api/catalog")
    assert response.ok, response.status
    return next(product for product in response.json() if product["name"] == name)


def choose(page, model, weight):
    page.locator("#item-model").select_option(label=model)
    page.locator("#item-weight").select_option(str(weight))


def color(page, code, role="checkbox"):
    return page.locator("#item-colors").get_by_role(role, name=f"Cor {code}", exact=True)


def quantity(page, code):
    return page.locator("#item-colors").get_by_label(f"Quantidade da cor {code}", exact=True)


def assert_colors(page, codes):
    expect(page.locator("#item-colors").get_by_role("checkbox")).to_have_count(len(codes))
    for code in codes:
        expect(color(page, code)).to_be_visible()


def assert_selection(page, colors, total):
    for locator, value in (("#item-selected-count", colors), ("#item-selected-total", total)):
        expect(page.locator(locator)).to_have_text(re.compile(rf"(?<!\d){value}(?!\d)"))


def assert_line(page, model, weight, code, units):
    row = page.locator("#manual-items tbody tr").filter(has_text=f"{weight} g · Cor {code}").filter(has_text=model)
    expect(row).to_have_count(1)
    expect(row.locator("td").nth(1)).to_have_text(str(units))


def is_quote_preview(request):
    return request.method == "POST" and request.url.endswith("/api/quotes/preview")


def submit_and_check_preview(page, click, expected_items):
    requests = []

    def capture(request):
        if is_quote_preview(request):
            requests.append(request.post_data_json["items"])

    page.on("request", capture)
    try:
        with page.expect_response(lambda response: is_quote_preview(response.request)) as pending:
            click()
        response = pending.value
        assert response.ok, (response.status, response.text())
        result = response.json()
        expect(page.locator("#summary-quantity")).to_have_text(f"{result['quantity']} unidades")
        expect(page.locator("#summary-tier")).to_have_text(
            "Lojista" if result["priceTier"] == "LOJISTA" else "Consumidor final")
        assert len(requests) == 1, requests
        actual_items = {item["variantId"]: item["quantity"] for item in requests[0]}
        assert len(requests[0]) == len(actual_items), requests
        assert actual_items == expected_items, requests
        return result
    finally:
        page.remove_listener("request", capture)


def urunaja_selection(page):
    put_setting(page, "/api/settings", {"wholesaleThreshold": 50})
    variants = {item["color"]: item["id"] for item in find_product(page, "Urunaja")["variants"] if item["weight"] == 10}
    put_setting(page, "/api/catalog/prices", {"variants": [
        {"id": variant_id, "retailPrice": 17, "wholesalePrice": 15} for variant_id in variants.values()
    ]})
    page.reload()
    choose(page, "Urunaja", 10)
    codes = [f"{number:02}" for number in range(1, 10)]
    assert_colors(page, codes)
    add = page.locator("#item-add")
    expect(add).to_be_disabled()
    color(page, "09").check()
    quantity(page, "09").fill("3")
    page.locator("#item-quantity").fill("5")
    page.locator("#item-select-all").click()
    assert_selection(page, 9, 43)
    page.locator("#item-apply-quantity").click()
    for code in codes:
        expect(quantity(page, code)).to_have_value("5")
    assert_selection(page, 9, 45)
    quantity(page, "01").fill("10")
    assert_selection(page, 9, 50)

    expected_items = {variants[code]: 10 if code == "01" else 5 for code in codes}
    preview = submit_and_check_preview(page, add.click, expected_items)
    assert (preview["quantity"], preview["priceTier"], Decimal(preview["total"])) == (50, "LOJISTA", 750)
    expect(page.locator("#manual-items tbody tr")).to_have_count(9)
    assert_line(page, "Urunaja", 10, "01", 10)
    assert_selection(page, 0, 0)
    expect(add).to_be_disabled()

    page.get_by_role("button", name="Editar Urunaja, 10 g, cor 01", exact=True).click()
    expect(color(page, "01", role="radio")).to_be_checked()
    expect(quantity(page, "01")).to_have_value("10")
    quantity(page, "01").fill("5")
    expected_items[variants["01"]] = 5
    preview = submit_and_check_preview(page, add.click, expected_items)
    assert (preview["quantity"], preview["priceTier"], Decimal(preview["total"])) == (45, "CONSUMIDOR_FINAL", 765)
    assert_line(page, "Urunaja", 10, "01", 5)

    choose(page, "Urunaja", 10)
    page.locator("#item-quantity").fill("5")
    color(page, "01").check()
    assert_selection(page, 1, 5)
    expected_items[variants["01"]] = 10
    preview = submit_and_check_preview(page, add.click, expected_items)
    assert (preview["quantity"], preview["priceTier"]) == (50, "LOJISTA")
    expect(page.locator("#manual-items tbody tr")).to_have_count(9)
    assert_line(page, "Urunaja", 10, "01", 10)


def spinner_selection(page):
    spinner = find_product(page, "Spinner")
    variants = {item["color"]: item for item in spinner["variants"] if item["weight"] == 12}
    catalog_update = {"name": spinner["name"], "active": True, "variants": [
        {"weight": item["weight"], "color": item["color"],
         "active": (item["weight"], item["color"]) != (14, "06")}
        for item in spinner["variants"]
    ]}
    catalog_update["variants"].append({"weight": 14, "color": "QA-21", "active": True})
    put_setting(page, f"/api/catalog/{spinner['id']}", catalog_update)
    unpriced = variants["03"]
    put_setting(page, "/api/catalog/prices", {"variants": [{
        "id": unpriced["id"], "retailPrice": None, "wholesalePrice": unpriced["wholesalePrice"],
    }]})
    page.reload()
    choose(page, "Spinner", 12)
    assert_colors(page, ["01", "03", "04", "06", "08"])
    add = page.locator("#item-add")
    color(page, "03").check()
    color(page, "08").check()
    assert_selection(page, 2, 2)
    page.locator("#item-quantity").fill("0")
    expect(page.locator("#item-apply-quantity")).to_be_disabled()
    assert_selection(page, 2, 2)
    page.locator("#item-quantity").fill("1")
    page.locator("#item-clear-all").click()
    assert_selection(page, 0, 0)
    expect(add).to_be_disabled()

    color(page, "08").check()
    quantity(page, "08").fill("6")
    page.locator("#item-weight").select_option("14")
    assert_colors(page, ["01", "03", "04", "08", "QA-21"])
    assert_selection(page, 0, 0)
    color(page, "QA-21").check()
    page.locator("#item-model").select_option(label="Urunaja")
    expect(page.locator("#item-weight")).to_have_value("")
    expect(page.locator("#item-colors").get_by_role("checkbox")).to_have_count(0)
    expect(add).to_be_disabled()

    choose(page, "Spinner", 12)
    color(page, "03").check()
    color(page, "08").check()
    for value in ["-1", "0", "2.5", "1.0", "1e1", "100001", ""]:
        quantity(page, "03").fill(value)
        expect(add).to_be_disabled()
        expect(page.locator("#summary-quantity")).to_have_text("0 unidades")
    quantity(page, "03").press_sequentially("abc")
    expect(add).to_be_disabled()
    quantity(page, "03").fill("3")
    quantity(page, "08").fill("7")
    assert_selection(page, 2, 10)
    preview = submit_and_check_preview(page, add.click, {variants["03"]["id"]: 3, variants["08"]["id"]: 7})
    assert preview["quantity"] == 10
    assert preview["missingPrices"] == 1
    assert_line(page, "Spinner", 12, "03", 3)
    assert_line(page, "Spinner", 12, "08", 7)

    color(page, "01").check()
    color(page, "03").check()
    quantity(page, "01").fill("2")
    quantity(page, "03").fill("99998")
    add.click()
    expect(page.locator("#item-error")).to_contain_text("100.000")
    assert_line(page, "Spinner", 12, "03", 3)
    expect(page.locator("#manual-items tbody tr")).to_have_count(2)
    expect(page.locator("#summary-quantity")).to_have_text("10 unidades")

    expect(page.locator("#quote-issue")).to_be_disabled()
    with page.expect_response("**/api/quotes/preview/pdf") as pending:
        page.locator("#quote-preview-pdf").click()
    assert pending.value.ok and pending.value.body().startswith(b"%PDF-")
    expect(page.locator("#preview-pdf-download")).to_be_visible()


def mobile_selection(page):
    choose(page, "Spinner", 12)
    assert_colors(page, ["01", "03", "04", "06", "08"])
    assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth"), "Página excede a tela móvel"
    for option in page.locator("#item-colors .item-color-option").all():
        box = option.bounding_box()
        assert box and box["x"] >= 0 and box["x"] + box["width"] <= page.viewport_size["width"] + 1, box

    page.locator("#item-select-all").tap()
    assert_selection(page, 5, 5)
    page.locator("#item-clear-all").tap()
    assert_selection(page, 0, 0)
    color(page, "01").tap()
    color(page, "08").tap()
    expect(color(page, "01")).to_be_checked()
    expect(color(page, "08")).to_be_checked()
    page.locator("#item-quantity").fill("4")
    page.locator("#item-apply-quantity").tap()
    quantity(page, "08").fill("6")
    assert_selection(page, 2, 10)

    variants = {item["color"]: item["id"] for item in find_product(page, "Spinner")["variants"] if item["weight"] == 12}
    submit_and_check_preview(page, page.locator("#item-add").tap, {variants["01"]: 4, variants["08"]: 6})
    assert_line(page, "Spinner", 12, "01", 4)
    assert_line(page, "Spinner", 12, "08", 6)


def main():
    parser = ArgumentParser(description=__doc__)
    parser.add_argument("jar", type=Path, help="Caminho do JAR executável gerado pelo Maven")
    parser.add_argument("--channel", choices=["chrome", "msedge"], help="Use um navegador já instalado")
    args = parser.parse_args()
    jar = args.jar.resolve(strict=True)

    with isolated_application(jar) as (url, username, password), sync_playwright() as playwright:
        browser = playwright.chromium.launch(channel=args.channel)
        for scenario, label, options in [
            (urunaja_selection, "Urunaja, quantidades, edição, soma e classificação", {"viewport": {"width": 1440, "height": 900}}),
            (spinner_selection, "Spinner, catálogo ativo, validação, atomicidade e PDF", {"viewport": {"width": 1440, "height": 900}}),
            (mobile_selection, "Seleção e adição por toque em tela de 375 px", {
                "viewport": {"width": 375, "height": 812}, "is_mobile": True, "has_touch": True, "device_scale_factor": 2,
            }),
        ]:
            context = browser.new_context(base_url=url, **options)
            try:
                page = context.new_page()
                login(page, username, password)
                scenario(page)
                print(f"OK {label}", flush=True)
            finally:
                context.close()
        browser.close()


if __name__ == "__main__":
    main()
