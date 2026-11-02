"""
Train Kaya's sleep-stage model on the Walch et al. 2019 sleep-lab dataset (PhysioNet sleep-accel, ODC-By).

Only uses signals the Noise REP gives: heart rate per 30-s slot and hourly steps.
Model: multinomial logistic regression (numpy, no other deps), probabilities smoothed over neighbouring epochs.
Features MUST match engine/SleepModel.kt exactly.

Usage: python -I train.py <walch_dir>   (prints held-out scores and the Kotlin coefficient block)
"""
import os
import sys

import numpy as np

STAGES = ["WAKE", "LIGHT", "DEEP", "REM"]
TRUTH = {0: 0, 1: 1, 2: 1, 3: 2, 4: 2, 5: 3}
FEATURES = ["hr_above_base", "hr_rank", "sd5", "sd15", "absdiff5", "trend10", "hr30_above_base",
            "steps_log", "frac", "frac2", "sd5_rel"]


def rolling(vals, i, half, fn):
    lo, hi = max(0, i - half), min(len(vals), i + half + 1)
    w = [v for v in vals[lo:hi] if v is not None]
    return fn(w) if len(w) >= max(3, (hi - lo) // 3) else None


def features(hr, steps_hourly, start_frac_idx, end_idx):
    """hr: list of 30-s slot values (None = missing) over the analysis window. steps_hourly: steps per slot's hour."""
    known = [v for v in hr if v is not None]
    base = float(np.percentile(known, 10))
    srt = np.sort(known)
    n = len(hr)
    sm5 = [rolling(hr, i, 5, np.mean) for i in range(n)]
    rows = []
    for i in range(n):
        if hr[i] is None or sm5[i] is None:
            rows.append(None)
            continue
        sd5 = rolling(hr, i, 5, np.std)
        sd15 = rolling(hr, i, 15, np.std)
        lo, hi = max(0, i - 5), min(n, i + 6)
        seg = [v for v in hr[lo:hi] if v is not None]
        absdiff = float(np.mean(np.abs(np.diff(seg)))) if len(seg) >= 3 else 0.0
        a = sm5[i - 10] if i >= 10 else None
        b = sm5[i + 10] if i + 10 < n else None
        trend = (b - a) if (a is not None and b is not None) else 0.0
        m30 = rolling(hr, i, 30, np.mean)
        frac = min(1.0, max(0.0, (i - start_frac_idx) / max(1, end_idx - start_frac_idx)))  # clamped, see SleepModel.kt
        rows.append([
            sm5[i] - base,
            float(np.searchsorted(srt, sm5[i])) / len(srt),
            sd5 or 0.0,
            sd15 or 0.0,
            absdiff,
            trend,
            (m30 - base) if m30 is not None else sm5[i] - base,
            float(np.log1p(steps_hourly[i])),
            frac,
            frac * frac,
            (sd5 or 0.0) / max(1.0, sm5[i] - base + 5),
        ])
    return rows


def load(d):
    subs = []
    for f in sorted(os.listdir(os.path.join(d, "lab"))):
        sid = f.split("_")[0]
        lab = [l.split() for l in open(os.path.join(d, "lab", f)) if l.strip()]
        lab = [(int(float(t)), int(c)) for t, c in lab]
        end = max(t for t, _ in lab)
        hr_raw = [tuple(map(float, l.strip().split(","))) for l in open(os.path.join(d, "hr", f"{sid}_heartrate.txt")) if "," in l]
        st_path = os.path.join(d, "steps", f"{sid}_steps.txt")
        st_raw = [tuple(map(float, l.strip().split(","))) for l in open(st_path) if "," in l] if os.path.exists(st_path) else []
        t0, t1 = -7200, end + 3600                       # 2 h before lights-off .. 1 h after the end, like the app's window
        n = (t1 - t0) // 30
        slots = [[] for _ in range(n)]
        for t, v in hr_raw:
            k = int((t - t0) // 30)
            if 0 <= k < n and v > 0:
                slots[k].append(v)
        hr = [round(float(np.mean(s))) if s else None for s in slots]
        hours = {}
        for t, v in st_raw:
            if t0 <= t < t1:
                h = int((t - t0) // 3600)
                hours[h] = hours.get(h, 0) + v
        steps = [hours.get(int(k * 30 // 3600), 0) for k in range(n)]
        labels = [None] * n
        for t, c in lab:
            k = (t - t0) // 30
            if 0 <= k < n and c in TRUTH:
                labels[k] = TRUTH[c]
        first = min(k for k, v in enumerate(labels) if v is not None)
        last = max(k for k, v in enumerate(labels) if v is not None)
        subs.append((sid, hr, steps, labels, first, last))
    return subs


def matrix(subs, which):
    X, y = [], []
    for idx in which:
        sid, hr, steps, labels, first, last = subs[idx]
        rows = features(hr, steps, first, last)
        for k in range(first, last + 1):
            if labels[k] is not None and rows[k] is not None:
                X.append(rows[k]); y.append(labels[k])
    return np.array(X), np.array(y)


def train(X, y, iters=4000, lr=0.3, l2=1e-3, power=0.5):
    mu, sd = X.mean(0), X.std(0) + 1e-9
    Z = np.hstack([(X - mu) / sd, np.ones((len(X), 1))])
    k = len(STAGES)
    counts = np.bincount(y, minlength=k).astype(float)
    cw = (counts.sum() / (k * np.maximum(counts, 1))) ** power      # soften class imbalance
    sw = cw[y]
    Y = np.eye(k)[y]
    W = np.zeros((Z.shape[1], k))
    for _ in range(iters):
        P = softmax(Z @ W)
        g = Z.T @ ((P - Y) * sw[:, None]) / sw.sum() + l2 * np.vstack([W[:-1], np.zeros((1, k))])
        W -= lr * g
    return mu, sd, W


def softmax(A):
    A = A - A.max(1, keepdims=True)
    E = np.exp(A)
    return E / E.sum(1, keepdims=True)


def predict_subject(model, sub, smooth=4):
    mu, sd, W = model
    sid, hr, steps, labels, first, last = sub
    rows = features(hr, steps, first, last)
    n = len(hr)
    P = np.full((n, len(STAGES)), np.nan)
    for i, r in enumerate(rows):
        if r is not None:
            z = np.append((np.array(r) - mu) / sd, 1.0)
            P[i] = softmax((z @ W)[None, :])[0]
    out = []
    for i in range(n):
        lo, hi = max(0, i - smooth), min(n, i + smooth + 1)
        w = P[lo:hi]
        w = w[~np.isnan(w[:, 0])]
        out.append(int(np.argmax(w.mean(0))) if len(w) else 1)
    return out


def score(model, subs, which):
    cm = np.zeros((4, 4), int)
    tst_err = []
    for idx in which:
        sub = subs[idx]
        pred = predict_subject(model, sub)
        labels = sub[3]
        ts = ps = 0
        for k, t in enumerate(labels):
            if t is None:
                continue
            cm[t, pred[k]] += 1
            ts += t != 0; ps += pred[k] != 0
        tst_err.append(abs(ts - ps) / 2)
    tot = cm.sum(); acc = np.trace(cm) / tot
    pe = (cm.sum(1) * cm.sum(0)).sum() / tot ** 2
    sens = cm[1:, 1:].sum() / cm[1:].sum(); spec = cm[0, 0] / cm[0].sum()
    sw = (cm[0, 0] + cm[1:, 1:].sum()) / tot
    return dict(acc=acc, kappa=(acc - pe) / (1 - pe), sleepwake=sw, sens=sens, spec=spec,
                tst_err=float(np.mean(tst_err)), light_base=cm[1].sum() / tot,
                true_min=(cm.sum(1) / 2 / len(which)).round(), pred_min=(cm.sum(0) / 2 / len(which)).round(), cm=cm)


def show(name, s):
    print(f"== {name}")
    print(f"  4-stage accuracy {s['acc']*100:.1f}%  (always-light baseline {s['light_base']*100:.1f}%)  kappa {s['kappa']:.2f}")
    print(f"  asleep-vs-awake {s['sleepwake']*100:.1f}%  sleep detected {s['sens']*100:.1f}%  wake detected {s['spec']*100:.1f}%")
    print(f"  total-sleep error {s['tst_err']:.0f} min/night")
    print(f"  minutes/night  lab {dict(zip(STAGES, s['true_min']))}  model {dict(zip(STAGES, s['pred_min']))}")


if __name__ == "__main__":
    subs = load(sys.argv[1])
    idx = list(range(len(subs)))
    a, b = idx[0::2], idx[1::2]
    # 2-fold cross-validation: train on one half, test on the other, then swap. Pool held-out predictions.
    m_a, m_b = train(*matrix(subs, a)), train(*matrix(subs, b))
    s1, s2 = score(m_a, subs, b), score(m_b, subs, a)
    cm = s1["cm"] + s2["cm"]
    tot = cm.sum(); acc = np.trace(cm) / tot; pe = (cm.sum(1) * cm.sum(0)).sum() / tot ** 2
    print(f"HELD-OUT (2-fold, {len(subs)} people): 4-stage {acc*100:.1f}%  kappa {(acc-pe)/(1-pe):.2f}  "
          f"sleep/wake {(cm[0,0]+cm[1:,1:].sum())/tot*100:.1f}%  wake detected {cm[0,0]/cm[0].sum()*100:.1f}%  "
          f"TST err {(s1['tst_err']*len(b)+s2['tst_err']*len(a))/len(subs):.0f} min")
    show("fold A->B", s1); show("fold B->A", s2)
    print("confusion (rows=lab, cols=model; W L D R):"); print(cm)
    final = train(*matrix(subs, idx))
    mu, sd, W = final
    def arr(v): return ", ".join(f"{x:.6f}" for x in v)
    print("\n// ---- generated by tools/sleep_model/train.py ----")
    print(f"val MEAN = doubleArrayOf({arr(mu)})")
    print(f"val STD = doubleArrayOf({arr(sd)})")
    print("val W = arrayOf(")
    for row in W: print(f"    doubleArrayOf({arr(row)}),")
    print(")")
