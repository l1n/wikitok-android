"""Quantize the trained table and write the app asset + parity test vectors.

Output format v3 (big-endian, read by HashEmbedder.kt):
  magic  "WKEM"
  u32    version (3)
  u32    dim
  u32    buckets
  u32    nSub          product-quantization subvectors (dim % nSub == 0)
  u32    k             centroids per subquantizer (256)
  f32[dim]             common-component mean (subtracted post-normalization)
  f32[nSub*k*(dim/nSub)]  codebooks
  f32[buckets]         per-bucket scale (the vector norm)
  u8[buckets*nSub]     PQ codes (row-major)
  u32    langCount
  per language:
    u8     code length, utf8 code
    u64    total token count
    u32    table size
    per token: u16 utf8 length, utf8 bytes, u32 count

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

PQ_SUB = 12
PQ_K = 256
FREQ_TOP = 20000


def pq_train(x: np.ndarray, n_sub: int, k: int, iters: int = 15, seed: int = 7):
    """Lloyd's k-means per subvector. Returns codebooks [n_sub,k,ds], codes [B,n_sub]."""
    rng = np.random.default_rng(seed)
    b, d = x.shape
    ds = d // n_sub
    codebooks = np.zeros((n_sub, k, ds), dtype=np.float32)
    codes = np.zeros((b, n_sub), dtype=np.uint8)
    for s in range(n_sub):
        sub = np.ascontiguousarray(x[:, s * ds : (s + 1) * ds])
        cent = sub[rng.choice(b, k, replace=False)].copy()
        assign = np.zeros(b, dtype=np.int64)
        for _ in range(iters):
            dist = (sub**2).sum(1, keepdims=True) - 2 * sub @ cent.T + (cent**2).sum(1)
            assign = dist.argmin(1)
            sums = np.zeros_like(cent)
            np.add.at(sums, assign, sub)
            counts = np.bincount(assign, minlength=k).astype(np.float32)
            dead = counts == 0
            counts[dead] = 1
            cent = sums / counts[:, None]
            if dead.any():
                cent[dead] = sub[rng.choice(b, int(dead.sum()), replace=False)]
        codebooks[s] = cent
        codes[:, s] = assign.astype(np.uint8)
        err = float(np.mean(np.linalg.norm(sub - cent[assign], axis=1)))
        print(f"  pq sub {s}: mean residual {err:.4f}")
    return codebooks, codes


def pq_reconstruct(codebooks: np.ndarray, codes: np.ndarray) -> np.ndarray:
    n_sub, _, ds = codebooks.shape
    b = codes.shape[0]
    out = np.zeros((b, n_sub * ds), dtype=np.float32)
    for s in range(n_sub):
        out[:, s * ds : (s + 1) * ds] = codebooks[s][codes[:, s]]
    return out

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
    ap.add_argument("--rotations", default="models/rotations.npz")
    args = ap.parse_args()
    langs = args.langs.split(",")
    rotations = {}
    if Path(args.rotations).exists():
        r = np.load(args.rotations)
        rotations = {k: r[k].astype(np.float32) for k in r.files}
        print(f"rotations: {sorted(rotations)}")

    m = np.load("models/joint.npz")
    table = m["table"].astype(np.float32)
    dim, buckets = int(m["dim"]), int(m["buckets"])
    freqs, totals = load_freqs(langs)

    # PQ on unit vectors; the norm rides along as the per-bucket scale
    norms = np.linalg.norm(table, axis=1)
    norms[norms == 0] = 1.0
    units = table / norms[:, None]
    codebooks, codes = pq_train(units, PQ_SUB, PQ_K)
    dq = pq_reconstruct(codebooks, codes) * norms[:, None]
    cos = (dq * table).sum(1) / (np.linalg.norm(dq, axis=1) * np.linalg.norm(table, axis=1) + 1e-9)
    print(f"pq reconstruction: mean cosine {cos.mean():.4f} (p5 {np.percentile(cos, 5):.4f})")

    # Trim frequency tables first: mean/testvectors must see the app's view
    freqs = {
        lang: dict(sorted(freqs.get(lang, {}).items(), key=lambda kv: -kv[1])[:FREQ_TOP])
        for lang in langs
    }

    # Common component from the quantized table (what the app will see)
    pre = RefEmbedder(dq, buckets, freqs, totals)
    mean = compute_mean(pre, langs)

    out = Path("../app/src/main/assets/wiki_embeddings.bin")
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("wb") as f:
        f.write(b"WKEM")
        f.write(struct.pack(">IIIII", 4, dim, buckets, PQ_SUB, PQ_K))
        f.write(mean.astype(">f4").tobytes())
        f.write(codebooks.astype(">f4").tobytes())
        f.write(norms.astype(">f4").tobytes())
        f.write(codes.tobytes())
        f.write(struct.pack(">I", len(langs)))
        for lang in langs:
            code = lang.encode("utf-8")
            f.write(struct.pack(">B", len(code)))
            f.write(code)
            f.write(struct.pack(">Q", totals.get(lang, 0)))
            items = sorted(freqs.get(lang, {}).items(), key=lambda kv: -kv[1])[:FREQ_TOP]
            f.write(struct.pack(">I", len(items)))
            for tok, cnt in items:
                tb = tok.encode("utf-8")
                f.write(struct.pack(">H", len(tb)))
                f.write(tb)
                f.write(struct.pack(">I", min(cnt, 0xFFFFFFFF)))
        f.write(struct.pack(">I", len(rotations)))
        for lang, w in sorted(rotations.items()):
            code = lang.encode("utf-8")
            f.write(struct.pack(">B", len(code)))
            f.write(code)
            f.write(w.astype(">f4").tobytes())
    print(f"asset: {out} ({out.stat().st_size/1e6:.1f}MB)")

    # Parity vectors computed against the QUANTIZED table
    emb = RefEmbedder(dq, buckets, freqs, totals, mean=mean)
    emb.rotations = rotations
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
