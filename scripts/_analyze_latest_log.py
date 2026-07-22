# -*- coding: utf-8 -*-
"""One-off analyzer for anc_session JSONL (possibly concatenated objects)."""
import json
import re
import collections
import sys
from pathlib import Path

def extract_objs(text):
    objs = []
    # decoder streaming over whole text
    dec = json.JSONDecoder()
    i = 0
    n = len(text)
    while i < n:
        while i < n and text[i] not in "{[":
            i += 1
        if i >= n:
            break
        try:
            obj, end = dec.raw_decode(text, i)
            if isinstance(obj, dict):
                objs.append(obj)
            i = end
        except json.JSONDecodeError:
            i += 1
    return objs

def main(path):
    p = Path(path)
    text = p.read_text(encoding="utf-8", errors="replace")
    print(f"file={p.name} bytes={p.stat().st_size}")
    objs = extract_objs(text)
    print(f"parsed_objs={len(objs)}")
    phases = collections.Counter(o.get("phase", "?") for o in objs)
    print("top_phases:", phases.most_common(25))

    snaps = [o for o in objs if o.get("phase") == "running_snapshot"]
    print(f"running_snapshots={len(snaps)}")
    if not snaps:
        # dump other useful
        for ph in ("audio_init", "debug_presets_apply", "test_step_snapshot"):
            ps = [o for o in objs if o.get("phase") == ph]
            print(f"{ph}: {len(ps)}")
            if ps:
                print(" last keys sample:", list(ps[-1].keys())[:40])
        return

    def step_id(o):
        return o.get("guidedTestStepId") or o.get("guidedTestStep") or "none"

    steps = collections.Counter(step_id(o) for o in snaps)
    print("steps:", steps.most_common(20))

    def stats(key, subset=None):
        src = subset if subset is not None else snaps
        vals = []
        for o in src:
            v = o.get(key)
            if isinstance(v, (int, float)):
                vals.append(float(v))
        if not vals:
            return None
        vals.sort()
        return {
            "n": len(vals),
            "min": round(vals[0], 4),
            "max": round(vals[-1], 4),
            "avg": round(sum(vals) / len(vals), 4),
            "p50": round(vals[len(vals) // 2], 4),
        }

    keys = [
        "reductionDb", "lowBandRumbleReduction", "lowBandReductionDb",
        "estimatedLatencyMs", "measuredLatencyMs", "maxCancelFrequencyHz",
        "previewRumble", "predictionHorizonMs", "plantElectricalDelaySamples",
        "fixedBankOut", "fdafPartitions", "preLearnedBinCount", "roadRoughness",
        "rumbleVibBoost", "effectiveLowMu", "effectiveMidMu",
        "musicSuppressionQuality", "virtualSuppressionQuality", "rumbleEnergyProxy",
        "speedKmh", "accelMag", "antiNoiseDb",
    ]
    print("=== GLOBAL numeric ===")
    for k in keys:
        s = stats(k)
        if s:
            print(f"  {k}: {s}")

    print("=== GLOBAL categorical ===")
    cats = [
        "latencyStrategy", "audioBackend", "fdafDelayless", "usingLatencyOverride",
        "wirelessAaSuspected", "wiredCarPathAvailable", "effectiveRumbleMode",
        "musicDominantRumbleMode", "processingMode", "dominantNoiseBand",
        "tier", "routeLabel", "music",
    ]
    for k in cats:
        c = collections.Counter(str(o.get(k, "<missing>")) for o in snaps)
        print(f"  {k}: {c.most_common(6)}")

    print("=== BY STEP ===")
    for step, n in steps.most_common():
        ss = [o for o in snaps if step_id(o) == step]
        red = stats("reductionDb", ss)
        mid = stats("effectiveMidMu", ss)
        prev = stats("previewRumble", ss)
        lat = stats("measuredLatencyMs", ss) or stats("estimatedLatencyMs", ss)
        fixed = stats("fixedBankOut", ss)
        rough = stats("roadRoughness", ss)
        strat = collections.Counter(str(o.get("latencyStrategy", "?")) for o in ss)
        fdaf = collections.Counter(str(o.get("fdafDelayless", "?")) for o in ss)
        backend = collections.Counter(str(o.get("audioBackend", "?")) for o in ss)
        wireless = collections.Counter(str(o.get("wirelessAaSuspected", "?")) for o in ss)
        print(f"-- {step} n={n}")
        print(f"   red={red} midMu={mid} preview={prev} lat={lat}")
        print(f"   fixedBank={fixed} roughness={rough}")
        print(f"   strategy={strat.most_common(4)} fdafDelayless={fdaf.most_common(3)}")
        print(f"   backend={backend.most_common(3)} wireless={wireless.most_common(3)}")

    print("=== SPECIAL PHASES ===")
    for ph in [
        "audio_init", "wireless_aa_warning", "runtime_latency_correlated",
        "processor_created", "debug_presets_apply", "test_script_complete",
        "sonification_detected", "bump_detected",
    ]:
        ps = [o for o in objs if o.get("phase") == ph]
        print(f"  {ph}: {len(ps)}")
        if ps and ph in ("audio_init", "wireless_aa_warning", "processor_created", "debug_presets_apply"):
            o = ps[-1]
            interesting = {
                k: o.get(k) for k in (
                    "audioBackend", "wirelessAaSuspected", "wiredCarPathAvailable",
                    "requireWiredAa", "aaConnected", "sampleRate", "trackBufferBytes",
                    "estimatedLatencyMs", "measuredLatencyMs", "routeLabel",
                    "initialTier", "lmsMuMultiplier", "latencyOverrideMs",
                    "musicLowAncEnabled", "tier", "totalMs", "trackBufferMs",
                ) if k in o or o.get(k) is not None
            }
            # also print any latency-related
            for k, v in o.items():
                if "lat" in k.lower() or "backend" in k.lower() or "wireless" in k.lower() or "wired" in k.lower():
                    interesting[k] = v
            print(f"   last: {interesting}")

    ts = [o.get("ts") for o in snaps if isinstance(o.get("ts"), (int, float))]
    if ts:
        print(f"duration_min={(max(ts)-min(ts))/60000:.2f} ts0={min(ts)} ts1={max(ts)}")

if __name__ == "__main__":
    path = sys.argv[1] if len(sys.argv) > 1 else "log/anc_session_20260722_073545.log"
    main(path)
