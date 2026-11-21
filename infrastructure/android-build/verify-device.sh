#!/usr/bin/env bash
set -euo pipefail
task_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
task_serial="${1:-emulator-5554}"
if [[ "$task_serial" != emulator-* ]]; then
    echo 'This layout matrix only changes emulator display settings. Pass an emulator serial.' >&2
    exit 1
fi
adb -s "$task_serial" get-state >/dev/null
task_old_size="$(adb -s "$task_serial" shell wm size | sed -n 's/Override size: //p' | tr -d '\r')"
task_old_density="$(adb -s "$task_serial" shell wm density | sed -n 's/Override density: //p' | tr -d '\r')"
restore_display() {
    adb -s "$task_serial" shell wm size "${task_old_size:-reset}" >/dev/null
    adb -s "$task_serial" shell wm density "${task_old_density:-reset}" >/dev/null
}
trap restore_display EXIT
cd "$task_root"
mkdir -p artifacts/verification
android/gradlew -p android assembleDebug assembleDebugAndroidTest
adb -s "$task_serial" install -r android/app/build/outputs/apk/debug/app-debug.apk
adb -s "$task_serial" install -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
run_test() {
    local task_class="$1"
    local task_report="$2"
    adb -s "$task_serial" shell am instrument -w -r -e class "$task_class" \
        dev.entangle.app.test/androidx.test.runner.AndroidJUnitRunner | tee "$task_report"
    rg -q '^OK \(' "$task_report" || { echo "Test failed: $task_report" >&2; exit 1; }
}
adb -s "$task_serial" shell wm size 1080x1920
adb -s "$task_serial" shell wm density 420
run_test 'dev.entangle.app.NativeIntegrationTest,dev.entangle.app.DocumentImportTest,dev.entangle.app.KnowledgeAssistantTest,dev.entangle.app.ReaderAnnotationTest,dev.entangle.app.ReferenceLayoutTest#compactSheetPreviewAndDismissPreserveReaderScroll' artifacts/verification/emulator-phone.txt
adb -s "$task_serial" shell wm size 1920x1200
adb -s "$task_serial" shell wm density 240
run_test 'dev.entangle.app.ReferenceLayoutTest#expandedSidePanelPreviewAndCollapsePreserveReaderScroll' artifacts/verification/emulator-tablet.txt
