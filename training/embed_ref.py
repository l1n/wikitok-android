"""Reference SIF embedding used by eval.py and export.py — HashEmbedder.kt
must produce identical vectors (against the quantized table)."""

import numpy as np

from common import sif_weight, token_buckets, tokenize


class RefEmbedder:
    def __init__(
        self,
        table: np.ndarray,
        buckets: int,
        freqs: dict[str, dict[str, int]],
        totals: dict[str, int],
        mean: np.ndarray | None = None,
    ):
        self.table = table  # [buckets, dim] float32 (possibly dequantized int8)
        self.buckets = buckets
        self.freqs = freqs
        self.totals = totals
        self.mean = mean  # common-component vector subtracted post-normalization
        self.rotations: dict[str, np.ndarray] = {}  # per-lang Procrustes into en space

    def token_vec(self, token: str) -> np.ndarray:
        ids = token_buckets(token, self.buckets)
        return self.table[ids].mean(axis=0)

    def embed(self, text: str, lang: str) -> np.ndarray:
        toks = tokenize(text)
        if not toks:
            return np.zeros(self.table.shape[1], dtype=np.float32)
        freqs = self.freqs.get(lang, {})
        total = self.totals.get(lang, 0)
        acc = np.zeros(self.table.shape[1], dtype=np.float64)
        wsum = 0.0
        for t in toks:
            w = sif_weight(freqs.get(t, 0), total)
            acc += w * self.token_vec(t)
            wsum += w
        v = acc / max(wsum, 1e-9)
        norm = np.linalg.norm(v)
        v = v / max(norm, 1e-9)
        if self.mean is not None:
            v = v - self.mean
            norm = np.linalg.norm(v)
            v = v / max(norm, 1e-9)
        w = self.rotations.get(lang)
        if w is not None:
            v = v @ w
        return v.astype(np.float32)


def compute_mean(embedder: "RefEmbedder", langs: list[str], per_lang: int = 5000) -> np.ndarray:
    """Mean of normalized sentence vectors over a corpus sample — the common
    component removed at inference (anisotropy fix)."""
    vecs = []
    for lang in langs:
        try:
            with open(f"corpus/{lang}.txt", encoding="utf-8") as f:
                for i, line in enumerate(f):
                    if i >= per_lang:
                        break
                    if len(line) > 40:
                        vecs.append(embedder.embed(line[:300], lang))
        except FileNotFoundError:
            continue
    return np.stack(vecs).mean(axis=0).astype(np.float32)


def load_asset(path: str):
    """Parse the v3 app asset back into (RefEmbedder-ready pieces) — used by
    eval.py --asset to score exactly what ships."""
    import struct

    with open(path, "rb") as f:
        data = f.read()
    off = 0

    def rd(fmt):
        nonlocal off
        vals = struct.unpack_from(fmt, data, off)
        off += struct.calcsize(fmt)
        return vals if len(vals) > 1 else vals[0]

    assert data[:4] == b"WKEM"
    off = 4
    version, dim, buckets, n_sub, k = rd(">IIIII")
    assert version == 4, version
    ds = dim // n_sub
    mean = np.frombuffer(data, ">f4", dim, off).astype(np.float32); off += 4 * dim
    codebooks = np.frombuffer(data, ">f4", n_sub * k * ds, off).astype(np.float32).reshape(n_sub, k, ds); off += 4 * n_sub * k * ds
    norms = np.frombuffer(data, ">f4", buckets, off).astype(np.float32); off += 4 * buckets
    codes = np.frombuffer(data, np.uint8, buckets * n_sub, off).reshape(buckets, n_sub); off += buckets * n_sub
    table = np.zeros((buckets, dim), dtype=np.float32)
    for s in range(n_sub):
        table[:, s * ds : (s + 1) * ds] = codebooks[s][codes[:, s]]
    table *= norms[:, None]
    lang_count = rd(">I")
    freqs, totals = {}, {}
    for _ in range(lang_count):
        (n,) = struct.unpack_from(">B", data, off); off += 1
        code = data[off : off + n].decode("utf-8"); off += n
        totals[code] = rd(">Q")
        cnt = rd(">I")
        m = {}
        for _ in range(cnt):
            (tl,) = struct.unpack_from(">H", data, off); off += 2
            tok = data[off : off + tl].decode("utf-8"); off += tl
            m[tok] = rd(">I")
        freqs[code] = m
    rotations = {}
    rot_count = rd(">I")
    for _ in range(rot_count):
        (n,) = struct.unpack_from(">B", data, off); off += 1
        code = data[off : off + n].decode("utf-8"); off += n
        rotations[code] = np.frombuffer(data, ">f4", dim * dim, off).astype(np.float32).reshape(dim, dim)
        off += 4 * dim * dim
    return table, buckets, freqs, totals, mean, rotations


def load_freqs(langs: list[str]) -> tuple[dict, dict]:
    freqs: dict[str, dict[str, int]] = {}
    totals: dict[str, int] = {}
    for lang in langs:
        table: dict[str, int] = {}
        with open(f"models/freq_{lang}.tsv", encoding="utf-8") as f:
            for line in f:
                tok, cnt = line.rstrip("\n").split("\t")
                if tok == "__total__":
                    totals[lang] = int(cnt)
                else:
                    table[tok] = int(cnt)
        freqs[lang] = table
    return freqs, totals
