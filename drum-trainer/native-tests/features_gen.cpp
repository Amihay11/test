// Synthesizes hits of several drum types (with velocity, tuning and room
// variation), runs them through the real OnsetDetector and writes the extracted
// features as JSON. The Kotlin classifier tests use that file as a fixture, so
// the whole chain "audio -> native features -> classification" is exercised.
//
// Usage: features_gen <out.json>
#include <cmath>
#include <cstdio>
#include <random>
#include <string>
#include <vector>

#include "../app/src/main/cpp/OnsetDetector.h"

namespace {

constexpr int kSr = 48000;

struct Rng {
    std::mt19937 gen{42};
    float uni(float a, float b) { return std::uniform_real_distribution<float>(a, b)(gen); }
    float gauss() { return std::normal_distribution<float>(0.0f, 1.0f)(gen); }
};

// One-pole filters for shaping noise.
struct OnePole {
    float a, z = 0;
    OnePole(float cutoff) : a(std::exp(-2.0f * static_cast<float>(M_PI) * cutoff / kSr)) {}
    float lp(float x) { return z = (1 - a) * x + a * z; }
    float hp(float x) { return x - lp(x); }
};

using Voice = std::vector<float>;

Voice kick(Rng &r, float tune) {
    Voice v(kSr * 400 / 1000);
    float phase = 0;
    for (size_t i = 0; i < v.size(); ++i) {
        float t = static_cast<float>(i) / kSr;
        float f = (55 + 90 * std::exp(-t / 0.03f)) * tune;  // pitch drop
        phase += 2 * M_PI * f / kSr;
        float click = 0.3f * r.gauss() * std::exp(-t / 0.003f);
        v[i] = std::sin(phase) * std::exp(-t / 0.18f) + click;
    }
    return v;
}

Voice snare(Rng &r, float tune) {
    Voice v(kSr * 300 / 1000);
    OnePole hp(1500);
    for (size_t i = 0; i < v.size(); ++i) {
        float t = static_cast<float>(i) / kSr;
        float body = 0.6f * std::sin(2 * M_PI * 185 * tune * t) * std::exp(-t / 0.07f);
        float wires = 0.8f * hp.hp(r.gauss()) * std::exp(-t / 0.09f);
        v[i] = body + wires;
    }
    return v;
}

Voice tom(Rng &r, float freq, float tune) {
    Voice v(kSr * 500 / 1000);
    for (size_t i = 0; i < v.size(); ++i) {
        float t = static_cast<float>(i) / kSr;
        float f = freq * tune * (1 + 0.15f * std::exp(-t / 0.05f));
        v[i] = std::sin(2 * M_PI * f * t) * std::exp(-t / 0.25f) +
               0.15f * r.gauss() * std::exp(-t / 0.01f);
    }
    return v;
}

Voice hihat(Rng &r, bool open) {
    Voice v(kSr * (open ? 600 : 120) / 1000);
    OnePole hp(7000);
    float decay = open ? 0.25f : 0.025f;
    for (size_t i = 0; i < v.size(); ++i) {
        float t = static_cast<float>(i) / kSr;
        float metal = 0;
        for (float f : {3150.f, 4850.f, 6480.f, 8120.f}) metal += std::sin(2 * M_PI * f * t);
        v[i] = (hp.hp(r.gauss()) + 0.08f * metal) * std::exp(-t / decay);
    }
    return v;
}

Voice cymbal(Rng &r, bool ride) {
    Voice v(kSr * 800 / 1000);
    OnePole hp(ride ? 3000 : 2000);
    for (size_t i = 0; i < v.size(); ++i) {
        float t = static_cast<float>(i) / kSr;
        float bell = ride ? 0.5f * std::sin(2 * M_PI * 620 * t) * std::exp(-t / 0.4f) : 0.0f;
        float wash = hp.hp(r.gauss()) * (ride ? 0.4f : 1.0f) * std::exp(-t / (ride ? 0.5f : 0.9f));
        v[i] = wash + bell;
    }
    return v;
}

Voice raw(const std::string &pad, Rng &r) {
    float tune = r.uni(0.97f, 1.03f);
    if (pad == "kick") return kick(r, tune);
    if (pad == "snare") return snare(r, tune);
    if (pad == "tom_high") return tom(r, 240, tune);
    if (pad == "tom_mid") return tom(r, 170, tune);
    if (pad == "tom_floor") return tom(r, 105, tune);
    if (pad == "hihat") return hihat(r, false);
    if (pad == "crash") return cymbal(r, false);
    if (pad == "ride") return cymbal(r, true);
    return {};
}

// Fade the tail out like a module's release; an abrupt cut would itself be a "hit".
Voice make(const std::string &pad, Rng &r) {
    Voice v = raw(pad, r);
    size_t fade = std::min(v.size(), static_cast<size_t>(kSr * 60 / 1000));
    for (size_t i = 0; i < fade; ++i) v[v.size() - fade + i] *= 0.5f + 0.5f * std::cos(M_PI * i / fade);
    return v;
}

void addVoice(std::vector<float> &buf, int64_t at, const Voice &v, float gain) {
    for (size_t i = 0; i < v.size() && at + static_cast<int64_t>(i) < static_cast<int64_t>(buf.size()); ++i) {
        float s = gain * v[i];
        buf[at + i] += s;
        if (at + i + 330 < buf.size()) buf[at + i + 330] += 0.25f * s;  // room reflection
    }
}

}  // namespace

