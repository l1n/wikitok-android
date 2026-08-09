"""Tokenizer + hashing shared by training and export.

CRITICAL: HashEmbedder.kt reimplements everything here byte-for-byte.
Any change must be mirrored there and verified against testvectors.json.
"""

import re

# Matches the app's article text: lowercase, CJK chars isolated, then runs of
# unicode letters/digits.
_CJK = (
    "぀-ヿ"   # hiragana + katakana
    "㐀-䶿"   # CJK ext A
    "一-鿿"   # CJK unified
    "豈-﫿"   # CJK compat
)
_CJK_RE = re.compile(f"([{_CJK}])")
_TOKEN_RE = re.compile(r"[^\W_]+", re.UNICODE)

NGRAM_MIN = 3
NGRAM_MAX = 5
MAX_TOKEN_LEN = 30  # longer tokens truncated
MAX_NGRAMS = 40     # first N ngrams in generation order
FNV_OFFSET = 0xCBF29CE484222325
FNV_PRIME = 0x100000001B3
MASK64 = (1 << 64) - 1


def tokenize(text: str) -> list[str]:
    text = text.lower()
    text = _CJK_RE.sub(r" \1 ", text)
    return _TOKEN_RE.findall(text)


def fnv1a(data: bytes) -> int:
    h = FNV_OFFSET
    for b in data:
        h ^= b
        h = (h * FNV_PRIME) & MASK64
    return h


def bucket(s: str, buckets: int) -> int:
    return (fnv1a(s.encode("utf-8")) & 0x7FFFFFFFFFFFFFFF) % buckets


def token_ngrams(token: str) -> list[str]:
    """The full word (wrapped) plus char n-grams of the wrapped word."""
    token = token[:MAX_TOKEN_LEN]
    wrapped = f"<{token}>"
    grams = [wrapped]
    n = len(wrapped)
    for size in range(NGRAM_MIN, NGRAM_MAX + 1):
        if size >= n:
            continue
        for i in range(n - size + 1):
            grams.append(wrapped[i : i + size])
    return grams[:MAX_NGRAMS]


def token_buckets(token: str, buckets: int) -> list[int]:
    return [bucket(g, buckets) for g in token_ngrams(token)]


SIF_A = 1e-3


def sif_weight(count: int, total: int) -> float:
    if count <= 0 or total <= 0:
        return 1.0
    return SIF_A / (SIF_A + count / total)
