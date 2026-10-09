#!/usr/bin/env python3
"""Run a device probe, with an optional temporary thaw of only the test APK's UID."""

import argparse
import datetime
import json
from pathlib import Path
import re
import shlex
import subprocess
import threading
import time

PACKAGE = "io.github.ccc007ccc.chromextextensions.ipcprobe"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--native-ui", action="store_true")
    parser.add_argument("--prepare", action="store_true", help="Warm the module app and open Chrome before the probe")
    parser.add_argument("--restart-chrome", action="store_true", help="Reload an updated module APK into Chrome")
    parser.add_argument("--system-picker", action="store_true", help="Use the system picker once; default uses a fixed fixture file")
    parser.add_argument("--report", type=Path, help="Write a machine-readable result, including failures")
    parser.add_argument("--thaw-test-uid", action="store_true",
                        help="Root-only workaround for OEMs freezing the instrumentation APK; never thaws Chrome")
    args = parser.parse_args()
    adb = [args.adb, "-s", args.serial]
    if args.restart_chrome:
        subprocess.run(adb + ["shell", "am", "force-stop", "com.android.chrome"], check=True, timeout=10)
    if args.prepare:
        for component in [
            "io.github.ccc007ccc.chromextextensions/org.matrix.chromext.manager.ExtensionManagerActivity",
            "com.android.chrome/com.google.android.apps.chrome.Main",
        ]:
            subprocess.run(adb + ["shell", "am", "start", "-W", "-n", component], check=True, timeout=15,
                           stdout=subprocess.DEVNULL)
    uid = None
    if args.thaw_test_uid:
        listing = subprocess.check_output(adb + ["shell", "pm", "list", "packages", "-U", PACKAGE], text=True)
        match = re.search(r"^package:" + re.escape(PACKAGE) + r" uid:(\d+)$", listing, re.MULTILINE)
        if not match or int(match[1]) < 10000:
            raise RuntimeError("Cannot verify the test APK's UID")
        uid = int(match[1])
    runner = "NativeManagerProbe" if args.native_ui else "UnauthorizedBrokerProbe"
    process = subprocess.Popen(adb + ["shell", "am", "instrument", "-w", "-r", "-e", "mode", "picker" if args.system_picker else "flow",
                                    PACKAGE + "/org.matrix.chromext.probe." + runner],
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    lines = []

    def read_output():
        for line in process.stdout:
            print(line, end="", flush=True)
            lines.append(line)

    reader = threading.Thread(target=read_output, daemon=True)
    reader.start()
    started = datetime.datetime.now(datetime.timezone.utc).isoformat()
    success = False
    deadline = time.monotonic() + 60
    try:
        while process.poll() is None:
            if time.monotonic() >= deadline:
                raise TimeoutError("Device probe exceeded 60 seconds")
            if uid is not None:
                path = f"/sys/fs/cgroup/apps/uid_{uid}/cgroup.freeze"
                # This changes no power settings. The test process is killed when instrumentation ends.
                command = f'if [ -r {path} ] && [ "$(cat {path})" = 1 ]; then printf 0 > {path}; fi'
                subprocess.run(adb + ["shell", "su -c " + shlex.quote(command)],
                               check=True, timeout=5, stdout=subprocess.DEVNULL)
            time.sleep(0.25)
        if process.returncode:
            raise RuntimeError(f"ADB instrumentation exited with {process.returncode}")
        reader.join(timeout=5)
        if "INSTRUMENTATION_CODE: -1\n" not in lines:
            raise RuntimeError("Instrumentation did not report success")
        success = True
    finally:
        if process.poll() is None:
            subprocess.run(adb + ["shell", "am", "force-stop", PACKAGE], timeout=5, check=True)
            process.wait(timeout=5)
        diagnostic = None
        if args.native_ui:
            try:
                diagnostic = subprocess.run(adb + ["shell", "am", "broadcast", "-a",
                    "io.github.ccc007ccc.chromextextensions.DEBUG", "-p", "com.android.chrome",
                    "--es", "operation", "status"], capture_output=True, text=True, timeout=5).stdout.strip()
                print(diagnostic, flush=True)
            except subprocess.TimeoutExpired:
                diagnostic = "Chrome diagnostics did not respond within five seconds"
        if args.report:
            args.report.parent.mkdir(parents=True, exist_ok=True)
            args.report.write_text(json.dumps({"probe": runner, "started": started, "passed": success,
                                              "oemTestUidThaw": args.thaw_test_uid,
                                              "systemPicker": args.system_picker,
                                              "browserDiagnostic": diagnostic,
                                              "output": "".join(lines)}, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    main()
