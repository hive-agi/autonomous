"""Keep the todo page open in headless Chromium, so the frontend's agent tools
are live without a person at a browser. Used by `bb fullstack --headless` and
`bb fullstack-e2e`. Needs `pip install playwright` and `playwright install chromium`.

    python3 headless.py http://127.0.0.1:8090/
"""
import glob
import os
import shutil
import sys
import time

from playwright.sync_api import sync_playwright

url = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8090/"


def launch(p):
    """Playwright's own Chromium, else any Chromium already on the machine
    (an older Playwright download, or a system install)."""
    try:
        return p.chromium.launch()
    except Exception as first:
        cache = os.path.expanduser("~/.cache/ms-playwright")
        candidates = sorted(glob.glob(f"{cache}/chromium-*/chrome-linux*/chrome"), reverse=True)
        candidates += [c for c in (shutil.which("chromium"), shutil.which("google-chrome")) if c]
        for exe in candidates:
            try:
                print(f"[headless] falling back to {exe}", flush=True)
                return p.chromium.launch(executable_path=exe)
            except Exception:
                continue
        raise first


with sync_playwright() as p:
    browser = launch(p)
    page = browser.new_page()
    page.on("console", lambda m: print(f"[console.{m.type}] {m.text}", flush=True))
    page.on("pageerror", lambda e: print(f"[pageerror] {e}", flush=True))
    page.goto(url)
    print(f"[headless] opened {url}", flush=True)
    while True:
        time.sleep(1)
