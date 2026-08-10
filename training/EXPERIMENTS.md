# Embedding improvement program (2026-08-10 → 2026-08-15)

Autonomous daily experiment loop to improve the recommender's embedding model.
One experiment per day; results logged here; better-than-baseline models ship.

## Metric

`python eval.py --langs en,es,fr,de,pt,it,ru,ja,zh,pl` — the **aligned-vs-shuffled
cosine gap** per language (aligned mean − shuffled mean), plus probe sanity.
Primary target: raise cross-script gaps (ru/ja/zh, currently ≈ 0) without
dropping Latin gaps (currently ≈ +0.52…+0.56).

Score a shipped candidate with `eval.py --asset` (PQ + trimmed freqs applied).

## Shipping gate

Ship a new model (export → parity test → version bump → tag) only if:
- mean Latin gap within 0.03 of current best, AND
- mean cross-script gap improves by ≥ 0.05, or Latin mean improves ≥ 0.03.

## Protocol per session

1. Read this file. Pick the top unclaimed backlog item; mark it `[running]`.
2. Implement minimally, retrain (detached, `--max-pairs 300M` unless noted),
   eval, and append a Results row. Restore `models/joint.npz` from
   `models/best.npz` if the experiment regressed; else update `models/best.npz`.
3. If the shipping gate passes: export, run `:app:testDebugUnitTest`, bump
   versionCode/versionName, commit, tag, verify release, update this file.
4. Never run two trainings at once (`pgrep -f "train.py --langs"` first).

## Baseline (v1.6.0 weights + PQ export, 2026-08-10)

| lang | gap |
|------|-----|
| es 0.508 · fr 0.550 · de 0.537 · pt 0.535 · it 0.530 · pl 0.532 | Latin mean ≈ 0.53 |
| ru 0.102 · ja 0.054 · zh 0.053 | cross-script mean ≈ 0.07 |

(ru/ja/zh gaps computed as aligned−shuffled from eval output; near-noise.)

## Backlog (ordered)

1. `[running 2026-08-10]` **Repeated pairs**: PAIR_REPEAT 60 for ru/ja/zh
   (was 10). Cheapest possible cross-script boost; running now.
2. **Transliteration bridge** (arXiv:2406.19759 "Breaking the Script Barrier"):
   append romanized forms of ru (ISO-9) and ja (kana→romaji) tokens as extra
   anchor tokens in code-switch sentences, giving CJK/Cyrillic shared-subword
   anchors with Latin. Needs a small pure-Python transliterator (no deps).
3. **Procrustes post-alignment per script** (MUSE, arXiv:1710.04087): can't
   rotate a shared table per-language — but CAN learn a rotation applied at
   *inference* per language (store per-lang D×D matrix in asset v4, ~36KB each;
   Kotlin matmul). Train rotation on title-pair embeddings (closed form SVD).
   This sidesteps the shared-table limitation entirely and is likely the
   biggest cross-script win.
4. **Alignment fine-tune phase**: after main SGNS, freeze table except a final
   2×-repeated pass over ONLY code-switch pairs at low lr.
5. **Hard negatives in SGNS** (cf. Conan-embedding, arXiv:2408.15710): sample
   half the negatives from the same-language high-frequency band instead of
   the global unigram table — sharpens within-language topical structure.
6. **More data**: 250MB/lang corpora (fetch cost ~2h; training 1.5 epochs).
7. **nanoGPT-style tiny transformer encoder** (2 layers, dim 96, mean-pooled,
   SimCSE-ish objective on intro paragraphs + title pairs): would replace the
   bag-of-subwords entirely; needs Kotlin matmul inference (~150 lines) and a
   full training day. High risk / high ceiling; attempt only if 2–5 plateau.

## Results

| date | experiment | Latin mean gap | cross-script mean gap | shipped? |
|------|-----------|---------------|----------------------|----------|
| 2026-08-10 | baseline (v1.6.0 + PQ) | 0.53 | 0.07 | v1.6.0 |
