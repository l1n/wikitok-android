"""Joint multilingual skipgram-negative-sampling over a shared hash-bucket
subword table — manual sparse backward + one-sided Shampoo, tuned for MPS.

Two phases:
  1. Pair generation: multiprocessing over sentence shards → int32 .npy pair
     shards in models/pairs_cache/ (resumable, GPU never starves).
  2. Training: manual SGNS forward/backward (no autograd, no dense-optimizer
     sweep over the 44M-param tables). Updates touch only the rows a batch
     used, preconditioned by the dim-side Shampoo factor (R = Σ GᵀG, apply
     G·R^{-1/2}), inverse root refreshed on CPU every REFRESH steps.

Usage:
  python train.py --langs en,es,ja --dim 64 --buckets 65536 --max-pairs 20000000
Output: models/joint.npz + models/freq_{lang}.tsv  (same as before)
"""

import argparse
import math
import multiprocessing as mp
import random
import sys
import time
from collections import Counter
from pathlib import Path

import numpy as np
import torch

from common import MAX_NGRAMS, token_buckets, tokenize
from translit import translit_tokens

SUBSAMPLE_T = 1e-4
WINDOW = 5
NEGATIVES = 5
MIN_COUNT = 5
# Cross-script languages get their code-switch pairs repeated much harder:
# Latin languages align through shared tokens; Cyrillic/CJK have no such
# anchors, so the title pairs carry all the supervision.
PAIR_REPEAT = {"ru": 60, "ja": 60, "zh": 60}
PAIR_REPEAT_DEFAULT = 10
SHARD_PAIRS = 5_000_000
REFRESH = 100  # steps between preconditioner refreshes


def load_sentences(langs):
    sentences, lang_counts, lang_totals = [], {}, {}
    for lang in langs:
        counts, total = Counter(), 0
        for line in Path(f"corpus/{lang}.txt").open(encoding="utf-8"):
            toks = tokenize(line)
            if len(toks) < 5:
                continue
            sentences.append(toks)
            counts.update(toks)
            total += len(toks)
        lang_counts[lang], lang_totals[lang] = counts, total
        print(f"{lang}: {total/1e6:.1f}M tokens", file=sys.stderr)
        pair_path = Path(f"pairs/{lang}.tsv")
        if lang != "en" and pair_path.exists():
            n = 0
            for row in pair_path.open(encoding="utf-8"):
                parts = row.rstrip("\n").split("\t")
                if len(parts) != 2:
                    continue
                a, b = tokenize(parts[0]), tokenize(parts[1])
                if not a or not b or len(a) > 12 or len(b) > 12:
                    continue
                # Romanized pivot tokens bridge scripts (arXiv:2406.19759):
                # they share subwords with Latin cognates/loanwords on one side
                # and co-occur with the original script on the other.
                bridge = translit_tokens(lang, a)
                for _ in range(PAIR_REPEAT.get(lang, PAIR_REPEAT_DEFAULT)):
                    parts = [a, bridge, [*b]] if random.random() < 0.5 else [[*b], bridge, a]
                    sentences.append([t for p in parts for t in p if t])
                n += 1
            print(f"{lang}: {n} title pairs for code-switching", file=sys.stderr)
    random.shuffle(sentences)
    return sentences, lang_counts, lang_totals


_G = {}


def _init_worker(sentences, vid, keep):
    _G["sentences"], _G["vid"], _G["keep"] = sentences, vid, keep
    random.seed(mp.current_process().pid)


