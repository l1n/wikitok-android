"""Joint multilingual skipgram-negative-sampling over a shared hash-bucket
subword table (fastText-style input, word-level output vocab).

Cross-lingual alignment comes from (a) tokens shared verbatim across wikis
(names, numbers, loanwords) and (b) code-switched sentences synthesized from
interlanguage title pairs (pairs/{lang}.tsv).

Usage:
  python train.py --langs en,es,ja --dim 64 --buckets 65536 --max-pairs 20000000
Output:
  models/joint.npz            bucket embedding table (float32)
  models/freq_{lang}.tsv      per-language token counts for SIF (top 60k)
"""

import argparse
import math
import random
import sys
import time
from collections import Counter
from pathlib import Path

import numpy as np
import torch

from common import MAX_NGRAMS, token_buckets, tokenize

SUBSAMPLE_T = 1e-4
WINDOW = 5
NEGATIVES = 5
MIN_COUNT = 5
PAIR_REPEAT = 10  # each title pair becomes this many code-switch sentences


def load_sentences(langs: list[str]) -> tuple[list[list[str]], dict[str, Counter], dict[str, int]]:
    sentences: list[list[str]] = []
    lang_counts: dict[str, Counter] = {}
    lang_totals: dict[str, int] = {}
    for lang in langs:
        counts: Counter = Counter()
        total = 0
        path = Path(f"corpus/{lang}.txt")
        for line in path.open(encoding="utf-8"):
            toks = tokenize(line)
            if len(toks) < 5:
                continue
            sentences.append(toks)
            counts.update(toks)
            total += len(toks)
        lang_counts[lang] = counts
        lang_totals[lang] = total
        print(f"{lang}: {total/1e6:.1f}M tokens", file=sys.stderr)
        # Code-switched sentences from title pairs
        pair_path = Path(f"pairs/{lang}.tsv")
        if lang != "en" and pair_path.exists():
            n = 0
            for row in pair_path.open(encoding="utf-8"):
                parts = row.rstrip("\n").split("\t")
                if len(parts) != 2:
                    continue
                a, b = tokenize(parts[0]), tokenize(parts[1])
                # CJK titles tokenize to one char per token — allow longer titles
                if not a or not b or len(a) > 12 or len(b) > 12:
                    continue
                for _ in range(PAIR_REPEAT):
                    sentences.append(a + b if random.random() < 0.5 else b + a)
                n += 1
            print(f"{lang}: {n} title pairs for code-switching", file=sys.stderr)
    random.shuffle(sentences)
    return sentences, lang_counts, lang_totals


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--langs", required=True)
    ap.add_argument("--dim", type=int, default=96)
    ap.add_argument("--buckets", type=int, default=262144)
    ap.add_argument("--vocab", type=int, default=150000)
    ap.add_argument("--max-pairs", type=int, default=200_000_000)
    ap.add_argument("--batch", type=int, default=8192)
    ap.add_argument("--lr", type=float, default=3e-3)
    ap.add_argument("--seed", type=int, default=42)
    args = ap.parse_args()
    random.seed(args.seed)
    np.random.seed(args.seed)
    torch.manual_seed(args.seed)

    langs = args.langs.split(",")
    device = "mps" if torch.backends.mps.is_available() else "cpu"
    print(f"device={device}", file=sys.stderr)

    sentences, lang_counts, lang_totals = load_sentences(langs)

    merged: Counter = Counter()
    for c in lang_counts.values():
        merged.update(c)
    vocab = [t for t, c in merged.most_common(args.vocab) if c >= MIN_COUNT]
    vid = {t: i for i, t in enumerate(vocab)}
    counts = np.array([merged[t] for t in vocab], dtype=np.float64)
    total_tokens = counts.sum()
    print(f"vocab={len(vocab)}", file=sys.stderr)

    # Precomputed subword buckets per vocab word, padded to MAX_NGRAMS
    vb = np.zeros((len(vocab), MAX_NGRAMS), dtype=np.int64)
    vb_mask = np.zeros((len(vocab), MAX_NGRAMS), dtype=np.float32)
    for i, t in enumerate(vocab):
        ids = token_buckets(t, args.buckets)
        vb[i, : len(ids)] = ids
        vb_mask[i, : len(ids)] = 1.0
    vb_t = torch.from_numpy(vb).to(device)
    vb_mask_t = torch.from_numpy(vb_mask).unsqueeze(-1).to(device)
    vb_n = vb_mask_t.sum(dim=1).clamp(min=1.0)

    # Subsample keep probability + negative sampling table
    freq = counts / total_tokens
    keep = np.minimum(1.0, np.sqrt(SUBSAMPLE_T / freq) + SUBSAMPLE_T / freq)
    # torch.multinomial segfaults on MPS (torch 2.11); use the classic word2vec
    # trick instead: a big pre-sampled unigram^0.75 table + uniform indexing.
    neg_weights = counts**0.75
    neg_lookup = np.random.choice(
        len(vocab), size=10_000_000, p=neg_weights / neg_weights.sum()
    ).astype(np.int64)

    bucket_emb = torch.nn.Embedding(args.buckets, args.dim, device=device)
    torch.nn.init.uniform_(bucket_emb.weight, -0.5 / args.dim, 0.5 / args.dim)
    out_emb = torch.nn.Embedding(len(vocab), args.dim, device=device)
    torch.nn.init.zeros_(out_emb.weight)
    opt = torch.optim.Adam(list(bucket_emb.parameters()) + list(out_emb.parameters()), lr=args.lr)

    def gen_pairs():
        """Yield (center_vid, ctx_vid) numpy chunks."""
        centers, ctxs = [], []
        for toks in sentences:
            ids = [vid[t] for t in toks if t in vid]
            ids = [i for i in ids if random.random() < keep[i]]
            n = len(ids)
            for j, c in enumerate(ids):
                w = random.randint(1, WINDOW)
                for k in range(max(0, j - w), min(n, j + w + 1)):
                    if k != j:
                        centers.append(c)
                        ctxs.append(ids[k])
            if len(centers) >= 2_000_000:
                yield np.array(centers), np.array(ctxs)
                centers, ctxs = [], []
        if centers:
            yield np.array(centers), np.array(ctxs)

    logsig = torch.nn.functional.logsigmoid
    seen_pairs = 0
    step = 0
    t0 = time.time()
    done = False
    for cen_np, ctx_np in gen_pairs():
        perm = np.random.permutation(len(cen_np))
        cen_np, ctx_np = cen_np[perm], ctx_np[perm]
        for off in range(0, len(cen_np), args.batch):
            cen = torch.from_numpy(cen_np[off : off + args.batch]).to(device)
            ctx = torch.from_numpy(ctx_np[off : off + args.batch]).to(device)
            b = len(cen)
            emb = (bucket_emb(vb_t[cen]) * vb_mask_t[cen]).sum(dim=1) / vb_n[cen]
            pos = (emb * out_emb(ctx)).sum(dim=-1)
            neg_ids = torch.from_numpy(
                neg_lookup[np.random.randint(0, len(neg_lookup), size=b * NEGATIVES)]
            ).view(b, NEGATIVES).to(device)
            neg = torch.bmm(out_emb(neg_ids), emb.unsqueeze(-1)).squeeze(-1)
            loss = -(logsig(pos).mean() + logsig(-neg).mean() * NEGATIVES)
            opt.zero_grad(set_to_none=True)
            loss.backward()
            opt.step()
            step += 1
            seen_pairs += b
            if step % 200 == 0:
                rate = seen_pairs / (time.time() - t0)
                print(
                    f"step {step} pairs {seen_pairs/1e6:.1f}M loss {loss.item():.4f} "
                    f"({rate/1e3:.0f}k pairs/s)",
                    file=sys.stderr,
                )
            if seen_pairs >= args.max_pairs:
                done = True
                break
        if done:
            break

    Path("models").mkdir(exist_ok=True)
    np.savez_compressed(
        "models/joint.npz",
        table=bucket_emb.weight.detach().cpu().numpy().astype(np.float32),
        dim=args.dim,
        buckets=args.buckets,
    )
    for lang in langs:
        with open(f"models/freq_{lang}.tsv", "w", encoding="utf-8") as f:
            f.write(f"__total__\t{lang_totals[lang]}\n")
            for t, c in lang_counts[lang].most_common(60000):
                if c >= MIN_COUNT:
                    f.write(f"{t}\t{c}\n")
    print(f"done: {seen_pairs/1e6:.1f}M pairs in {(time.time()-t0)/60:.1f} min")


if __name__ == "__main__":
    main()
