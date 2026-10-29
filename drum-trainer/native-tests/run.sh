#!/usr/bin/env bash
# Builds and runs the host-side native tests.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p build
g++ -std=c++17 -O2 -Wall -Wextra -o build/onset_test onset_test.cpp ../app/src/main/cpp/OnsetDetector.cpp
./build/onset_test
