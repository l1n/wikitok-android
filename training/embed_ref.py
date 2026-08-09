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
