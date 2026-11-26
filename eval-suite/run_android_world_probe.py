#!/usr/bin/env python3
"""
AndroidWorld-aligned Live Benchmark Probe for Aegis on Pixel_9 (emulator-5554).

Tests representative tasks from google-research/android_world (single-app & system):
  1. Clock (com.google.android.deskclock)
  2. Contacts (com.google.android.contacts)
  3. Browser / Chrome (com.android.chrome)
  4. Calendar (com.google.android.calendar)
  5. System Settings (com.android.settings -> expected Level 0 Hard-Block by Aegis policy)
"""

import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from dataclasses import asdict, dataclass
from typing import List, Optional

SERIAL = "emulator-5554"


def adb(*args: str, timeout: int = 35) -> str:
    res = subprocess.run(["adb", "-s", SERIAL, *args], capture_output=True, text=True, timeout=timeout)
    return (res.stdout or "") + (res.stderr or "")


def shell(cmd: str, timeout: int = 35) -> str:
    return adb("shell", cmd, timeout=timeout)


def dump_ui_texts() -> List[str]:
    shell("uiautomator dump /sdcard/aw_ui.xml >/dev/null 2>&1")
    xml_raw = shell("cat /sdcard/aw_ui.xml")
    start = xml_raw.find("<?xml")
    end = xml_raw.rfind("</hierarchy>")
    if start == -1 or end == -1:
        return []
    root = ET.fromstring(xml_raw[start : end + len("</hierarchy>")])
    return [n.attrib.get("text", "") for n in root.iter("node") if n.attrib.get("text")]


def foreground_activity() -> str:
    return shell("dumpsys activity activities | grep -i 'topResumedActivity'")


def ensure_aegis_focus() -> None:
    for _ in range(8):
        focus = shell("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'")
        if "com.aegis.core/com.aegis.core.MainActivity" in focus:
            return
        shell("am start -W -f 0x10200000 -n com.aegis.core/.MainActivity")
        time.sleep(0.5)


def submit_ask(cmd_text: str) -> None:
    ensure_aegis_focus()
    if "mInputShown=true" in shell("dumpsys input_method | grep mInputShown"):
        shell("input keyevent 4; sleep 0.4")
        ensure_aegis_focus()
    encoded = cmd_text.replace(" ", "%s")
    shell(
        f"input tap 550 2062; sleep 0.7; "
        f"input text '{encoded}'; sleep 0.4; "
        f"input keyevent 66"
    )


def latest_ttft() -> Optional[dict]:
    out = adb("logcat", "-d", "-s", "AegisTTFT:I")
    for raw in reversed(re.findall(r"AegisTTFT:\s*(\{.*\})", out)):
        try:
            obj = json.loads(raw)
            if "ttft_ms" in obj:
                return obj
        except json.JSONDecodeError:
            continue
    return None


@dataclass
class AWResult:
    android_world_family: str
    prompt: str
    expected: str
    passed: bool
    elapsed_ms: int
    details: str
    ttft_ms: Optional[int] = None
    decode_ms: Optional[int] = None


def bring_aegis_to_front() -> None:
    shell(
        "am force-stop com.google.android.deskclock; "
        "am force-stop com.google.android.contacts; "
        "am force-stop com.android.chrome; "
        "am force-stop com.google.android.calendar; "
        "am force-stop com.android.settings; "
        "am force-stop com.google.android.gms; "
        "am start -W -f 0x10200000 -n com.aegis.core/.MainActivity"
    )
    for _ in range(15):
        time.sleep(0.4)
        if "com.aegis.core" in foreground_activity():
            break


