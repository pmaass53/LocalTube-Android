"""
Minimal pure-Python stand-in for the `Levenshtein` package (python-Levenshtein).
Implements distance() and ratio(), including the processor/score_cutoff
keyword arguments some callers (rapidfuzz-style APIs) pass through.
"""


def distance(s1: str, s2: str, *, processor=None, **kwargs) -> int:
    if processor is not None:
        s1 = processor(s1)
        s2 = processor(s2)

    if s1 == s2:
        return 0
    if len(s1) == 0:
        return len(s2)
    if len(s2) == 0:
        return len(s1)

    # ensure s1 is the shorter string, to use less memory
    if len(s1) > len(s2):
        s1, s2 = s2, s1

    previous_row = list(range(len(s1) + 1))
    for i, c2 in enumerate(s2, 1):
        current_row = [i] + [0] * len(s1)
        for j, c1 in enumerate(s1, 1):
            insertions = previous_row[j] + 1
            deletions = current_row[j - 1] + 1
            substitutions = previous_row[j - 1] + (c1 != c2)
            current_row[j] = min(insertions, deletions, substitutions)
        previous_row = current_row

    return previous_row[-1]


def ratio(s1: str, s2: str, *, processor=None, score_cutoff=None, **kwargs) -> float:
    if processor is not None:
        s1 = processor(s1)
        s2 = processor(s2)

    if not s1 and not s2:
        result = 1.0
    else:
        d = distance(s1, s2)
        result = 1.0 - (d / max(len(s1), len(s2)))

    if score_cutoff is not None and result < score_cutoff:
        return 0.0
    return result
