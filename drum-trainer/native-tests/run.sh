#!/usr/bin/env bash
# Builds and runs the host-side native tests, and regenerates the feature
# fixture used by the Kotlin classifier tests.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p build
SRC=../app/src/main/cpp/OnsetDetector.cpp
g++ -std=c++17 -O2 -Wall -Wextra -o build/onset_test onset_test.cpp "$SRC"
./build/onset_test
g++ -std=c++17 -O2 -Wall -Wextra -o build/features_gen features_gen.cpp "$SRC"
mkdir -p ../app/src/test/resources
./build/features_gen ../app/src/test/resources/synthetic_hits.json
