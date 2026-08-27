#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Hands-free road-test loop: adb pull spectrum_kpi, compare to 48-80 Hz lock, live-tune.

Does not need the driver to reply. Cannot tap 開始降噪 (service not exported).

  python3 scripts/auto_road_watch.py --once
  python3 scripts/auto_road_watch.py --loop --hours 14 --interval 10
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

PKG = "com.example.caranc"
ROOT = Path(__file__).resolve().parents[1]
STATE_PATH = ROOT / "log" / "witness" / "auto_watch_state.json"
LOG_PATH = ROOT / "log" / "witness" / "auto_watch.jsonl"
PULL_DIR = ROOT / "log" / "witness" / "pulled"
HOSTS = [
    os.environ.get("CARANC_ADB_HOST", ""),
    "192.168.1.111:5555",
    "10.138.210.102:5555",
]
ADB = os.environ.get(
    "ADB",
    str(Path.home() / "Library/Android/sdk/platform-tools/adb"),
)

# Locked product target (cabin wav 2026-08-25: 50 Hz and 80 Hz reached air).
LF_MINUS_HF_OK = 6.0
RESIDUAL_BAD = -2.5
BOOM_STARVED = 0.012
SCALE_MIN, SCALE_MAX = 0.15, 2.0


def now_iso():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def adb_base():
    return ADB if Path(ADB).exists() else "adb"


def run(args, timeout=25, input_text=None):
    try:
        return subprocess.run(
            args,
            capture_output=True,
            text=True,
            timeout=timeout,
            input=input_text,
        )
    except subprocess.TimeoutExpired:
        return subprocess.CompletedProcess(args, 124, "", "timeout")


def connect():
    adb = adb_base()
    for h in [x for x in HOSTS if x]:
        run([adb, "connect", h], timeout=8)
    p = run([adb, "devices"], timeout=10)
    for line in (p.stdout or "").splitlines():
        if "\tdevice" in line and "List" not in line:
            return line.split()[0]
    return None


def shell(serial, remote, timeout=25):
    return run([adb_base(), "-s", serial, "shell", remote], timeout=timeout)


def run_as(serial, cmd, timeout=25):
    # One remote string so `sh -c` keeps pipes/globs.
    wrapped = "run-as %s sh -c %s" % (PKG, json.dumps(cmd))
    return shell(serial, wrapped, timeout=timeout)


def load_state():
    defaults = {
        "polarity": -1.0,
        "scale": 1.6,
        "nvh": "ROAD_RUMBLE",
        "gain": 1.0,
        "sendLpfHz": 160.0,
        "shelfBoost": 2.0,
        "highLatMs": 100.0,
        "muteSand": 1.0,
        "lfSendOnly": 1.0,
        "last_flip_ts": 0.0,
        "last_scale_ts": 0.0,
        "flips_this_session": 0,
        "session": "",
        "last_action": "init",
    }
    if STATE_PATH.exists():
        try:
            raw = json.loads(STATE_PATH.read_text())
            defaults.update(raw)
        except Exception:
            pass
    return defaults


def save_state(st):
    STATE_PATH.parent.mkdir(parents=True, exist_ok=True)
    tmp = STATE_PATH.with_suffix(".tmp")
    tmp.write_text(json.dumps(st, indent=2))
    tmp.replace(STATE_PATH)


def log_event(ev):
    LOG_PATH.parent.mkdir(parents=True, exist_ok=True)
    ev["ts"] = now_iso()
    with LOG_PATH.open("a", encoding="utf-8") as f:
        f.write(json.dumps(ev, ensure_ascii=False) + "\n")


def parse_kpis(text):
    kpis, snaps, meta = [], [], {}
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        try:
            o = json.loads(line)
        except Exception:
            continue
        ph = o.get("phase")
        if ph == "spectrum_kpi":
            kpis.append(o)
        elif ph == "running_snapshot":
            snaps.append(o)
        elif ph in ("audio_init", "aa_path_check", "live_tune_apply", "running_start"):
            meta[ph] = o
    return kpis, snaps, meta