def _gen_shard(args):
    lo, hi, out_path = args
    vid, keep = _G["vid"], _G["keep"]
    centers, ctxs = [], []
    for toks in _G["sentences"][lo:hi]:
        ids = [vid[t] for t in toks if t in vid]
        ids = [i for i in ids if random.random() < keep[i]]
        n = len(ids)
        for j, c in enumerate(ids):
            w = random.randint(1, WINDOW)
            for k in range(max(0, j - w), min(n, j + w + 1)):
                if k != j:
                    centers.append(c)
                    ctxs.append(ids[k])
    arr = np.stack([np.array(centers, dtype=np.int32), np.array(ctxs, dtype=np.int32)])
    np.save(out_path, arr)
    return out_path, arr.shape[1]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--langs", required=True)
    ap.add_argument("--dim", type=int, default=96)
    ap.add_argument("--buckets", type=int, default=262144)
    ap.add_argument("--vocab", type=int, default=200000)
    ap.add_argument("--max-pairs", type=int, default=300_000_000)
    ap.add_argument("--batch", type=int, default=16384)
    ap.add_argument("--lr", type=float, default=0.03)
    ap.add_argument("--workers", type=int, default=5)
    ap.add_argument("--hard-negs", type=int, default=0,
                    help="in-batch hard negatives per pair (mined from a 2048-ctx pool)")
    ap.add_argument("--seed", type=int, default=42)
    args = ap.parse_args()
    random.seed(args.seed)
    np.random.seed(args.seed)
    torch.manual_seed(args.seed)
    langs = args.langs.split(",")

    sentences, lang_counts, lang_totals = load_sentences(langs)
    merged = Counter()
    for c in lang_counts.values():
        merged.update(c)
    vocab = [t for t, c in merged.most_common(args.vocab) if c >= MIN_COUNT]
    vid = {t: i for i, t in enumerate(vocab)}
    counts = np.array([merged[t] for t in vocab], dtype=np.float64)
    freq = counts / counts.sum()
    keep = np.minimum(1.0, np.sqrt(SUBSAMPLE_T / freq) + SUBSAMPLE_T / freq)
    print(f"vocab={len(vocab)}", file=sys.stderr)

    # ---- Phase 1: pair shards (fork BEFORE touching MPS) ----
    cache = Path("models/pairs_cache")
    cache.mkdir(parents=True, exist_ok=True)
    shards = sorted(cache.glob("shard_*.npy"))
    if not shards:
        n_shards = max(args.workers * 2, math.ceil(len(sentences) / 200_000))
        bounds = np.linspace(0, len(sentences), n_shards + 1, dtype=int)
        jobs = [
            (int(bounds[i]), int(bounds[i + 1]), str(cache / f"shard_{i:03d}.npy"))
            for i in range(n_shards)
        ]
        t0 = time.time()
        ctx = mp.get_context("fork")
        with ctx.Pool(args.workers, _init_worker, (sentences, vid, keep)) as pool:
            total = 0
            for path, n in pool.imap_unordered(_gen_shard, jobs):
                total += n
                print(f"shard {path}: {n/1e6:.1f}M pairs ({total/1e6:.0f}M total)", file=sys.stderr)
        print(f"pair generation: {total/1e6:.0f}M pairs in {(time.time()-t0)/60:.1f} min", file=sys.stderr)
        shards = sorted(cache.glob("shard_*.npy"))
    del sentences

    # ---- Phase 2: training ----
    device = "mps" if torch.backends.mps.is_available() else "cpu"
    print(f"device={device}", file=sys.stderr)
    D, B = args.dim, args.buckets

    vb = np.zeros((len(vocab), MAX_NGRAMS), dtype=np.int64)
    vb_mask = np.zeros((len(vocab), MAX_NGRAMS), dtype=np.float32)
    for i, t in enumerate(vocab):
        ids = token_buckets(t, B)
        vb[i, : len(ids)] = ids
        vb_mask[i, : len(ids)] = 1.0
    vb_t = torch.from_numpy(vb).to(device)
    vb_mask_t = torch.from_numpy(vb_mask).unsqueeze(-1).to(device)
    vb_n = vb_mask_t.sum(dim=1).clamp(min=1.0)

    neg_weights = counts**0.75
    neg_lookup = np.random.choice(len(vocab), size=10_000_000, p=neg_weights / neg_weights.sum()).astype(np.int64)

    w_in = ((torch.rand(B, D, device=device) - 0.5) / D)
    w_out = torch.zeros(len(vocab), D, device=device)
    # f32: MPS has no float64; the tiny eigendecomposition upcasts on CPU
    r_in = torch.zeros(D, D, device=device)
    r_out = torch.zeros(D, D, device=device)
    p_in = torch.eye(D, device=device)
    p_out = torch.eye(D, device=device)

    def inv_sqrt(r: torch.Tensor) -> torch.Tensor:
        m = r.cpu().double()
        m = m / max(seen_pairs, 1)
        eps = 1e-6 * torch.diagonal(m).mean().clamp(min=1e-12)
        vals, vecs = torch.linalg.eigh(m + eps * torch.eye(D, dtype=torch.float64))
        return (vecs @ torch.diag(vals.clamp(min=1e-12).rsqrt()) @ vecs.T).float().to(device)

    seen_pairs = 0
    step = 0
    t0 = time.time()
    done = False
    for shard in shards:
        arr = np.load(shard)
        perm = np.random.permutation(arr.shape[1])
        cen_np, ctx_np = arr[0][perm].astype(np.int64), arr[1][perm].astype(np.int64)
        for off in range(0, len(cen_np), args.batch):
            cen = torch.from_numpy(cen_np[off : off + args.batch]).to(device)
            ctx = torch.from_numpy(ctx_np[off : off + args.batch]).to(device)
            b = len(cen)
            neg = torch.from_numpy(
                neg_lookup[np.random.randint(0, len(neg_lookup), size=b * NEGATIVES)]
            ).view(b, NEGATIVES).to(device)

            cb = vb_t[cen]                      # [b, NG]
            cmask = vb_mask_t[cen]              # [b, NG, 1]
            cn = vb_n[cen]                      # [b, 1]
            e = (w_in[cb] * cmask).sum(1) / cn  # [b, D]
            vc = w_out[ctx]                     # [b, D]
            vn = w_out[neg]                     # [b, K, D]
            s_pos = (e * vc).sum(-1)
            s_neg = torch.bmm(vn, e.unsqueeze(-1)).squeeze(-1)

            a_pos = torch.sigmoid(s_pos) - 1.0          # [b]
            a_neg = torch.sigmoid(s_neg)                # [b, K]
            g_e = a_pos.unsqueeze(-1) * vc + (a_neg.unsqueeze(-1) * vn).sum(1)
            g_ctx = a_pos.unsqueeze(-1) * e             # [b, D]
            g_neg = a_neg.unsqueeze(-1) * e.unsqueeze(1)  # [b, K, D]

            # In-batch hard negatives: most-similar other contexts in a sampled
            # pool. Kept few (vs K random) to bound false-negative damage.
            if args.hard_negs > 0:
                pool_pos = torch.randint(0, b, (min(2048, b),), device=device)
                pool_ids = ctx[pool_pos]
                sim = e @ w_out[pool_ids].T
                sim = sim.masked_fill(pool_ids.unsqueeze(0) == ctx.unsqueeze(1), -1e9)
                hard_ids = pool_ids[sim.topk(args.hard_negs, dim=1).indices]  # [b, H]
                vh = w_out[hard_ids]
                s_hard = torch.bmm(vh, e.unsqueeze(-1)).squeeze(-1)
                a_hard = torch.sigmoid(s_hard)
                g_e = g_e + (a_hard.unsqueeze(-1) * vh).sum(1)
                g_hard = a_hard.unsqueeze(-1) * e.unsqueeze(1)

            r_in += g_e.T @ g_e
            g_out_all = torch.cat([g_ctx, g_neg.reshape(-1, D)])
            r_out += g_out_all.T @ g_out_all

            # Row-mean accumulation: each example's contribution is divided by
            # how many examples touch that row this batch, approximating
            # sequential per-example SGD (plain sums explode on hot buckets,
            # batch-size division starves cold ones).
            scale = args.lr

            def precondition(g: torch.Tensor, p: torch.Tensor) -> torch.Tensor:
                # Shampoo direction, SGD magnitude (row-wise grafting): without
                # this, R^{-1/2} amplifies the tiny early gradients ~100x and
                # the run NaNs right after the first refresh.
                u = g @ p
                gn = g.norm(dim=-1, keepdim=True)
                un = u.norm(dim=-1, keepdim=True).clamp(min=1e-12)
                return u * (gn / un)

            flat_in = cb.reshape(-1)
            # sqrt(count): sum explodes on hot rows, mean starves them; CLT
            # scaling matches sequential-SGD progress within a constant.
            cnt_in = torch.zeros(B, device=device).index_add_(
                0, flat_in, cmask.reshape(-1)
            ).clamp(min=1.0).sqrt()
            g_rows = ((g_e / cn).unsqueeze(1) * cmask).reshape(-1, D)
            w_in.index_add_(
                0, flat_in, -scale * precondition(g_rows, p_in) / cnt_in[flat_in].unsqueeze(-1)
            )

            if args.hard_negs > 0:
                flat_out = torch.cat([ctx, neg.reshape(-1), hard_ids.reshape(-1)])
                g_out = torch.cat([g_ctx, g_neg.reshape(-1, D), g_hard.reshape(-1, D)])
            else:
                flat_out = torch.cat([ctx, neg.reshape(-1)])
                g_out = torch.cat([g_ctx, g_neg.reshape(-1, D)])
            cnt_out = torch.zeros(len(vocab), device=device).index_add_(
                0, flat_out, torch.ones_like(flat_out, dtype=torch.float32)
            ).clamp(min=1.0).sqrt()
            w_out.index_add_(
                0, flat_out, -scale * precondition(g_out, p_out) / cnt_out[flat_out].unsqueeze(-1)
            )

            step += 1
            seen_pairs += b
            if step % REFRESH == 0:
                p_in = inv_sqrt(r_in)
                p_out = inv_sqrt(r_out)
            if step % 200 == 0:
                with torch.no_grad():
                    loss = -(torch.nn.functional.logsigmoid(s_pos).mean()
                             + torch.nn.functional.logsigmoid(-s_neg).mean() * NEGATIVES)
                rate = seen_pairs / (time.time() - t0)
                print(f"step {step} pairs {seen_pairs/1e6:.1f}M loss {loss.item():.4f} "
                      f"({rate/1e3:.0f}k pairs/s)", file=sys.stderr)
            if seen_pairs >= args.max_pairs:
                done = True
                break
        if done:
            break

    Path("models").mkdir(exist_ok=True)
    np.savez_compressed("models/joint.npz",
                        table=w_in.cpu().numpy().astype(np.float32),
                        dim=D, buckets=B)
    for lang in langs:
        with open(f"models/freq_{lang}.tsv", "w", encoding="utf-8") as f:
            f.write(f"__total__\t{lang_totals[lang]}\n")
            for t, c in lang_counts[lang].most_common(60000):
                if c >= MIN_COUNT:
                    f.write(f"{t}\t{c}\n")
    print(f"done: {seen_pairs/1e6:.1f}M pairs in {(time.time()-t0)/60:.1f} min")


if __name__ == "__main__":
    main()
