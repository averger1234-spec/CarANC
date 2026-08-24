# Live AA-path logcat watcher. Unbuffered. Prints only high-signal lines.
import os
import re
import subprocess
import sys
from datetime import datetime

ADB = os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe")
SERIAL = os.environ.get("AA_ADB_SERIAL", "10.138.210.102:5555")
LOG_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "log")
os.makedirs(LOG_DIR, exist_ok=True)
OUT = os.path.join(LOG_DIR, datetime.now().strftime("aa_live_logcat_%Y%m%d_%H%M%S.txt"))

# Broader: keep in file. Tighter: print (wakes the agent).
FILE_PAT = re.compile(
    r"ANCService|AncMediaSession|AudioRoute|AudioTrack|AudioFlinger|AudioPolicy|"
    r"MediaSession|AUDIOFOCUS|remote_submix|projection|Gearhead|GH\.Audio|GH\.AUDIO|"
    r"AndroidAuto|CAR\.AUDIO|Car Connection|aa_|AA_|submix|USAGE_MEDIA",
    re.I,
)
PRINT_PAT = re.compile(
    r"ANCService.*(aa_connected|aa_disconnected|aa_became|aa_path_check|"
    r"AUDIO_BACKEND|HIGH_LATENCY_AA|WIRELESS_AA|"
    r"requestRunningMediaFocus|MEDIA focus|tearDownEngine|"
    r"PHONE_SPEAKER|MIC_HEARD|verdict|cabinHeard|heard50|heard80|收到停止)|"
    r"AudioRoute.*(AA_MIXER_ATTR|STREAM_MUSIC boost|AA_SUBMIX_PICK)|"
    r"Car Connection Type|"
    r"MediaSessionStack.*caranc|"
    r"openOutput\(\).*REMOTE_SUBMIX|"
    r"Will record from REMOTE_SUBMIX|"
    r"pack:com\.google\.android\.projection\.gearhead",
    re.I,
)


def main():
    import time

    print(f"WATCHING {SERIAL} file={OUT} (reconnect loop)", flush=True)
    with open(OUT, "a", encoding="utf-8", errors="replace") as fh:
        while True:
            subprocess.run(
                [ADB, "connect", SERIAL],
                check=False,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            proc = subprocess.Popen(
                [ADB, "-s", SERIAL, "logcat", "-v", "threadtime"],
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                encoding="utf-8",
                errors="replace",
                bufsize=1,
            )
            assert proc.stdout is not None
            got = False
            for line in proc.stdout:
                got = True
                line = line.rstrip("\n")
                if " I adbd " in line or "AndroidRuntime" in line:
                    continue
                if FILE_PAT.search(line):
                    fh.write(line + "\n")
                    fh.flush()
                if PRINT_PAT.search(line):
                    print(line, flush=True)
            proc.wait()
            time.sleep(8 if not got else 2)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(0)
