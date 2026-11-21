#!/usr/bin/env bash
set -euo pipefail
task_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
task_reference="$task_root/Refrence/Ferrous"
task_commit=e30ada691c251d22781115d55d263bdb9bfa8367
if [[ -e "$task_reference" ]]; then
    echo "Existing reference kept at $task_reference"
    git -C "$task_reference" rev-parse HEAD
    exit 0
fi
git clone --no-checkout https://github.com/JuanSoFly/Ferrous.git "$task_reference"
git -C "$task_reference" checkout --detach "$task_commit"
