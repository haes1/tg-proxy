#!/usr/bin/env python3
"""Collect MTProto proxies from public Telegram channels into proxies.json.

Reads the channels' public web preview (https://t.me/s/<channel>), newest posts first,
pulls every proxy link (t.me/proxy?..., tg://proxy?...) and writes the format the app expects:
[{"server": "...", "port": 443, "secret": "..."}]

Usage: update_proxies.py [output] [channel ...]
"""

import base64
import html
import json
import re
import sys
import urllib.parse
import urllib.request

CHANNELS = ["mtp4tg"]
PAGES_PER_CHANNEL = 5        # each page of the web preview holds about 20 posts
MAX_PROXIES = 100            # the app checks at most 100
USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36"

LINK_RE = re.compile(r"""(?:tg://|https?://t\.me/)proxy\?([^"'<>\s]+)""")
POST_ID_RE = re.compile(r'data-post="[^"/]+/(\d+)"')
HOST_RE = re.compile(r"^[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$|^[0-9A-Fa-f:]{2,39}$")
HEX_RE = re.compile(r"^[0-9A-Fa-f]+$")


def fetch(url):
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=20) as response:
        return response.read().decode("utf-8", "replace")


def normalize_secret(secret):
    """The app wants hex. Some links carry the secret in (url-safe) base64."""
    secret = secret.strip()
    if HEX_RE.match(secret) and len(secret) % 2 == 0:
        return secret.lower()
    try:
        padded = secret + "=" * (-len(secret) % 4)
        raw = base64.urlsafe_b64decode(padded.replace("+", "-").replace("/", "_"))
        return raw.hex()
    except Exception:
        return None


def parse_link(query):
    params = urllib.parse.parse_qs(html.unescape(query))
    server = (params.get("server") or params.get("host") or [""])[0].strip()
    secret = normalize_secret((params.get("secret") or [""])[0])
    try:
        port = int((params.get("port") or ["443"])[0])
    except ValueError:
        return None
    # Same rules as ProxyPickerActivity.buildProxy, so the app does not drop them later
    if not server or len(server) > 253 or not HOST_RE.match(server):
        return None
    if not 1 <= port <= 65535:
        return None
    if not secret or not 32 <= len(secret) <= 600:
        return None
    return {"server": server, "port": port, "secret": secret}


def collect(channel):
    proxies = []
    before = None
    for _ in range(PAGES_PER_CHANNEL):
        url = f"https://t.me/s/{channel}" + (f"?before={before}" if before else "")
        try:
            page = fetch(url)
        except Exception as e:
            print(f"{channel}: {url} failed: {e}", file=sys.stderr)
            break
        found = [p for p in (parse_link(q) for q in LINK_RE.findall(page)) if p]
        # Posts on a page go oldest to newest; we want newest first overall
        proxies.extend(reversed(found))
        ids = [int(i) for i in POST_ID_RE.findall(page)]
        if not ids or min(ids) <= 1:
            break
        before = min(ids)
    print(f"{channel}: {len(proxies)} links", file=sys.stderr)
    return proxies


def main():
    output = sys.argv[1] if len(sys.argv) > 1 else "proxies.json"
    channels = sys.argv[2:] or CHANNELS
    result, seen = [], set()
    for channel in channels:
        for proxy in collect(channel):
            key = (proxy["server"].lower(), proxy["port"], proxy["secret"])
            if key not in seen:
                seen.add(key)
                result.append(proxy)
    result = result[:MAX_PROXIES]
    if not result:
        # Keep the previous list rather than publishing an empty one when the channel is unreachable
        print("No proxies found, leaving the file as it is", file=sys.stderr)
        return 1
    with open(output, "w", encoding="utf-8") as f:
        f.write("[\n" + ",\n".join("  " + json.dumps(p, ensure_ascii=False) for p in result) + "\n]\n")
    print(f"Wrote {len(result)} proxies to {output}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
