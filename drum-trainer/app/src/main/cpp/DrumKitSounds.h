#pragma once

#include <cmath>
#include <cstdint>
#include <vector>

// Synthesized drum sounds for playing patterns back to the player. Indexes follow the
// Kotlin Pad enum: kick, snare, hi-hat, high tom, mid tom, floor tom, crash, ride.
namespace drumkit {

constexpr int kNumPads = 8;

class Noise {
public:
    explicit Noise(uint32_t seed) : s_(seed) {}
    float next() {
        s_ = s_ * 1664525u + 1013904223u;
        return static_cast<float>(s_ >> 8) / static_cast<float>(1 << 24) * 2.0f - 1.0f;
    }

private:
    uint32_t s_;
};

// One-pole high-pass, for the bright part of snares and cymbals.
class HighPass {
public:
    HighPass(float cutoff, int sr) : a_(std::exp(-2.0f * static_cast<float>(M_PI) * cutoff / sr)) {}
    float process(float x) {
        lp_ = (1 - a_) * x + a_ * lp_;
        return x - lp_;
    }

private:
    float a_, lp_ = 0;
};

inline void fadeOut(std::vector<float> &v, int sr) {
    size_t fade = std::min(v.size(), static_cast<size_t>(sr * 40 / 1000));
    for (size_t i = 0; i < fade; ++i) {
        v[v.size() - fade + i] *= 0.5f + 0.5f * std::cos(static_cast<float>(M_PI) * i / fade);
    }
}

inline std::vector<float> kick(int sr) {
    std::vector<float> v(sr * 350 / 1000);
    Noise n(1);
    double phase = 0;
    for (size_t i = 0; i < v.size(); ++i) {
        float t = static_cast<float>(i) / sr;
        float f = 50 + 110 * std::exp(-t / 0.025f);  // pitch drop gives the "thump"
        phase += 2 * M_PI * f / sr;
        v[i] = 0.9f * std::sin(phase) * std::exp(-t / 0.15f) + 0.25f * n.next() * std::exp(-t / 0.002f);
    }
    return v;
}

inline std::vector<float> snare(int sr) {
    std::vector<float> v(sr * 250 / 1000);
    Noise n(2);
    HighPass hp(1800, sr);
    for (size_t i = 0; i < v.size(); ++i) {
        float t = static_cast<float>(i) / sr;
        float body = 0.5f * std::sin(2 * M_PI * 190 * t) * std::exp(-t / 0.05f);
        float wires = 0.7f * hp.process(n.next()) * std::exp(-t / 0.07f);
        v[i] = body + wires;
    }
    return v;
}

inline std::vector<float> tom(int sr, float freq, uint32_t seed) {
    std::vector<float> v(sr * 400 / 1000);
    Noise n(seed);
    double phase = 0;
    for (size_t i = 0; i < v.size(); ++i) {
        float t = static_cast<float>(i) / sr;
        phase += 2 * M_PI * freq * (1 + 0.2f * std::exp(-t / 0.04f)) / sr;
        v[i] = 0.8f * std::sin(phase) * std::exp(-t / 0.2f) + 0.15f * n.next() * std::exp(-t / 0.008f);
    }
    return v;
}

inline std::vector<float> hihat(int sr) {
    std::vector<float> v(sr * 90 / 1000);
    Noise n(3);
    HighPass hp(7000, sr);
    for (size_t i = 0; i < v.size(); ++i) {
        float t = static_cast<float>(i) / sr;
        v[i] = 0.6f * hp.process(n.next()) * std::exp(-t / 0.02f);
    }
    return v;
}

inline std::vector<float> cymbal(int sr, bool ride) {
    std::vector<float> v(sr * (ride ? 800 : 1200) / 1000);
    Noise n(ride ? 5u : 4u);
    HighPass hp(ride ? 3500.0f : 2500.0f, sr);
    for (size_t i = 0; i < v.size(); ++i) {
        float t = static_cast<float>(i) / sr;
        float wash = hp.process(n.next()) * (ride ? 0.3f : 0.55f) * std::exp(-t / (ride ? 0.35f : 0.6f));
        float bell = ride ? 0.25f * std::sin(2 * M_PI * 640 * t) * std::exp(-t / 0.3f) : 0.0f;
        v[i] = wash + bell;
    }
    return v;
}

inline std::vector<std::vector<float>> build(int sr) {
    std::vector<std::vector<float>> s = {
        kick(sr), snare(sr), hihat(sr), tom(sr, 230, 6), tom(sr, 165, 7), tom(sr, 110, 8),
        cymbal(sr, false), cymbal(sr, true),
    };
    for (auto &v : s) fadeOut(v, sr);
    return s;
}

}  // namespace drumkit
