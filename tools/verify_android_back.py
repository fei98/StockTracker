"""Read-only device regressions for root, nested, and dialog back navigation."""

import argparse
import json
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET


PACKAGE = "com.example.stocktracker"
ACTIVITY = PACKAGE + "/.MainActivity"


class Device:
    def __init__(self, adb, serial, package=PACKAGE, activity=PACKAGE + ".MainActivity"):
        self.adb, self.serial = adb, serial
        self.package, self.activity = package, package + "/" + activity
        size = self.run("shell", "wm", "size")
        self.width, self.height = map(int, re.findall(r"(\d+)x(\d+)", size)[-1])

    def run(self, *args, timeout=20):
        result = subprocess.run([self.adb, "-s", self.serial, *args], capture_output=True,
                                text=True, encoding="utf-8", errors="replace", timeout=timeout)
        if result.returncode:
            raise RuntimeError(f"ADB command failed: {args[:3]}: {result.stderr[:200]}")
        return result.stdout

    def foreground(self):
        output = self.run("shell", "dumpsys", "window")
        focus = next((line for line in output.splitlines() if "mCurrentFocus=" in line), "")
        match = re.search(r"\s([A-Za-z0-9_.]+)/[A-Za-z0-9_.$]+", focus)
        return match.group(1) if match else "unknown"

    def wait(self, predicate, reason, timeout=8):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if predicate():
                return
            time.sleep(.25)
        raise AssertionError(reason)

    def hierarchy(self):
        path = "/data/local/tmp/stocktracker-back-test.xml"
        for attempt in range(3):
            self.run("shell", "uiautomator", "dump", path)
            raw = self.run("exec-out", "cat", path)
            try:
                return ET.fromstring(raw)
            except ET.ParseError:
                if attempt == 2:
                    raise
                time.sleep(.2)

    def contains(self, text):
        return any(node.get("text") == text or node.get("content-desc") == text for node in self.hierarchy().iter("node"))

    def tap(self, text, bottom=False):
        candidates = []
        for node in self.hierarchy().iter("node"):
            if node.get("text") != text and node.get("content-desc") != text:
                continue
            values = list(map(int, re.findall(r"\d+", node.get("bounds", ""))))
            if len(values) == 4:
                candidates.append(((values[0] + values[2]) // 2, (values[1] + values[3]) // 2))
        if not candidates:
            raise AssertionError(f"Navigation control was not found: {text}")
        x, y = max(candidates, key=lambda point: point[1]) if bottom else candidates[0]
        self.run("shell", "input", "tap", str(x), str(y))
        time.sleep(.35)

    def launch(self, cold=True):
        if cold:
            self.run("shell", "am", "force-stop", self.package)
        self.run("shell", "am", "start", "-W", "-n", self.activity)
        self.wait(lambda: self.foreground() == self.package, "The app did not reach the foreground.")
        self.wait(lambda: self.contains("炒股记账本"), "The home screen did not become ready.")

    def gesture(self):
        self.run("shell", "input", "swipe", "1", str(self.height // 2),
                 str(self.width * 2 // 5), str(self.height // 2), "350")
        time.sleep(.5)

    def left_app(self):
        self.wait(lambda: self.foreground() not in (self.package, "unknown"), "Back did not leave the app.")
        # Detect a delayed bounce into the activity after the first successful transition.
        for _ in range(4):
            time.sleep(.3)
            if self.foreground() == self.package:
                raise AssertionError("The app returned to the foreground without a launch request.")


def verify(device):
    results = []
    for iteration in range(3):
        device.launch(cold=iteration == 0)
        device.gesture()
        device.left_app()
    results.append({"test": "root_gesture_exit_and_reenter", "iterations": 3, "passed": True})
    device.launch(cold=False)
    device.run("shell", "input", "keyevent", "4")
    device.left_app()
    results.append({"test": "root_back_key_exit", "passed": True})
    device.launch()
    device.tap("我的", bottom=True)
    device.tap("设置")
    device.wait(lambda: device.contains("外观"), "Settings did not open.")
    device.gesture()
    device.wait(lambda: device.contains("关于") and not device.contains("外观"), "Nested back skipped the Mine menu.")
    device.gesture()
    device.wait(lambda: device.contains("炒股记账本"), "Mine menu back did not return home.")
    if device.foreground() != device.package:
        raise AssertionError("A nested back unexpectedly left the app.")
    results.append({"test": "nested_settings_then_home", "passed": True})
    device.tap("我的", bottom=True)
    device.tap("关于")
    device.wait(lambda: device.contains("关闭"), "The About dialog did not open.")
    device.gesture()
    device.wait(lambda: not device.contains("关闭") and device.contains("设置"), "Dialog back did not dismiss only the dialog.")
    if device.foreground() != device.package:
        raise AssertionError("Dismissing a dialog unexpectedly left the app.")
    results.append({"test": "dialog_gesture_dismiss_only", "passed": True})
    device.tap("首页", bottom=True)
    device.wait(lambda: device.contains("炒股记账本"), "Home did not reopen.")
    return results


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", default=r"D:\AndroidSDK\platform-tools\adb.exe")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--package", default=PACKAGE)
    parser.add_argument("--activity", default=PACKAGE + ".MainActivity")
    parser.add_argument("--output", default=".gradle/device-verification/back-navigation.json")
    args = parser.parse_args()
    device = Device(args.adb, args.serial, args.package, args.activity)
    results = verify(device)
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps({"package": args.package, "results": results}, indent=2), encoding="utf-8")
    print(json.dumps({"passed": len(results), "results": results, "report": str(output.resolve())}))


if __name__ == "__main__":
    main()