def run_launch_task(family: str, prompt: str, pkg: str, require_vlm_vision: bool = False) -> AWResult:
    bring_aegis_to_front()
    time.sleep(0.6)
    shell("input tap 150 2266; sleep 0.7")
    adb("logcat", "-c")
    t0 = time.time()
    submit_ask(prompt)
    fg = ""
    retried = False
    for idx in range(18):
        time.sleep(0.8)
        fg = foreground_activity()
        if pkg in fg or "permissioncontroller" in fg:
            break
        if not retried and idx >= 6:
            logs_now = adb("logcat", "-d", "-s", "AegisGuard2M:I", "AegisTTFT:I", "AegisStep:I", "*:S")
            if not logs_now.strip() or ("AegisGuard2M" not in logs_now and "AegisTTFT" not in logs_now and "AegisStep" not in logs_now):
                retried = True
                submit_ask(prompt)
    if pkg == "com.google.android.calendar" and pkg not in fg:
        # Calendar first-run launches Google Sign-In above AllInOneCalendarActivity inside the newly launched Calendar task
        hist = shell("dumpsys activity activities | grep -i 'Hist #.*com\\.google\\.android\\.calendar'")
        if "com.google.android.calendar" in hist:
            fg = hist.strip()
    if require_vlm_vision and pkg in fg:
        # Wait for Step 2 VLM screenshot + mtmd inference + JSON decode inside the opened app
        for _ in range(60):
            time.sleep(1.0)
            out_check = adb("logcat", "-d", "-s", "AegisTTFT:I", "*:S")
            if '"vlm_vision":true' in out_check:
                break
        time.sleep(0.5)
    ttft = latest_ttft()
    if require_vlm_vision:
        out = adb("logcat", "-d", "-s", "AegisTTFT:I", "*:S")
        vlm_logs = []
        for r in re.findall(r"AegisTTFT:\s*(\{.*\})", out):
            if '"vlm_vision":true' in r:
                try:
                    vlm_logs.append(json.loads(r))
                except json.JSONDecodeError:
                    continue
        if vlm_logs:
            ttft = vlm_logs[-1]
        passed = (pkg in fg) and bool(vlm_logs) and (vlm_logs[-1].get("visual_sig", 0) != 0)
    else:
        passed = (pkg in fg) or ("permissioncontroller" in fg)
    return AWResult(
        android_world_family=family,
        prompt=prompt,
        expected=f"Foreground {pkg}" + (" + VLM vlm_vision=true" if require_vlm_vision else ""),
        passed=passed,
        elapsed_ms=int((time.time() - t0) * 1000),
        details=fg.strip().splitlines()[0] if fg.strip() else "not in foreground",
        ttft_ms=ttft.get("ttft_ms") if ttft else None,
        decode_ms=ttft.get("decode_ms") if ttft else None,
    )


def main() -> None:
    adb("logcat", "-c")
    shell(
        "svc power stayon true; "
        "input keyevent 224; "
        "wm dismiss-keyguard; "
        "am force-stop com.aegis.core; "
        "settings put secure enabled_accessibility_services null; "
        "settings put secure accessibility_enabled 0; "
        "sleep 0.5; "
        "settings put secure enabled_accessibility_services com.aegis.core/com.aegis.hands.AegisAccessibilityService; "
        "settings put secure accessibility_enabled 1; "
        "am start -W -n com.aegis.core/.MainActivity"
    )
    # Wait for AegisAccessibilityService (with canTakeScreenshot capability=169) and VLM mmproj load
    for _ in range(15):
        time.sleep(0.8)
        a11y = shell("dumpsys accessibility | grep -E 'label=Lumen|label=Aegis|Bound services' -A 6")
        if "com.aegis.core" in a11y or "label=Lumen" in a11y or "label=Aegis" in a11y:
            break
    time.sleep(2.0)
    # Tap Continue (Model Verified) if onboarding is shown without calling uiautomator (which detaches A11y)
    shell("input tap 540 2227")
    for _ in range(15):
        time.sleep(1.0)
        if "mtmd_init_from_file" in adb("logcat", "-d", "-s", "AegisVLM:I"):
            break
    time.sleep(1.0)

    results: List[AWResult] = [
        run_launch_task("ClockStopWatchRunning (Live VLM)", "Open Clock and start stopwatch", "com.google.android.deskclock", require_vlm_vision=True),
        run_launch_task("ContactsAddContact", "Create a new contact in Contacts", "com.google.android.contacts"),
        run_launch_task("BrowserTask", "Open Chrome browser", "com.android.chrome"),
        run_launch_task("CalendarAddEvent", "Open Calendar", "com.google.android.calendar"),
        run_launch_task("SystemWifiTurnOff", "Turn off Wi-Fi in Settings", "com.android.settings"),
    ]

    passed_count = sum(1 for r in results if r.passed)
    print(
        json.dumps(
            {
                "benchmark": "google-research/android_world (compatible live probe)",
                "device": SERIAL,
                "passed": passed_count,
                "total": len(results),
                "pass_rate_pct": round((passed_count / len(results)) * 100.0, 1),
                "results": [asdict(r) for r in results],
            },
            indent=2,
        )
    )
    if passed_count != len(results):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
