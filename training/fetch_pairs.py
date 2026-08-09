"""Fetch (foreign title, english title) pairs via Wikipedia's langlinks API.

These interlanguage links are the supervision for cross-lingual alignment:
train.py swaps translated titles into sentences (code-switching augmentation),
and eval.py measures whether translations end up near each other.

Usage: python fetch_pairs.py --lang es --pairs 10000
Output: pairs/{lang}.tsv
"""

import argparse
import sys
import time
from pathlib import Path

import requests

UA = "WikiTok-Android-training/1.0 (personal project)"


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--lang", required=True)
    ap.add_argument("--pairs", type=int, default=10000)
    args = ap.parse_args()

    out = Path(f"pairs/{args.lang}.tsv")
    out.parent.mkdir(parents=True, exist_ok=True)
    session = requests.Session()
    session.headers["User-Agent"] = UA
    url = f"https://{args.lang}.wikipedia.org/w/api.php"

    seen: set[str] = set()
    rows: list[str] = []
    batches = 0
    backoff = 5.0
    while len(rows) < args.pairs and batches < 120:
        batches += 1
        resp = session.get(
            url,
            params={
                "action": "query",
                "format": "json",
                "generator": "random",
                "grnnamespace": "0",
                "grnlimit": "500",
                "prop": "langlinks",
                "lllang": "en",
                "lllimit": "500",
            },
            timeout=60,
        )
        if resp.status_code == 429:
            print(f"{args.lang}: 429, backing off {backoff:.0f}s", file=sys.stderr)
            time.sleep(backoff)
            backoff = min(backoff * 2, 120)
            batches -= 1
            continue
        backoff = 5.0
        resp.raise_for_status()
        pages = resp.json().get("query", {}).get("pages", {})
        for page in pages.values():
            title = page.get("title", "")
            links = page.get("langlinks", [])
            if not title or not links or title in seen:
                continue
            en = links[0].get("*", "")
            if not en or "\t" in title or "\t" in en:
                continue
            seen.add(title)
            rows.append(f"{title}\t{en}")
        print(f"{args.lang}: batch {batches}, {len(rows)} pairs", file=sys.stderr)
        time.sleep(0.5)

    out.write_text("\n".join(rows) + "\n", encoding="utf-8")
    print(f"{args.lang}: {len(rows)} pairs → {out}")


if __name__ == "__main__":
    main()