int main(int argc, char **argv) {
    const char *outPath = argc > 1 ? argv[1] : "synthetic_hits.json";
    const std::vector<std::string> pads = {"kick", "snare", "hihat", "tom_high", "tom_mid", "tom_floor", "crash", "ride"};
    const int perPad = 30;
    const int combos = 30;  // kick + hihat struck together

    Rng r;
    struct Truth { int64_t at; std::string label; };
    std::vector<Truth> truth;
    std::vector<float> buf;
    int64_t at = kSr / 2;
    auto place = [&](const std::vector<std::pair<std::string, float>> &parts, const std::string &label) {
        size_t need = static_cast<size_t>(at + kSr);
        if (buf.size() < need) buf.resize(need, 0.0f);
        for (const auto &p : parts) addVoice(buf, at, make(p.first, r), p.second);
        truth.push_back({at, label});
        at += kSr * 9 / 10;  // let each hit ring out
    };
    for (int i = 0; i < perPad; ++i) {
        for (const auto &p : pads) place({{p, r.uni(0.15f, 0.7f)}}, p);
    }
    for (int i = 0; i < combos; ++i) {
        place({{"kick", r.uni(0.3f, 0.7f)}, {"hihat", r.uni(0.2f, 0.5f)}}, "kick+hihat");
    }
    buf.resize(buf.size() + kSr, 0.0f);
    for (auto &s : buf) s += 0.001f * r.gauss();

    OnsetDetector det(kSr);
    det.setSensitivity(0.6f);
    std::vector<OnsetEvent> found;
    for (size_t i = 0; i < buf.size(); i += 256) {
        int n = static_cast<int>(std::min<size_t>(256, buf.size() - i));
        det.process(&buf[i], n, static_cast<int64_t>(i), [&](const OnsetEvent &e) { found.push_back(e); });
    }

    FILE *f = std::fopen(outPath, "w");
    if (!f) return 1;
    std::fprintf(f, "[\n");
    int written = 0, unmatched = 0, extra = 0;
    std::vector<bool> used(found.size(), false);
    for (const auto &t : truth) {
        int best = -1;
        for (size_t j = 0; j < found.size(); ++j) {
            if (!used[j] && std::llabs(found[j].frame - t.at) < kSr / 100) { best = static_cast<int>(j); break; }
        }
        if (best < 0) { ++unmatched; continue; }
        used[best] = true;
        const auto &e = found[best];
        std::fprintf(f, "%s  {\"label\": \"%s\", \"strength\": %.4f, \"decayDb\": %.3f, \"bandsDb\": [",
                     written++ ? ",\n" : "", t.label.c_str(), e.strength, e.decayDb);
        for (int b = 0; b < kNumBands; ++b) std::fprintf(f, "%s%.3f", b ? ", " : "", e.bandsDb[b]);
        std::fprintf(f, "]}");
    }
    std::fprintf(f, "\n]\n");
    std::fclose(f);
    for (bool u : used) if (!u) ++extra;
    std::printf("features: %d hits written, %d not detected, %d extra detections\n", written, unmatched, extra);
    return unmatched == 0 && extra == 0 ? 0 : 1;
}
