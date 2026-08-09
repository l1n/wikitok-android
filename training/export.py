"""Quantize the trained table and write the app asset + parity test vectors.

Output format (big-endian, read by HashEmbedder.kt):
  magic  "WKEM"
  u32    version (2)
  u32    dim
  u32    buckets
  f32[dim]            common-component mean (subtracted post-normalization)
  f32[buckets]        per-bucket quantization scale
  i8[buckets*dim]     quantized table (row-major)
  u32    langCount
  per language:
    u8     code length, utf8 code
    u64    total token count
    u32    table size
    per token: u16 utf8 length, utf8 bytes, u64 count

Usage: python export.py --langs en,es,ja
Writes ../app/src/main/assets/wiki_embeddings.bin
   and ../app/src/test/resources/testvectors.json
"""

import argparse
import json
import struct
from pathlib import Path

import numpy as np

from embed_ref import RefEmbedder, compute_mean, load_freqs

TEST_TEXTS = [
    ("en", "Apollo 11"),
    ("en", "Apollo 11 was the American spaceflight that first landed humans on the Moon."),
    ("en", "Neil Armstrong was an American astronaut."),
    ("en", "The mitochondria is the powerhouse of the cell."),
    ("es", "El Sr. Frío es un supervillano de DC Comics."),
    ("es", "La física cuántica estudia los fenómenos a escala atómica."),
    ("ja", "物理学は自然科学の一分野である。"),
    ("ja", "名古屋記念は地方競馬の重賞競走である。"),
    ("en", ""),
    ("en", "42"),
    ("de", "Zusammengesetztes deutsches Wort ohne Frequenztabelle"),
]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--langs", required=True)
    args = ap.parse_args()
    langs = args.langs.split(",")

    m = np.load("models/joint.npz")
    table = m["table"].astype(np.float32)
    dim, buckets = int(m["dim"]), int(m["buckets"])
    freqs, totals = load_freqs(langs)

    scales = np.abs(table).max(axis=1) / 127.0
    scales[scales == 0] = 1.0
    q = np.clip(np.round(table / scales[:, None]), -127, 127).astype(np.int8)
    dq = q.astype(np.float32) * scales[:, None]

    # Common component from the quantized table (what the app will see)
    pre = RefEmbedder(dq, buckets, freqs, totals)
    mean = compute_mean(pre, langs)

    out = Path("../app/src/main/assets/wiki_embeddings.bin")
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("wb") as f:
        f.write(b"WKEM")
        f.write(struct.pack(">III", 2, dim, buckets))
        f.write(mean.astype(">f4").tobytes())
        f.write(scales.astype(">f4").tobytes())
        f.write(q.tobytes())
        f.write(struct.pack(">I", len(langs)))
        for lang in langs:
            code = lang.encode("utf-8")
            f.write(struct.pack(">B", len(code)))
            f.write(code)
            f.write(struct.pack(">Q", totals.get(lang, 0)))
            items = list(freqs.get(lang, {}).items())
            f.write(struct.pack(">I", len(items)))
            for tok, cnt in items:
                tb = tok.encode("utf-8")
                f.write(struct.pack(">H", len(tb)))
                f.write(tb)
                f.write(struct.pack(">Q", cnt))
    print(f"asset: {out} ({out.stat().st_size/1e6:.1f}MB)")

    # Parity vectors computed against the QUANTIZED table
    emb = RefEmbedder(dq, buckets, freqs, totals, mean=mean)
    vectors = [
        {"lang": lang, "text": text, "vector": [round(float(x), 6) for x in emb.embed(text, lang)]}
        for lang, text in TEST_TEXTS
    ]
    tv = Path("../app/src/test/resources/testvectors.json")
    tv.parent.mkdir(parents=True, exist_ok=True)
    tv.write_text(json.dumps(vectors, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"test vectors: {tv}")


if __name__ == "__main__":
    main()
