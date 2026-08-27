#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Independent acoustic witness vs phone CarANC spectrum_kpi.

Same band-RMS dB as shared SpectrumAnalyzer.bandRangeEnergyDb, plus FFT peak.

Usage:
  python3 scripts/compare_witness_spectrum.py WAV [JSONL]
  python3 scripts/compare_witness_spectrum.py --phone-log JSONL
  python3 scripts/compare_witness_spectrum.py --record 8
"""
from __future__ import annotations

import argparse
import json
import math
import os
import struct
import subprocess
import sys
import wave
from collections import Counter
from pathlib import Path

BANDS = (
    ("40-80 boom", 40.0, 80.0),
    ("80-120 cabin", 80.0, 120.0),
    ("200-500 tire", 200.0, 500.0),
    ("500-2000 wind", 500.0, 2000.0),
)

try:
    import numpy as np
except ImportError:
    np = None  # type: ignore


def band_range_energy_db(samples, sr: float, f_lo: float, f_hi: float) -> float:
    """Match Kotlin SpectrumAnalyzer.bandRangeEnergyDb (1-pole BP RMS)."""
    if not samples or sr <= 0 or f_hi <= f_lo:
        return -90.0
    a_hi = min(max(2.0 * math.pi * f_hi / sr, 0.002), 0.95)
    a_lo = min(max(2.0 * math.pi * f_lo / sr, 0.001), 0.9)
    lp_hi = lp_lo = 0.0
    ss = 0.0
    n = 0
    for x in samples:
        lp_hi += a_hi * (x - lp_hi)
        lp_lo += a_lo * (x - lp_lo)
        y = lp_hi - lp_lo
        ss += y * y
        n += 1
    rms = math.sqrt(ss / max(n, 1)) + 1e-10
    return 20.0 * math.log10(rms)


def load_wav_mono(path: str):
    with wave.open(path, "rb") as w:
        sr = w.getframerate()
        ch = w.getnchannels()
        sw = w.getsampwidth()
        n = w.getnframes()
        raw = w.readframes(n)
    if sw != 2:
        raise SystemExit(f"need 16-bit wav, got {sw} bytes ({path})")
    nfr = len(raw) // 2
    vals = struct.unpack("<" + "h" * nfr, raw)
    if ch == 2:
        vals = vals[0::2]
    return sr, [v / 32768.0 for v in vals]


def fft_peak(samples, sr: float, fmin=20.0, fmax=2000.0):
    if np is None or len(samples) < 256:
        return None
    x = np.asarray(samples, dtype=np.float64)
    if len(x) > int(sr * 12):
        x = x[: int(sr * 12)]
    win = np.hanning(len(x))
    spec = np.abs(np.fft.rfft(x * win))
    freqs = np.fft.rfftfreq(len(x), 1.0 / sr)
    m = (freqs >= fmin) & (freqs <= fmax)
    if not np.any(m):
        return None
    i = int(np.argmax(spec[m]))
    f = float(freqs[m][i])
    peak = float(spec[m][i])
    med = float(np.median(spec[m]) + 1e-12)
    snr = 20.0 * math.log10(peak / med)
    # energy in the four bands via FFT bins (narrow, not 1-pole)
    def e(lo, hi):
        mm = (freqs >= lo) & (freqs < hi)
        if not np.any(mm):
            return -90.0
        rms = math.sqrt(float(np.mean(spec[mm] ** 2))) + 1e-12
        return 20.0 * math.log10(rms)

    return {
        "peak_hz": f,
        "snr_db": snr,
        "fft_40_80": e(40, 80),
        "fft_80_120": e(80, 120),
        "fft_200_500": e(200, 500),
        "fft_500_2000": e(500, 2000),
    }


def analyze_wav(path: str) -> dict:
    sr, samples = load_wav_mono(path)
    dur = len(samples) / float(sr)
    rms = math.sqrt(sum(x * x for x in samples) / max(len(samples), 1)) + 1e-10
    out = {
        "path": path,
        "sr": sr,
        "dur_s": dur,
        "rms_db": 20.0 * math.log10(rms),
    }
    for name, lo, hi in BANDS:
        out[name] = band_range_energy_db(samples, sr, lo, hi)
    peak = fft_peak(samples, sr)
    if peak:
        out.update(peak)
    return out


def load_kpis(jsonl_path: str):
    rows = []
    with open(jsonl_path, "rb") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith(b"#"):
                continue
            try:
                o = json.loads(line)
            except Exception:
                continue
            if o.get("phase") == "spectrum_kpi":
                rows.append(o)
    return rows


def kpi_summary(rows, min_speed=0.0):
    use = [o for o in rows if float(o.get("speedKmh") or 0) >= min_speed]
    if not use:
        return None

    def med(key):
        xs = [float(o[key]) for o in use if o.get(key) is not None]
        if not xs:
            return None
        xs.sort()
        return xs[len(xs) // 2]

    return {
        "n": len(use),
        "speed_med": med("speedKmh"),
        "antiE40_80": med("antiE40_80"),
        "antiE80_120": med("antiE80_120"),
        "antiE200_500": med("antiE200_500"),
        "antiE500_2k": med("antiE500_2k"),
        "micE40_80": med("micE40_80"),
        "micE80_150": med("micE80_150"),
        "boomPressureOut": med("boomPressureOut"),
        "antiLfDominatesHf": Counter(str(o.get("antiLfDominatesHf")) for o in use),
        "latencyStrategy": Counter(o.get("latencyStrategy") for o in use),
        "openBoom": Counter(o.get("openBoom") for o in use),
        "nvhFocus": Counter(o.get("nvhFocus") for o in use),
    }


def fmt(v, nd=1):
    if v is None:
        return "—"
    if isinstance(v, float):
        return f"{v:.{nd}f}"
    return str(v)


def print_wav(title: str, a: dict):
    print(f"\n== {title} ==")
    print(f"  file {a['path']}")
    print(f"  {a['dur_s']:.1f}s @{a['sr']} Hz  broadband RMS {a['rms_db']:.1f} dBFS")
    print(f"  40-80={a['40-80 boom']:+.1f}  80-120={a['80-120 cabin']:+.1f}  "
          f"200-500={a['200-500 tire']:+.1f}  500-2000={a['500-2000 wind']:+.1f}")
    if "peak_hz" in a:
        print(f"  FFT peak {a['peak_hz']:.1f} Hz  SNR {a['snr_db']:.1f} dB vs band median")
        lf = a["fft_40_80"]
        hf = a["fft_500_2000"]
        print(f"  FFT bins 40-80={lf:.1f}  500-2000={hf:.1f}  LF-HF={lf-hf:+.1f}")
        if a["peak_hz"] >= 180:
            print("  VERDICT: witness is MID/HF (sand / hiss), not cabin boom")
        elif a["peak_hz"] <= 120 and a["snr_db"] >= 8:
            print("  VERDICT: witness has a real LF peak (boom-band)")
        else:
            print("  VERDICT: no strong tonal peak (noise floor or mixed)")


def print_phone(title: str, s: dict):
    print(f"\n== {title} n={s['n']} speed_med={fmt(s['speed_med'])} km/h ==")
    print(f"  SEND antiE40_80={fmt(s['antiE40_80'])}  80-120={fmt(s['antiE80_120'])}  "
          f"200-500={fmt(s['antiE200_500'])}  500-2k={fmt(s['antiE500_2k'])}")
    print(f"  PHONE-MIC micE40_80={fmt(s['micE40_80'])}  80-150={fmt(s['micE80_150'])}  "
          f"boomOut={fmt(s['boomPressureOut'], 3)}")
    print(f"  strategy {dict(s['latencyStrategy'])}  openBoom {dict(s['openBoom'])}  "
          f"lfDom {dict(s['antiLfDominatesHf'])}")
    a40 = s["antiE40_80"]
    a2k = s["antiE500_2k"]
    if a40 is not None and a2k is not None:
        if a40 > a2k + 6:
            print("  SEND: LF-heavy (what we want)")
        elif a2k > a40:
            print("  SEND: HF heavier than boom — matches '吵雜聲'")
        else:
            print("  SEND: LF not clearly above HF")


def record_mac(out: Path, seconds: float) -> Path:
    swift_src = Path(__file__).with_name("record_mac_mic.swift")
    bin_path = Path("/tmp/record_mac_mic")
    compile = subprocess.run(
        ["swiftc", "-O", "-o", str(bin_path), str(swift_src)],
        capture_output=True,
        text=True,
    )
    if compile.returncode != 0:
        sys.stderr.write(compile.stderr or compile.stdout or "")
        raise SystemExit("swiftc record_mac_mic.swift failed")
    print(f"recording Mac mic {seconds:.0f}s → {out}", flush=True)
    p = subprocess.run(
        [str(bin_path), str(out), str(seconds)],
        capture_output=True,
        text=True,
    )
    if p.returncode != 0:
        sys.stderr.write(p.stderr or p.stdout or "")
        raise SystemExit(f"mac mic record failed (exit {p.returncode})")
    if not out.is_file() or out.stat().st_size < 1024:
        raise SystemExit(f"wav missing or tiny: {out}")
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("wav", nargs="?", help="witness wav (Mac mic or cabin_*.wav)")
    ap.add_argument("jsonl", nargs="?", help="anc_session jsonl")
    ap.add_argument("--record", type=float, default=0, help="record Mac mic N seconds first")
    ap.add_argument("--phone-log", help="spectrum_kpi jsonl")
    ap.add_argument("--min-speed", type=float, default=0.0)
    args = ap.parse_args()

    wav = args.wav
    if args.record:
        dest = Path("/tmp/caranc_mac_mic.wav")
        wav = str(record_mac(dest, args.record))
    if not wav:
        ap.error("need a wav or --record")

    print_wav("WITNESS", analyze_wav(wav))

    logp = args.phone_log or args.jsonl
    if logp:
        kpis = load_kpis(logp)
        s_all = kpi_summary(kpis, 0.0)
        s_drv = kpi_summary(kpis, max(22.0, args.min_speed))
        if s_all:
            print_phone("PHONE spectrum_kpi all", s_all)
        if s_drv:
            print_phone("PHONE spectrum_kpi ≥22 km/h", s_drv)
        elif s_all:
            print("\n  (no ≥22 km/h rows — cannot compare driving send vs witness)")


if __name__ == "__main__":
    main()
