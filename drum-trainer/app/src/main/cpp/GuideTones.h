#pragma once

#include <cmath>
#include <vector>

#include "OnsetDetector.h"

// Tonal "guide" sounds for playing along through the phone speaker. Each is a pure
// tone on one of the frequencies the onset detector notches out (the metronome click
// frequencies and their octaves), so the microphone does not report them as hits and
// only the player's drums are measured.
namespace guide {

constexpr int kNumTones = 4;
// Low to high: kick, snare/toms, hi-hat/ride, crash.
constexpr double kFreqs[kNumTones] = {kClickFreqNormal, kClickFreqAccent, 2 * kClickFreqNormal,
                                      2 * kClickFreqAccent};

inline std::vector<float> tone(int sr, double freq) {
    const int len = sr * 90 / 1000;
    // A soft attack keeps the tone's energy inside the notch: with 10 ms and the detector's
    // double notch the tones are suppressed by 24-39 dB (3 ms leaked through at -7 dB).
    const int attack = sr * 10 / 1000;
    std::vector<float> v(len);
    for (int i = 0; i < len; ++i) {
        double t = static_cast<double>(i) / sr;
        double env = i < attack ? 0.5 - 0.5 * std::cos(M_PI * i / attack) : 1.0;
        env *= std::exp(-t / 0.015);
        v[i] = static_cast<float>(0.5 * env * std::sin(2.0 * M_PI * freq * t));
    }
    return v;
}

}  // namespace guide
