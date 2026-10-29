#pragma once

#include <algorithm>
#include <cmath>
#include <vector>

// Bass and chord backing for free play. Notes start softly (tens of milliseconds) so
// the onset detector, which listens for sharp attacks, does not take the backing for
// the player's drums when it comes out of the phone speaker.
namespace backing {

enum Instrument : int { kBass = 0, kPad = 1 };

struct Note {
    int start;   // frame within the loop
    int length;  // frames
    int midi;
    float gain;
    int instrument;
};

constexpr double kBassAttackMs = 40.0;
constexpr double kPadAttackMs = 120.0;

inline double midiToHz(int midi) { return 440.0 * std::pow(2.0, (midi - 69) / 12.0); }

// Renders the notes into a loop of loopFrames; notes running past the end wrap around
// so the loop repeats seamlessly.
inline std::vector<float> render(const std::vector<Note> &notes, int loopFrames, int sr) {
    std::vector<float> out(static_cast<size_t>(std::max(loopFrames, 1)), 0.0f);
    for (const Note &n : notes) {
        const bool bass = n.instrument == kBass;
        const double f = midiToHz(n.midi);
        const int attack = static_cast<int>(sr * (bass ? kBassAttackMs : kPadAttackMs) / 1000.0);
        const int release = static_cast<int>(sr * (bass ? 0.06 : 0.2));
        const int total = n.length + release;
        for (int i = 0; i < total; ++i) {
            double t = static_cast<double>(i) / sr;
            double env = i < attack ? 0.5 - 0.5 * std::cos(M_PI * i / attack) : 1.0;
            env *= bass ? std::exp(-t / 0.8) : 0.8;
            if (i >= n.length) env *= 1.0 - static_cast<double>(i - n.length) / release;
            double s;
            if (bass) {
                double ph = 2.0 * M_PI * f * t;
                s = std::sin(ph) + 0.3 * std::sin(2 * ph) + 0.1 * std::sin(3 * ph);
            } else {
                // Two slightly detuned voices for a soft chorus.
                double ph1 = 2.0 * M_PI * f * t, ph2 = 2.0 * M_PI * f * 1.003 * t;
                s = 0.5 * (std::sin(ph1) + std::sin(ph2)) + 0.15 * std::sin(2 * ph1);
            }
            size_t idx = static_cast<size_t>((n.start + i) % loopFrames);
            out[idx] += static_cast<float>(n.gain * env * s);
        }
    }
    return out;
}

}  // namespace backing
