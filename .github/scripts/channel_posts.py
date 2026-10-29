#!/usr/bin/env python3
"""Copy the latest posts of the Тележка channel into the site.

The site must not load anything from Telegram (it is blocked for many of our users),
so a GitHub Action reads the public page t.me/s/<channel> and saves the posts as
channel/posts.json, with photos as channel/img/<id>.jpg, next to index.html.

usage: channel_posts.py <site dir> [saved t.me/s page, for testing without network]
"""
import html
import json
import os
import re
import sys
import urllib.request
from html.parser import HTMLParser

CHANNEL = "teleskaoff"
KEEP = 8  # posts on the site
UA = {"User-Agent": "Mozilla/5.0 (compatible; telezhka-site)"}

# tags allowed in a post's text, everything else is reduced to its text
ALLOWED = {"b", "strong", "i", "em", "u", "s", "del", "code", "pre", "br", "a", "blockquote"}


class Clean(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.out, self.stack, self.emoji = [], [], 0

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        # Telegram draws emoji as pictures from telegram.org; keep the emoji character instead
        if tag == "i" and "emoji" in (a.get("class") or ""):
            self.stack.append(None)
            self.emoji += 1
            return
        if self.emoji or tag not in ALLOWED:
            self.stack.append(None)
            return
        if tag == "br":
            self.out.append("<br>")
            return
        if tag == "a":
            href = a.get("href") or ""
            if not re.match(r"^(https?:|tg:)", href):
                self.stack.append(None)
                return
            self.out.append('<a href="%s" target="_blank" rel="noopener">' % html.escape(href))
        else:
            self.out.append("<%s>" % tag)
        self.stack.append(tag)

    def handle_endtag(self, tag):
        if tag == "br" or not self.stack:
            return
        top = self.stack.pop()
        if top is None:
            if tag == "i" and self.emoji:
                self.emoji -= 1
            return
        self.out.append("</%s>" % top)

    def handle_data(self, data):
        self.out.append(html.escape(data))


def clean(fragment):
    p = Clean()
    p.feed(fragment)
    return "".join(p.out).strip()


def inner_div(s, start):
    """HTML inside the <div> that opens at s[start], counting nested divs."""
    i = s.index(">", start) + 1
    depth, pos = 1, i
    for m in re.finditer(r"<(/?)div\b", s[i:]):
        depth += -1 if m.group(1) else 1
        if depth == 0:
            return s[i:i + m.start()]
    return s[i:]


def get(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=30) as r:
        return r.read()


def parse(page):
    posts = []
    for block in page.split('<div class="tgme_widget_message_wrap')[1:]:
        if "service_message" in block:
            continue
        m = re.search(r'data-post="%s/(\d+)"' % CHANNEL, block)
        if not m:
            continue
        post = {"id": int(m.group(1))}
        t = re.search(r'<div class="tgme_widget_message_text[^"]*"', block)
        post["html"] = clean(inner_div(block, t.start())) if t else ""
        d = re.search(r'<time datetime="([^"]+)"', block)
        post["date"] = d.group(1) if d else ""
        # first photo, or the cover of a video
        img = re.search(r"tgme_widget_message_(?:photo_wrap|video_thumb)[^>]*background-image:url\('([^']+)'\)", block)
        if img:
            post["img_src"] = img.group(1)
            post["video"] = "tgme_widget_message_video" in block
        if post["html"] or img:
            posts.append(post)
    return sorted(posts, key=lambda p: p["id"], reverse=True)[:KEEP]


def main():
    site = sys.argv[1]
    page = open(sys.argv[2], encoding="utf-8").read() if len(sys.argv) > 2 else get("https://t.me/s/%s" % CHANNEL).decode("utf-8")
    posts = parse(page)
    if not posts:
        sys.exit("no posts found, keeping the old file")
    img_dir = os.path.join(site, "channel", "img")
    os.makedirs(img_dir, exist_ok=True)
    keep = set()
    for p in posts:
        src = p.pop("img_src", None)
        if not src:
            continue
        name = "%d.jpg" % p["id"]
        path = os.path.join(img_dir, name)
        if not os.path.exists(path):
            try:
                data = get(src if src.startswith("http") else "https:" + src)
            except Exception as e:  # a missing picture should not drop the post
                print("photo of post %d: %s" % (p["id"], e))
                continue
            open(path, "wb").write(data)
        p["img"] = "channel/img/" + name
        keep.add(name)
    for name in os.listdir(img_dir):
        if name not in keep:
            os.remove(os.path.join(img_dir, name))
    data = {"channel": CHANNEL, "posts": posts}
    with open(os.path.join(site, "channel", "posts.json"), "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=1)
    print("%d posts" % len(posts))


if __name__ == "__main__":
    main()
