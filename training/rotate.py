"""Per-language orthogonal Procrustes rotations into English space (MUSE-style,
arXiv:1710.04087), fitted on final sentence vectors of interlanguage title pairs.

Fits on pairs[:-HOLDOUT] only — eval.py scores the tail, so the eval stays
honest. Orthogonality preserves within-language geometry exactly; only
cross-language comparisons change.

Usage: python rotate.py --langs en,es,...   (reads models/joint.npz + pairs/)
Output: models/rotations.npz  ({lang: [D,D]})
"""

import argparse

import numpy as np

from embed_ref import RefEmbedder, compute_mean, load_freqs

HOLDOUT = 2000  # matches eval.py --sample default


def fit_rotations(emb: RefEmbedder, langs: list[str]) -> dict[str, np.ndarray]:
    """Orthogonal Procrustes per language on pairs[:-HOLDOUT] (eval-honest)."""
    rotations: dict[str, np.ndarray] = {}
    for lang in langs:
        if lang == "en":
            continue
        try:
            rows = [r.split("\t") for r in open(f"pairs/{lang}.tsv", encoding="utf-8").read().splitlines()]
        except FileNotFoundError:
            continue
        rows = [r for r in rows if len(r) == 2][:-HOLDOUT]
        if len(rows) < 500:
            print(f"{lang}: only {len(rows)} training pairs, skipping")
            continue
        x = np.stack([emb.embed(r[0], lang) for r in rows])
        y = np.stack([emb.embed(r[1], "en") for r in rows])
        u, _, vt = np.linalg.svd(x.T @ y)
        w = (u @ vt).astype(np.float32)
        rotations[lang] = w
        fit = float(((x @ w) * y).sum(1).mean())
        base = float((x * y).sum(1).mean())
        print(f"{lang}: train-pair cos {base:.3f} -> {fit:.3f}  [n={len(rows)}]")
    return rotations


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--langs", required=True)
    args = ap.parse_args()
    langs = args.langs.split(",")

    m = np.load("models/joint.npz")
    table, buckets = m["table"], int(m["buckets"])
    freqs, totals = load_freqs(langs)
    emb = RefEmbedder(table, buckets, freqs, totals)
    emb.mean = compute_mean(emb, langs)

    rotations = fit_rotations(emb, langs)
    np.savez_compressed("models/rotations.npz", **rotations)
    print(f"saved {len(rotations)} rotations")


if __name__ == "__main__":
    main()
