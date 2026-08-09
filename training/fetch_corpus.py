"""Stream the head of a Wikipedia pages-articles dump into clean plain text.

Reads the multistream bz2 over HTTP incrementally, so grabbing 100MB of clean
text touches only the first few percent of the dump. Wikitext cleanup is crude
but plenty for embedding training.

Usage: python fetch_corpus.py --lang es --megabytes 100
Output: corpus/{lang}.txt (one cleaned article paragraph per line)
"""

import argparse
import bz2
import re
import sys
from pathlib import Path

import requests

UA = "WikiTok-Android-training/1.0 (personal project)"

RE_REDIRECT = re.compile(r"#redirect", re.IGNORECASE)
RE_REF = re.compile(r"<ref[^>]*?/>|<ref[^>]*?>.*?</ref>", re.DOTALL)
RE_COMMENT = re.compile(r"<!--.*?-->", re.DOTALL)
RE_TABLE = re.compile(r"\{\|.*?\|\}", re.DOTALL)
RE_TEMPLATE = re.compile(r"\{\{[^{}]*\}\}")
RE_FILE = re.compile(r"\[\[(?:file|image|archivo|fichier|datei|ficheiro|файл|ファイル|文件|plik)\s*:[^\[\]]*(?:\[\[[^\[\]]*\]\][^\[\]]*)*\]\]", re.IGNORECASE)
RE_LINK_PIPED = re.compile(r"\[\[[^\[\]|]*\|([^\[\]]*)\]\]")
RE_LINK = re.compile(r"\[\[([^\[\]]*)\]\]")
RE_EXTLINK = re.compile(r"\[https?://\S*\s+([^\]]*)\]")
RE_EXTLINK_BARE = re.compile(r"\[?https?://\S+\]?")
RE_TAG = re.compile(r"<[^>]+>")
RE_HEADING = re.compile(r"^=+.*?=+\s*$", re.MULTILINE)
RE_BOLD = re.compile(r"'{2,}")
RE_ENTITY = re.compile(r"&[a-z]+;|&#\d+;")
RE_SPACE = re.compile(r"[ \t]+")


def clean_wikitext(text: str) -> str:
    text = RE_COMMENT.sub(" ", text)
    text = RE_REF.sub(" ", text)
    for _ in range(6):  # nested templates/tables peel one level per pass
        new = RE_TEMPLATE.sub(" ", RE_TABLE.sub(" ", text))
        if new == text:
            break
        text = new
    text = RE_FILE.sub(" ", text)
    text = RE_LINK_PIPED.sub(r"\1", text)
    text = RE_LINK.sub(r"\1", text)
    text = RE_EXTLINK.sub(r"\1", text)
    text = RE_EXTLINK_BARE.sub(" ", text)
    text = RE_TAG.sub(" ", text)
    text = RE_HEADING.sub(" ", text)
    text = RE_BOLD.sub("", text)
    text = RE_ENTITY.sub(" ", text)
    return text


def iter_pages(url: str):
    """Yield (title, wikitext) from the head of a multistream dump."""
    with requests.get(url, stream=True, headers={"User-Agent": UA}, timeout=60) as resp:
        resp.raise_for_status()
        decomp = bz2.BZ2Decompressor()
        buf = ""
        for chunk in resp.iter_content(chunk_size=1 << 20):
            data = b""
            while chunk:
                data += decomp.decompress(chunk)
                if decomp.eof:
                    chunk = decomp.unused_data
                    decomp = bz2.BZ2Decompressor()
                else:
                    chunk = b""
            buf += data.decode("utf-8", errors="replace")
            while True:
                start = buf.find("<page>")
                end = buf.find("</page>")
                if start == -1 or end == -1:
                    if len(buf) > 50_000_000:
                        buf = buf[-1_000_000:]
                    break
                page = buf[start : end + 7]
                buf = buf[end + 7 :]
                tm = re.search(r"<title>(.*?)</title>", page)
                xm = re.search(r"<text[^>]*>(.*?)</text>", page, re.DOTALL)
                if tm and xm:
                    yield tm.group(1), xm.group(1)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--lang", required=True)
    ap.add_argument("--megabytes", type=float, default=100.0)
    ap.add_argument("--out", default=None)
    args = ap.parse_args()

    url = (
        f"https://dumps.wikimedia.org/{args.lang}wiki/latest/"
        f"{args.lang}wiki-latest-pages-articles-multistream.xml.bz2"
    )
    out = Path(args.out or f"corpus/{args.lang}.txt")
    out.parent.mkdir(parents=True, exist_ok=True)
    target = int(args.megabytes * 1_000_000)
    written = 0
    pages = 0
    with out.open("w", encoding="utf-8") as f:
        for title, text in iter_pages(url):
            if ":" in title or RE_REDIRECT.match(text.strip()[:20]):
                continue
            cleaned = clean_wikitext(text)
            for para in cleaned.split("\n"):
                para = RE_SPACE.sub(" ", para).strip()
                if len(para) < 80:
                    continue
                f.write(para + "\n")
                written += len(para.encode("utf-8"))
            pages += 1
            if pages % 2000 == 0:
                print(f"{args.lang}: {pages} pages, {written/1e6:.1f}MB", file=sys.stderr)
            if written >= target:
                break
    print(f"{args.lang}: done — {pages} pages, {written/1e6:.1f}MB → {out}")


if __name__ == "__main__":
    main()
