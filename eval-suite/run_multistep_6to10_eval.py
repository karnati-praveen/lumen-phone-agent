#!/usr/bin/env python3
"""
Multi-Step (6-10 Steps) Real-App Benchmark for Aegis on Pixel_9 (emulator-5554).

Verifies that Aegis does NOT stop at just opening apps, and instead:
  1. Opens the target app (Step 1)
  2. Runs SmolVLM-500M VLM on live AccessibilityService screenshots (vlm_vision=true, visual_sig != 0)
  3. Executes 5-10 real grounded UI steps inside the target app (clicks tabs, types into labeled fields,
     submits search / saves forms, scrolls) with closed-loop state verification!
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


def current_focus() -> str:
    return shell("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'")


def foreground_activity() -> str:
    return shell("dumpsys activity activities | grep -i 'topResumedActivity'")


def dump_ui_text_blob() -> str:
    xml_raw = ""
    for attempt in range(5):
        shell("rm -f /sdcard/ms_ui.xml; uiautomator dump --compressed /sdcard/ms_ui.xml >/dev/null 2>&1 || uiautomator dump /sdcard/ms_ui.xml >/dev/null 2>&1")
        xml_raw = shell("cat /sdcard/ms_ui.xml 2>/dev/null")
        if "<?xml" in xml_raw and "</hierarchy>" in xml_raw:
            break
        if attempt == 2:
            # uiautomator conflicts with an active AccessibilityService during Chrome animations
            shell("settings put secure enabled_accessibility_services null; settings put secure accessibility_enabled 0")
        time.sleep(1.5)
    s = xml_raw.find("<?xml")
    e = xml_raw.rfind("</hierarchy>")
    if s == -1 or e == -1:
        top_dump = shell("dumpsys activity top | grep -E 'url_bar|WebView|weather|search|toolbar' | head -n 40")
        return top_dump.strip()
    root = ET.fromstring(xml_raw[s : e + len("</hierarchy>")])
    vals = []
    for n in root.iter("node"):
        for k in ("text", "content-desc"):
            v = (n.attrib.get(k) or "").strip()
            if v:
                vals.append(v)
    return " | ".join(vals)


def bring_aegis_to_front() -> None:
    shell(
        "am force-stop com.google.android.deskclock; "
        "am force-stop com.google.android.contacts; "
        "am force-stop com.android.chrome; "
        "am force-stop com.android.settings; "
        "am force-stop com.google.android.settings.intelligence; "
        "am force-stop com.google.android.gms; "
        "am force-stop com.google.android.youtube; "
        "am force-stop com.android.vending; "
        "am force-stop com.google.android.googlequicksearchbox; "
        "am start -W -f 0x10200000 -n com.aegis.core/.MainActivity"
    )
    for _ in range(15):
        time.sleep(0.35)
        fg = foreground_activity()
        if "com.aegis.core" in fg:
            break
        if "youtube" in fg or "vending" in fg:
            shell("input keyevent 4; am force-stop com.google.android.youtube; am force-stop com.android.vending; am start -W -f 0x10200000 -n com.aegis.core/.MainActivity")
    time.sleep(0.6)
    shell("input tap 150 2266; sleep 0.5")


def ensure_aegis_fg() -> None:
    for _ in range(8):
        focus = current_focus()
        if "Application Error" in focus or "Gboard" in focus:
            shell("am force-stop com.google.android.inputmethod.latin; input keyevent 4; sleep 0.4")
            focus = current_focus()
        if "com.aegis.core/com.aegis.core.MainActivity" in focus:
            return
        shell(
            "am force-stop com.android.vending; "
            "am force-stop com.google.android.inputmethod.latin; "
            "am start -W -f 0x10200000 -n com.aegis.core/.MainActivity; "
            "sleep 0.7"
        )


def submit_ask(cmd_text: str) -> None:
    ensure_aegis_fg()
    if "mInputShown=true" in shell("dumpsys input_method | grep mInputShown"):
        shell("input keyevent 4; sleep 0.4")
        ensure_aegis_fg()
    shell("input tap 150 2266; sleep 0.4")
    encoded = cmd_text.replace(" ", "%s")
    shell("input tap 550 2062; sleep 1.4")
    focus_now = current_focus()
    if "Application Error" in focus_now or "Gboard" in focus_now or "com.aegis.core" not in focus_now:
        shell("am force-stop com.google.android.inputmethod.latin; input keyevent 4; sleep 0.5")
        ensure_aegis_fg()
        shell("input tap 550 2062; sleep 0.9")
    shell(f"input text '{encoded}'; sleep 0.5; input keyevent 66")


def rebind_accessibility() -> None:
    shell(
        "settings put secure enabled_accessibility_services null; "
        "settings put secure accessibility_enabled 0; "
        "sleep 0.4; "
        "settings put secure enabled_accessibility_services com.aegis.core/com.aegis.hands.AegisAccessibilityService; "
        "settings put secure accessibility_enabled 1"
    )
    for _ in range(15):
        time.sleep(0.5)
        bound = shell("dumpsys accessibility | grep -A 6 'Bound services'")
        if "com.aegis.core" in bound:
            time.sleep(1.0)
            break


@dataclass
class MultiStepResult:
    task: str
    target_pkg: str
    prompt: str
    step_count_observed: int
    vlm_vision_used: bool
    goal_state_verified: bool
    passed: bool
    elapsed_ms: int
    verification_evidence: str


def run_multistep_task(
    name: str,
    prompt: str,
    target_pkg: str,
    expected_markers: List[str],
    max_wait_s: int = 65,
) -> MultiStepResult:
    bring_aegis_to_front()
    rebind_accessibility()
    if "com.aegis.core" not in foreground_activity():
        bring_aegis_to_front()
    adb("logcat", "-c")
    t0 = time.time()
    submit_ask(prompt)

    steps_seen = 0
    vlm_used = False
    done_seen = False
    step_lines: List[str] = []
    poll_s = 2.0
    retried_submit = False
    for poll_idx in range(max(1, int(max_wait_s / poll_s))):
        time.sleep(poll_s)
        logs = adb("logcat", "-d", "-s", "AegisTTFT:I", "AegisGuard2M:I", "AegisVLM:I", "AegisStep:I", "*:S", timeout=60)
        ttft_jsons = re.findall(r"AegisTTFT:\s*(\{.*\})", logs)
        guard_jsons = re.findall(r"AegisGuard2M:\s*(\{.*\})", logs)
        step_lines = re.findall(r"AegisStep:\s*(.+)", logs)
        if not retried_submit and poll_idx >= 10 and not (ttft_jsons or guard_jsons or step_lines) and "com.aegis.core" in current_focus():
            retried_submit = True
            submit_ask(prompt)
        if any('"vlm_vision":true' in x for x in ttft_jsons):
            vlm_used = True
        non_done_steps = [s for s in step_lines if not s.startswith("DONE:")]
        steps_seen = max(steps_seen, len(guard_jsons), len(non_done_steps))
        if any("visual_sig" in x and '"visual_sig":0' not in x for x in ttft_jsons):
            vlm_used = True
        if any(s.startswith("DONE:") for s in step_lines):
            done_seen = True
            break

    fg_now = foreground_activity()
    app_in_fg = target_pkg in fg_now
    # Verify actual app UI state via uiautomator dump AFTER steps finished
    ui_blob = dump_ui_text_blob().lower()
    matched_markers = [m for m in expected_markers if m.lower() in ui_blob]
    goal_verified = app_in_fg and bool(matched_markers)
    passed = goal_verified and vlm_used and steps_seen >= 6

    res = MultiStepResult(
        task=name,
        target_pkg=target_pkg,
        prompt=prompt,
        step_count_observed=steps_seen,
        vlm_vision_used=vlm_used,
        goal_state_verified=goal_verified,
        passed=passed,
        elapsed_ms=int((time.time() - t0) * 1000),
        verification_evidence=f"done={done_seen}, fg={target_pkg in fg_now}, markers={matched_markers}, steps={step_lines}, ui_sample={ui_blob[:160]}",
    )
    print(f"[{name}] passed={res.passed} steps={res.step_count_observed} vlm={res.vlm_vision_used} fg={app_in_fg} ms={res.elapsed_ms}", flush=True)
    return res


def main() -> None:
    adb("logcat", "-c")
    shell("svc power stayon true; input keyevent 224; wm dismiss-keyguard; am force-stop com.aegis.core; am start -W -n com.aegis.core/.MainActivity")
    rebind_accessibility()
    time.sleep(3.5)
    shell("input tap 540 2227; sleep 1.0")
    for _ in range(25):
        time.sleep(1.0)
        if "mtmd_init_from_file" in adb("logcat", "-d", "-s", "AegisVLM:I"):
            time.sleep(2.5)
            break
    shell("input tap 540 2227; sleep 0.5; input tap 150 2266; sleep 0.5")

    tasks = [
        run_multistep_task(
            "DeskClock Stopwatch 6-Step Workflow",
            "Open Clock, switch to Stopwatch, start stopwatch, lap, lap, pause, reset, and verify",
            "com.google.android.deskclock",
            ["stopwatch", "start"],
            max_wait_s=150,
        ),
        run_multistep_task(
            "Contacts Create & Save 6+ Step Workflow",
            "Open Contacts, create contact named Alice Smith with phone 5550199, save, and verify",
            "com.google.android.contacts",
            ["alice", "555", "smith", "edit"],
            max_wait_s=100,
        ),
        run_multistep_task(
            "Chrome Search & Scroll 6-Step Workflow",
            "Open Chrome, search weather today, scroll down, scroll down, and verify",
            "com.android.chrome",
            ["weather", "chrome", "search", "results"],
            max_wait_s=200,
        ),
        run_multistep_task(
            "Settings Network & Internet 6-Step Workflow",
            "Open Settings, go to Network, open Internet, scroll down, go back, and verify",
            "com.android.settings",
            ["wi-fi", "internet", "network", "airplane", "settings"],
            max_wait_s=140,
        ),
    ]

    passed_n = sum(1 for r in tasks if r.passed)
    print(
        json.dumps(
            {
                "benchmark": "Aegis Multi-Step (6-10 Step) Live App Benchmark",
                "device": SERIAL,
                "passed": passed_n,
                "total": len(tasks),
                "accuracy_pct": round((passed_n / len(tasks)) * 100.0, 1),
                "results": [asdict(r) for r in tasks],
            },
            indent=2,
        )
    )
    if passed_n != len(tasks):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
