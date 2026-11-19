#!/usr/bin/env python3
"""Record the README GIF: an agent driving the todo app over MCP.

Run through `bb demo --record doc/img/demo.gif`, which starts shadow-cljs
and the bridge and passes --page/--mcp. Headless Chromium (python
Playwright) opens the page; this script is the agent: one MCP call per
step of the script, each named in a caption bar under the app while the
page changes. ffmpeg turns the video into a GIF.

Needs: pip install playwright (any Chromium it can launch; set
PLAYWRIGHT_CHROMIUM to a chrome binary when the bundled revision is not
installed) and ffmpeg on the PATH.
"""
import argparse, glob, json, os, shutil, subprocess, sys, tempfile, time, urllib.request

from playwright.sync_api import sync_playwright


class Mcp:
    """A minimal MCP client over streamable HTTP (loopback, no token)."""

    def __init__(self, url):
        self.url, self.sid, self.ids = url, None, 0

    def post(self, msg):
        headers = {"Content-Type": "application/json", "Accept": "application/json"}
        if self.sid:
            headers["Mcp-Session-Id"] = self.sid
        req = urllib.request.Request(self.url, json.dumps(msg).encode(), headers, method="POST")
        with urllib.request.urlopen(req, timeout=60) as r:
            self.sid = r.headers.get("Mcp-Session-Id") or self.sid
            body = r.read()
            return json.loads(body) if body else None

    def init(self):
        self.post({"jsonrpc": "2.0", "id": 0, "method": "initialize",
                   "params": {"protocolVersion": "2025-06-18"}})
        self.post({"jsonrpc": "2.0", "method": "notifications/initialized"})

    def call(self, tool, args):
        self.ids += 1
        r = self.post({"jsonrpc": "2.0", "id": self.ids, "method": "tools/call",
                       "params": {"name": tool, "arguments": args}})
        res = r.get("result", {})
        text = next((c.get("text") for c in res.get("content", []) if c.get("text")), None)
        self.last_text = text or (r.get("error") or {}).get("message")
        return res.get("structuredContent"), bool(res.get("isError") or r.get("error"))


def runtimes(mcp):
    value, _ = mcp.call("autonomous_runtimes", {})
    return [rt.get("runtime/id") for rt in (value or {}).get("runtimes", [])]


def resolve(args, todos):
    """Replace {"todo": text} references by the id of the todo with that text."""
    by_text = {t["text"]: t["id"] for t in todos}
    return {k: by_text.get(v["todo"], v) if isinstance(v, dict) and set(v) == {"todo"} else v
            for k, v in args.items()}


def caption(tool, args):
    return tool + (" {" + ", ".join(f"{k}: {json.dumps(v)}" for k, v in args.items()) + "}" if args else "")


BAR_JS = """([title, line, refused]) => {
  let bar = document.getElementById('demo-caption');
  if (!bar) {
    bar = document.createElement('div');
    bar.id = 'demo-caption';
    bar.style.cssText = 'position:fixed;left:0;right:0;bottom:0;padding:10px 14px;' +
      'font:15px/1.4 ui-monospace,monospace;background:#111;color:#eee;border-top:3px solid #f5a623';
    document.body.appendChild(bar);
    document.body.style.cssText = 'font:16px/1.5 system-ui,sans-serif;margin:24px 28px 110px';
  }
  bar.innerHTML = '<div style="color:#f5a623">' + title + '</div><div></div>';
  bar.lastChild.textContent = line;
  bar.lastChild.style.color = refused ? '#ff6b6b' : '#8fd18f';
}"""


def chromium_path():
    if os.environ.get("PLAYWRIGHT_CHROMIUM"):
        return os.environ["PLAYWRIGHT_CHROMIUM"]
    found = sorted(glob.glob(os.path.expanduser("~/.cache/ms-playwright/chromium-*/chrome-linux64/chrome")))
    return found[-1] if found else None


FPS = 5


def record(page_url, mcp_url, script, out, width=720, height=480):
    steps = json.load(open(script))
    mcp = Mcp(mcp_url)
    mcp.init()
    before = set(runtimes(mcp))
    video_dir = tempfile.mkdtemp(prefix="autonomous-demo-")
    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=True, executable_path=chromium_path())
        ctx = browser.new_context(viewport={"width": width, "height": height})
        page = ctx.new_page()
        frames = [0]

        def hold(seconds):
            # Timed screenshots, joined by ffmpeg at FPS: no video codec needed in the browser.
            for _ in range(max(1, int(seconds * FPS))):
                frames[0] += 1
                page.screenshot(path=os.path.join(video_dir, "f%05d.png" % frames[0]))
                time.sleep(1.0 / FPS)
        page.goto(page_url)
        rt = None
        for _ in range(120):
            fresh = [r for r in runtimes(mcp) if r not in before]
            if fresh:
                rt = max(fresh, key=lambda r: int(r) if str(r).isdigit() else -1)
                break
            time.sleep(0.5)
        if rt is None:
            sys.exit("the page did not connect to shadow-cljs as a runtime")
        mcp.call("autonomous_attach", {"runtime_id": rt})
        page.evaluate(BAR_JS, ["an agent drives this app over MCP", f"autonomous_attach {{runtime_id: {rt}}}", False])
        hold(1.5)
        for step in steps:
            todos, _ = mcp.call("todos_visible", {})
            args = resolve(step.get("args", {}), (todos or {}).get("result", []))
            value, refused = mcp.call(step["tool"], args)
            if refused:
                line = "refused: " + " ".join(str(mcp.last_text or step.get("note") or "").split())[:90]
            else:
                shown = value.get("result", value) if isinstance(value, dict) else value
                line = "ok " + json.dumps(shown)[:90]
            page.evaluate(BAR_JS, ["agent \u2192 " + caption(step["tool"], args), line, refused])
            hold(1.6)
        hold(1.0)
        ctx.close()
        browser.close()
    palette = os.path.join(video_dir, "palette.png")
    video = ["-framerate", str(FPS), "-i", os.path.join(video_dir, "f%05d.png")]
    vf = "scale=%d:-1:flags=lanczos" % width
    subprocess.run(["ffmpeg", "-v", "error", "-y", *video, "-vf", vf + ",palettegen=max_colors=64", palette], check=True)
    subprocess.run(["ffmpeg", "-v", "error", "-y", *video, "-i", palette,
                    "-lavfi", vf + " [x]; [x][1:v] paletteuse=dither=bayer:bayer_scale=5", out], check=True)
    shutil.rmtree(video_dir, ignore_errors=True)
    print(f"wrote {out} ({os.path.getsize(out) // 1024} KiB)")


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--page", required=True)
    ap.add_argument("--mcp", required=True)
    ap.add_argument("--script", required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    record(a.page, a.mcp, a.script, a.out)