def median(xs):
    xs = [float(x) for x in xs if x is not None]
    if not xs:
        return None
    xs.sort()
    return xs[len(xs) // 2]


def fget(o, k):
    v = o.get(k)
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def driving(kpis, snaps):
    d = [o for o in kpis if (fget(o, "speedKmh") or 0) >= 22]
    snap_list = [(o.get("ts") or 0, o) for o in snaps]
    paired = []
    for o in d:
        ts = o.get("ts") or 0
        best = {}
        best_dt = 5000
        for sts, s in snap_list:
            dt = abs((sts or 0) - ts)
            if dt < best_dt:
                best_dt = dt
                best = s
        if best_dt > 4000:
            best = {}
        paired.append((o, best))
    if not paired:
        for s in snaps:
            if (fget(s, "speedKmh") or 0) >= 22:
                paired.append(({}, s))
    return paired[-4:]


def decide(paired, meta, st, session):
    """Return (action, reason, new_state_fields). action=hold|scale_up|scale_down|flip|need_start|idle."""
    if session and session != st.get("session"):
        st["session"] = session
        st["flips_this_session"] = 0

    if not paired:
        path = (meta.get("audio_init") or {}).get("routeLabel") or ""
        return "idle", f"no_drive_kpi route={path}", {}

    last_k, last_s = paired[-1]
    src = last_k or last_s
    a40 = median([fget(k, "antiE40_80") for k, _ in paired] or [fget(src, "antiE40_80")])
    a2k = median([fget(k, "antiE500_2k") for k, _ in paired] or [fget(src, "antiE500_2k")])
    boom = median(
        [fget(k, "boomPressureOut") or fget(s, "boomPressureOut") for k, s in paired]
    )
    resid = median([fget(s, "plantResidualReductionDb") for _, s in paired])
    open_b = last_k.get("openBoom") if last_k else last_s.get("openBoom")
    strat = last_k.get("latencyStrategy") or last_s.get("latencyStrategy")
    spd = fget(src, "speedKmh")
    now = time.time()

    facts = {
        "speed": spd,
        "antiE40_80": a40,
        "antiE500_2k": a2k,
        "boomOut": boom,
        "residual": resid,
        "openBoom": open_b,
        "strategy": strat,
        "scale": st["scale"],
        "polarity": st["polarity"],
    }

    if a40 is not None and a40 <= -150 and spd and spd >= 22:
        return "need_start", "send_silent_while_driving", facts

    lf_ok = (
        a40 is not None
        and a2k is not None
        and a40 > -90
        and a40 >= (a2k + LF_MINUS_HF_OK)
    )
    sand = a40 is not None and a2k is not None and a2k > a40 + 1.0 and a2k > -80
    starved = (boom is not None and boom < BOOM_STARVED) or (
        a40 is not None and a40 < -40 and open_b
    )
    adding = resid is not None and resid < RESIDUAL_BAD

    if lf_ok and not adding:
        # Floor after a bad residual: if plant is now helping, ease scale back up.
        if (
            resid is not None
            and resid > 0.3
            and st["scale"] < 0.8
            and now - st.get("last_scale_ts", 0) >= 20
        ):
            new_s = min(0.8, round(st["scale"] + 0.2, 2))
            if new_s > st["scale"] + 1e-6:
                return "scale_up", "residual_positive_recover", {**facts, "new_scale": new_s}
        return "hold", "lf_dominates_and_residual_ok", facts
    if sand:
        # 1.2.37 should already kill sand; dropping scale avoids louder hiss.
        if now - st.get("last_scale_ts", 0) < 10:
            return "hold", "sand_cooldown", facts
        new_s = max(SCALE_MIN, round(st["scale"] * 0.75, 2))
        if new_s >= st["scale"] - 1e-6:
            return "hold", "sand_at_floor", facts
        facts["new_lpf"] = max(90.0, float(st.get("sendLpfHz", 160)) - 20.0)
        return "scale_down", "send_hf_sand", {**facts, "new_scale": new_s}
    if adding:
        if st.get("flips_this_session", 0) < 1 and now - st.get("last_flip_ts", 0) > 40:
            return "flip", "residual_adding", facts
        if now - st.get("last_scale_ts", 0) < 10:
            return "hold", "residual_cooldown", facts
        new_s = max(SCALE_MIN, round(st["scale"] * 0.7, 2))
        if new_s >= st["scale"] - 1e-6:
            return "hold", "residual_at_floor", facts
        return "scale_down", "residual_adding_after_flip", {**facts, "new_scale": new_s}
    if starved and spd and spd >= 22:
        if now - st.get("last_scale_ts", 0) < 8:
            return "hold", "starve_cooldown", facts
        new_s = min(SCALE_MAX, round(st["scale"] + 0.2, 2))
        if new_s <= st["scale"] + 1e-6:
            return "hold", "starve_at_cap", facts
        return "scale_up", "lf_starved_at_speed", {**facts, "new_scale": new_s}
    return "hold", "no_rule", facts


def write_tune(serial, st):
    pol = float(st["polarity"])
    scale = float(st["scale"])
    nvh = str(st["nvh"]).replace("'", "")
    gain = float(st["gain"])
    body = (
        f"forceBoomPolarity={pol:.0f}\n"
        f"boomOpenScale={scale}\n"
        f"forceNvhFocus={nvh}\n"
        f"userAncGain={gain}\n"
    )
    lpf = float(st.get("sendLpfHz", 160))
    shelf = float(st.get("shelfBoost", 2.0))
    hl = float(st.get("highLatMs", 100))
    sand = float(st.get("muteSand", 1))
    lf_only = float(st.get("lfSendOnly", 1))
    cmd = (
        "printf '%s\\n' "
        f"'forceBoomPolarity={pol:.0f}' 'boomOpenScale={scale}' "
        f"'forceNvhFocus={nvh}' 'userAncGain={gain}' "
        f"'sendLpfHz={lpf:.0f}' 'shelfBoost={shelf}' "
        f"'highLatMs={hl:.0f}' 'muteSand={sand:.0f}' 'lfSendOnly={lf_only:.0f}' "
        "> files/anc_live_tune.properties"
    )
    p = run_as(serial, cmd, timeout=15)
    return p.returncode == 0, body, (p.stderr or p.stdout or "")[:200]


def pull_tail(serial):
    ls = run_as(serial, "ls -t files/anc_logs/anc_session_*.log 2>/dev/null | head -1")
    name = (ls.stdout or "").strip().splitlines()
    if not name:
        return None, ""
    rel = name[0].strip()
    t = run_as(serial, f"tail -n 120 {rel}", timeout=30)
    text = t.stdout or ""
    PULL_DIR.mkdir(parents=True, exist_ok=True)
    dest = PULL_DIR / Path(rel).name
    # keep a rolling copy of the tail only
    dest.write_text(text, encoding="utf-8", errors="replace")
    return Path(rel).name, text


def cycle():
    serial = connect()
    if not serial:
        ev = {"action": "offline", "reason": "no_adb"}
        log_event(ev)
        return ev
    st = load_state()
    session, text = pull_tail(serial)
    if not text:
        ev = {"action": "need_start", "reason": "no_session_log", "serial": serial}
        log_event(ev)
        return ev
    kpis, snaps, meta = parse_kpis(text)
    paired = driving(kpis, snaps)
    action, reason, facts = decide(paired, meta, st, session or "")
    changed = False
    if action == "flip":
        st["polarity"] = -1.0 if st["polarity"] >= 0 else 1.0
        st["last_flip_ts"] = time.time()
        st["flips_this_session"] = st.get("flips_this_session", 0) + 1
        st["last_action"] = "flip"
        changed = True
    elif action in ("scale_up", "scale_down"):
        st["scale"] = float(facts.get("new_scale", st["scale"]))
        if facts.get("new_lpf") is not None:
            st["sendLpfHz"] = float(facts["new_lpf"])
        st["last_scale_ts"] = time.time()
        st["last_action"] = action
        changed = True
    elif action == "hold":
        st["last_action"] = "hold"
    ok_write = True
    if changed:
        ok_write, body, err = write_tune(serial, st)
        st["write_ok"] = ok_write
        st["write_err"] = err
    save_state(st)
    ev = {
        "action": action,
        "reason": reason,
        "serial": serial,
        "session": session,
        "changed": changed,
        "write_ok": ok_write if changed else None,
        "polarity": st["polarity"],
        "scale": st["scale"],
        **{k: facts.get(k) for k in facts},
    }
    log_event(ev)
    return ev


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--once", action="store_true")
    ap.add_argument("--loop", action="store_true")
    ap.add_argument("--interval", type=float, default=10.0)
    ap.add_argument("--hours", type=float, default=14.0)
    ap.add_argument(
        "--sleep-until",
        default="",
        help="Asia/Taipei ISO 'YYYY-MM-DDTHH:MM' before looping",
    )
    args = ap.parse_args()
    STATE_PATH.parent.mkdir(parents=True, exist_ok=True)
    if args.sleep_until:
        try:
            from zoneinfo import ZoneInfo

            tz = ZoneInfo("Asia/Taipei")
            target = datetime.strptime(args.sleep_until, "%Y-%m-%dT%H:%M").replace(tzinfo=tz)
            wait = (target - datetime.now(tz)).total_seconds()
            if wait > 0:
                print(json.dumps({"action": "sleep", "seconds": int(wait), "until": args.sleep_until}), flush=True)
                time.sleep(wait)
        except Exception as e:
            print(json.dumps({"action": "sleep_skip", "error": str(e)}), flush=True)
    if args.once or not args.loop:
        ev = cycle()
        print(json.dumps(ev, ensure_ascii=False), flush=True)
        return 0
    deadline = time.time() + args.hours * 3600
    while time.time() < deadline:
        ev = cycle()
        print(json.dumps(ev, ensure_ascii=False), flush=True)
        time.sleep(max(5.0, args.interval))
    print(json.dumps({"action": "done", "reason": "hours_elapsed"}), flush=True)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(0)
