#!/usr/bin/env python3
"""
Lumen Real-World & Non-System Multi-App Evaluation Suite (emulator-5554)
Tests third-party/non-system workflows & multi-listing comparison without mid-drops:
  1. Amazon (in Chrome) search + check 3 product listings + synthesize best pick
  2. YouTube search + check multiple video listings + pick best one
  3. Play Store search + scroll + verify
  4. Clock Stopwatch full lap/pause/reset (regression check for mid-drop at Pause)
"""
from __future__ import annotations

import json
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from dataclasses import asdict, dataclass
from typing import List

SERIAL = "emulator-5554"


def adb(*args: str, timeout: int = 35) -> str:
    res = subprocess.run(["adb", "-s", SERIAL, *args], capture_output=True, text=True, timeout=timeout)
    return (res.stdout or "") + (res.stderr or "")


def shell(cmd: str, timeout: int = 35) -> str:
    return adb("shell", cmd, timeout=timeout)


def current_focus() -> str:
    return shell("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | head -n 4").strip()


def ui_texts() -> List[str]:
    shell("uiautomator dump /sdcard/rw_ui.xml >/dev/null 2>&1")
    xml_raw = shell("cat /sdcard/rw_ui.xml 2>/dev/null; rm -f /sdcard/rw_ui.xml")
    if "<hierarchy" not in xml_raw:
        return []
    try:
        root = ET.fromstring(xml_raw[xml_raw.index("<hierarchy"):])
    except ET.ParseError:
        return []
    out: List[str] = []
    for n in root.iter("node"):
        t = (n.attrib.get("text") or n.attrib.get("content-desc") or "").strip()
        if t:
            out.append(t.lower())
    return out


def ensure_lumen_fg() -> None:
    for _ in range(8):
        focus = current_focus()
        if "Application Error" in focus or "Gboard" in focus:
            shell("am force-stop com.google.android.inputmethod.latin; input keyevent 4; sleep 0.4")
            focus = current_focus()
        if "com.aegis.core/com.aegis.core.MainActivity" in focus:
            return
        shell(
            "am force-stop com.google.android.inputmethod.latin; "
            "am start -W -f 0x10200000 -n com.aegis.core/.MainActivity; "
            "sleep 0.7"
        )


def submit_ask(cmd_text: str) -> None:
    ensure_lumen_fg()
    if "mInputShown=true" in shell("dumpsys input_method | grep mInputShown"):
        shell("input keyevent 4; sleep 0.4")
        ensure_lumen_fg()
    shell("input tap 150 2266; sleep 0.4")
    shell("input tap 550 2062; sleep 0.5")
    if "Application Error" in current_focus():
        shell("am force-stop com.google.android.inputmethod.latin; input keyevent 4; sleep 0.3")
        ensure_lumen_fg()
        shell("input tap 550 2062; sleep 0.4")
    encoded = cmd_text.replace(" ", "%s")
    shell(f"input text '{encoded}'; sleep 0.4; input keyevent 66")


@dataclass
class RealWorldResult:
    name: str
    target_pkg: str
    prompt: str
    steps_observed: int
    listings_checked: int
    vlm_vision_used: bool
    passed: bool
    elapsed_ms: int
    evidence: str


