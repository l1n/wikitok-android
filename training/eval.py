"""Sanity checks for a trained model.

1. Cross-lingual alignment: cosine(title, its English translation) versus a
   shuffled baseline, from held-out interlanguage pairs.
2. Nearest-neighbor spot checks for a few probe words.

Usage: python eval.py --langs en,es,ja
"""

import argparse
import sys

import numpy as np

from embed_ref import RefEmbedder, compute_mean, load_freqs


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--langs", required=True)
    ap.add_argument("--sample", type=int, default=2000)
    ap.add_argument("--asset", default=None, help="score an exported asset instead of models/joint.npz")
    ap.add_argument("--rotations", default=None, help="apply models/rotations.npz at inference")
    args = ap.parse_args()
    langs = args.langs.split(",")

    if args.asset:
        from embed_ref import load_asset
        table, buckets, freqs, totals, mean, rotations = load_asset(args.asset)
        emb = RefEmbedder(table, buckets, freqs, totals, mean=mean)
        emb.rotations = rotations
        print(f"asset loaded: {args.asset} (rotations: {sorted(rotations)})", file=sys.stderr)
    else:
        m = np.load("models/joint.npz")
        table, buckets = m["table"], int(m["buckets"])
        freqs, totals = load_freqs(langs)
        emb = RefEmbedder(table, buckets, freqs, totals)
        emb.mean = compute_mean(emb, langs)
        print("common-component mean computed", file=sys.stderr)
    if args.rotations:
        rots = np.load(args.rotations)
        emb.rotations = {k: rots[k] for k in rots.files}
        print(f"rotations applied: {sorted(emb.rotations)}", file=sys.stderr)

    rng = np.random.default_rng(7)
    for lang in langs:
        if lang == "en":
            continue
        try:
            rows = [r.split("\t") for r in open(f"pairs/{lang}.tsv", encoding="utf-8").read().splitlines()]
        except FileNotFoundError:
            continue
        rows = [r for r in rows if len(r) == 2][-args.sample:]  # tail = unused by training? (repeat-aug uses all; still indicative)
        if not rows:
            continue
        a = np.stack([emb.embed(r[0], lang) for r in rows])
        b = np.stack([emb.embed(r[1], "en") for r in rows])
        aligned = (a * b).sum(axis=1)
        shuffled = (a * b[rng.permutation(len(b))]).sum(axis=1)
        print(
            f"{lang}->en: aligned cos {aligned.mean():.3f} (median {np.median(aligned):.3f}) "
            f"vs shuffled {shuffled.mean():.3f}  [n={len(rows)}]"
        )

    probes = {
        "en": ["physics", "basketball", "opera", "volcano"],
        "es": ["física", "baloncesto"],
        "ja": ["物理学", "バスケットボール"],
    }
    en_words = ["physics", "chemistry", "basketball", "football", "opera", "painting",
                "volcano", "river", "spacecraft", "astronaut", "election", "virus"]
    en_vecs = np.stack([emb.embed(w, "en") for w in en_words])
    for lang, words in probes.items():
        if lang not in langs:
            continue
        for w in words:
            v = emb.embed(w, lang)
            sims = en_vecs @ v
            top = np.argsort(-sims)[:3]
            nn = ", ".join(f"{en_words[i]}:{sims[i]:.2f}" for i in top)
            print(f"  {lang}:{w} -> {nn}", file=sys.stderr)


if __name__ == "__main__":
    main()
