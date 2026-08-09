# WikiTok embedding training

The app's recommender uses a multilingual article embedder trained **from
scratch on Wikipedia itself** — no third-party model weights, no runtime
downloads. Source, training data provenance, and weights are all open:

- **Data**: the head of each language's `pages-articles` dump from
  dumps.wikimedia.org (CC BY-SA), ~100MB of cleaned text per language, plus
  interlanguage-link title pairs fetched from the Wikipedia API.
- **Model**: fastText-style skipgram with negative sampling over a single
  shared hash-bucket subword table (FNV-1a, char 3–5-grams), trained jointly
  on all languages. Cross-lingual alignment comes from tokens shared verbatim
  across wikis plus code-switched sentences synthesized from the title pairs.
- **Inference**: SIF-weighted token averaging with common-component removal,
  int8-quantized table — implemented twice, in `embed_ref.py` (reference) and
  `app/src/main/java/.../WikiEmbedder.kt` (pure Kotlin, no ML runtime), kept
  in lock-step by `HashEmbedderTest` against `testvectors.json`.

## Reproducing

```sh
nix-shell --impure -p "python3.withPackages(ps: [ps.torch-bin ps.requests ps.numpy])"
# NIXPKGS_ALLOW_UNFREE=1 needed for torch-bin (MPS support on macOS)

for l in en es fr de pt it ru ja zh pl; do python fetch_corpus.py --lang $l --megabytes 100; done
for l in es fr de pt it ru ja zh pl;    do python fetch_pairs.py  --lang $l --pairs 10000;   done
python train.py --langs en,es,fr,de,pt,it,ru,ja,zh,pl --dim 96 --buckets 262144 --max-pairs 300000000
python eval.py  --langs en,es,fr,de,pt,it,ru,ja,zh,pl   # alignment sanity check
python export.py --langs en,es,fr,de,pt,it,ru,ja,zh,pl  # writes the app asset + parity vectors
```

Training runs on Apple Silicon via PyTorch MPS (~70k pairs/s at dim 64).
`torch.multinomial` segfaults on MPS, so negative sampling uses the classic
pre-sampled unigram^0.75 table.

## Changing the tokenizer or hashing

`common.py` and `WikiEmbedder.kt` must stay byte-identical in behavior
(tokenization, FNV-1a, n-gram generation and caps, SIF weighting, pooling).
After any change, re-run `export.py` and the `HashEmbedderTest` parity test.

Weights (`app/src/main/assets/wiki_embeddings.bin`) are derived from Wikipedia
text and distributed under CC BY-SA 4.0.