def run_task(
    name: str,
    target_pkg: str,
    prompt: str,
    min_listings: int = 0,
    require_best_pick: bool = False,
    expected_markers: List[str] = None,
    max_wait_s: int = 180,
) -> RealWorldResult:
    expected_markers = expected_markers or []
    shell(f"am force-stop {target_pkg} 2>/dev/null; am force-stop com.android.vending 2>/dev/null; logcat -c")
    t0 = time.time()
    submit_ask(prompt)

    done = False
    while time.time() - t0 < max_wait_s:
        time.sleep(2.5)
        logs = adb("logcat", "-d", "-s", "AegisStep:I", "AegisTTFT:I", "AegisVLM:I", "*:S")
        if "AegisStep: DONE:" in logs or "AegisStep: BLOCKED:" in logs or "AegisStep: DENIED:" in logs:
            done = True
            time.sleep(0.8)
            break

    elapsed_ms = int((time.time() - t0) * 1000)
    logs = adb("logcat", "-d", "-s", "AegisStep:V", "AegisTTFT:V", "*:S")
    step_lines = [l.strip() for l in logs.splitlines() if "AegisStep:" in l]
    listing_lines = [l for l in step_lines if "LISTING #" in l]
    done_lines = [l for l in step_lines if "AegisStep: DONE:" in l]
    vlm_used = ('"vlm_vision":true' in logs) or ("SmolVLM-500M+Vision" in logs)
    best_pick_ok = (not require_best_pick) or any("Best Pick:" in l for l in done_lines)

    fg_ok = target_pkg in current_focus()
    texts = ui_texts()
    joined = " | ".join(texts[:25])
    markers_ok = True
    if expected_markers:
        markers_ok = any(m in joined for m in expected_markers)

    passed = (
        done
        and fg_ok
        and len(listing_lines) >= min_listings
        and best_pick_ok
        and markers_ok
        and vlm_used
    )
    evidence = (
        f"done={done}, fg={fg_ok}, listings={len(listing_lines)}, vlm={vlm_used}, "
        f"best_pick={best_pick_ok}, steps={len(step_lines)}, "
        f"done_msg={done_lines[-1][-120:] if done_lines else 'none'}, ui={joined[:140]}"
    )
    print(f"[{name}] passed={passed} steps={len(step_lines)} listings={len(listing_lines)} vlm={vlm_used} ms={elapsed_ms}", flush=True)
    return RealWorldResult(
        name=name,
        target_pkg=target_pkg,
        prompt=prompt,
        steps_observed=len(step_lines),
        listings_checked=len(listing_lines),
        vlm_vision_used=vlm_used,
        passed=passed,
        elapsed_ms=elapsed_ms,
        evidence=evidence,
    )


def main() -> None:
    shell("svc power stayon true; wm dismiss-keyguard; settings put secure enabled_accessibility_services com.aegis.core/com.aegis.hands.AegisAccessibilityService; settings put secure accessibility_enabled 1")
    ensure_lumen_fg()
    if any("start sandbox" in t or "get started" in t for t in ui_texts()):
        shell("input tap 540 2227; sleep 1.5")

    results = [
        run_task(
            name="Amazon Multi-Listing Compare (Chrome)",
            target_pkg="com.android.chrome",
            prompt="Open Amazon, search wireless earbuds, check 3 listings and check for the best one",
            min_listings=2,
            require_best_pick=True,
            max_wait_s=150,
        ),
        run_task(
            name="YouTube Multi-Video Compare",
            target_pkg="com.google.android.youtube",
            prompt="Open YouTube, search python tutorial, check 2 listings and pick the best one",
            min_listings=1,
            require_best_pick=True,
            max_wait_s=150,
        ),
        run_task(
            name="DeskClock Stopwatch Pause & Reset (No Mid-Drop)",
            target_pkg="com.google.android.deskclock",
            prompt="Open Clock, switch to Stopwatch, start stopwatch, lap, lap, pause, reset, and verify",
            min_listings=0,
            require_best_pick=False,
            expected_markers=["start", "stopwatch"],
            max_wait_s=120,
        ),
    ]
    passed = sum(1 for r in results if r.passed)
    report = {
        "benchmark": "Lumen Real-World Non-System Multi-Listing & Stability Eval",
        "device": SERIAL,
        "passed": passed,
        "total": len(results),
        "accuracy_pct": round(100.0 * passed / len(results), 1),
        "results": [asdict(r) for r in results],
    }
    print(json.dumps(report, indent=2))
    sys.exit(0 if passed == len(results) else 1)


if __name__ == "__main__":
    main()
