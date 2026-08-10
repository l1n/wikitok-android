"""Minimal pure-Python romanization for training-time script bridging.

Transliterated tokens are added to code-switch sentences as pivot anchors
(cf. arXiv:2406.19759, arXiv:2409.17326): the romanized form shares subwords
with Latin-script cognates/loanwords, giving SGNS a bridge between scripts.
Coverage is deliberately partial — ru is near-total, ja covers kana (kanji
passes through), zh is not attempted.
"""

RU = {
    "а": "a", "б": "b", "в": "v", "г": "g", "д": "d", "е": "e", "ё": "e",
    "ж": "zh", "з": "z", "и": "i", "й": "j", "к": "k", "л": "l", "м": "m",
    "н": "n", "о": "o", "п": "p", "р": "r", "с": "s", "т": "t", "у": "u",
    "ф": "f", "х": "h", "ц": "c", "ч": "ch", "ш": "sh", "щ": "shch",
    "ъ": "", "ы": "y", "ь": "", "э": "e", "ю": "yu", "я": "ya",
}

_KANA_BASE = {
    "あ": "a", "い": "i", "う": "u", "え": "e", "お": "o",
    "か": "ka", "き": "ki", "く": "ku", "け": "ke", "こ": "ko",
    "さ": "sa", "し": "shi", "す": "su", "せ": "se", "そ": "so",
    "た": "ta", "ち": "chi", "つ": "tsu", "て": "te", "と": "to",
    "な": "na", "に": "ni", "ぬ": "nu", "ね": "ne", "の": "no",
    "は": "ha", "ひ": "hi", "ふ": "fu", "へ": "he", "ほ": "ho",
    "ま": "ma", "み": "mi", "む": "mu", "め": "me", "も": "mo",
    "や": "ya", "ゆ": "yu", "よ": "yo",
    "ら": "ra", "り": "ri", "る": "ru", "れ": "re", "ろ": "ro",
    "わ": "wa", "を": "o", "ん": "n",
    "が": "ga", "ぎ": "gi", "ぐ": "gu", "げ": "ge", "ご": "go",
    "ざ": "za", "じ": "ji", "ず": "zu", "ぜ": "ze", "ぞ": "zo",
    "だ": "da", "ぢ": "ji", "づ": "zu", "で": "de", "ど": "do",
    "ば": "ba", "び": "bi", "ぶ": "bu", "べ": "be", "ぼ": "bo",
    "ぱ": "pa", "ぴ": "pi", "ぷ": "pu", "ぺ": "pe", "ぽ": "po",
}
_SMALL = {"ゃ": "ya", "ゅ": "yu", "ょ": "yo"}


def _kana_map() -> dict[str, str]:
    m = dict(_KANA_BASE)
    # katakana = hiragana + 0x60
    for k, v in list(_KANA_BASE.items()):
        m[chr(ord(k) + 0x60)] = v
    for k, v in list(_SMALL.items()):
        m[k] = v
        m[chr(ord(k) + 0x60)] = v
    m["ー"] = ""  # long-vowel mark
    m["っ"] = ""  # gemination (simplified)
    m[chr(ord("っ") + 0x60)] = ""
    return m


KANA = _kana_map()
_SMALL_ALL = set(_SMALL) | {chr(ord(k) + 0x60) for k in _SMALL}


def translit_ru(token: str) -> str:
    return "".join(RU.get(c, c) for c in token)


def translit_ja(token: str) -> str:
    out: list[str] = []
    for ch in token:
        r = KANA.get(ch)
        if r is None:
            out.append(ch)  # kanji and anything else pass through
        elif ch in _SMALL_ALL and out and out[-1] and out[-1][-1] == "i":
            out[-1] = out[-1][:-1]  # き+ゃ -> kya
            out.append(r)
        else:
            out.append(r)
    return "".join(out)


def translit_tokens(lang: str, tokens: list[str]) -> list[str]:
    """Romanized forms of tokens, dropping ones that didn't change."""
    fn = {"ru": translit_ru, "ja": translit_ja}.get(lang)
    if fn is None:
        return []
    out = []
    for t in tokens:
        r = fn(t)
        if r and r != t:
            out.append(r)
    return out
