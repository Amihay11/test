// Host-side test for the onset detector: synthesizes drum-like hits played
// through a "speaker" into a "room", mixes in leakage of the metronome click
// tones and background noise, then checks recall, false positives and timing.
//
// Build & run:  ./run.sh
#include <cmath>
#include <cstdio>
#include <random>
#include <vector>

#include "../app/src/main/cpp/BackingSynth.h"
#include "../app/src/main/cpp/GuideTones.h"
#include "../app/src/main/cpp/OnsetDetector.h"

namespace {

constexpr int kSr = 48000;

struct Hit {
    int64_t frame;
    float amp;
};

void addDrum(std::vector<float> &buf, int64_t at, float amp, std::mt19937 &rng, bool kick) {
    std::normal_distribution<float> noise(0.0f, 1.0f);
    int len = kSr * 300 / 1000;
    for (int i = 0; i < len && at + i < static_cast<int64_t>(buf.size()); ++i) {
        float t = static_cast<float>(i) / kSr;
        float attack = std::min(1.0f, t / 0.0005f);
        float body = kick ? std::sin(2 * M_PI * 60 * t) * std::exp(-t / 0.12f)
                          : 0.6f * std::sin(2 * M_PI * 190 * t) * std::exp(-t / 0.08f);
        float snares = (kick ? 0.15f : 0.5f) * noise(rng) * std::exp(-t / 0.04f);
        float s = amp * attack * (body + snares);
        // Room: direct sound plus two early reflections.
        buf[at + i] += s;
        if (at + i + 240 < static_cast<int64_t>(buf.size())) buf[at + i + 240] += 0.3f * s;
        if (at + i + 530 < static_cast<int64_t>(buf.size())) buf[at + i + 530] += 0.2f * s;
    }
}

void addClick(std::vector<float> &buf, int64_t at, bool accent, float amp) {
    double f = accent ? kClickFreqAccent : kClickFreqNormal;
    int len = kSr * 40 / 1000;
    int attackLen = kSr * 3 / 1000;
    for (int i = 0; i < len && at + i < static_cast<int64_t>(buf.size()); ++i) {
        float t = static_cast<float>(i) / kSr;
        float env = i < attackLen ? 0.5f - 0.5f * std::cos(M_PI * i / attackLen) : 1.0f;
        env *= std::exp(-t / 0.008f);
        float s = amp * env * std::sin(2 * M_PI * f * t);
        // Speaker distortion adds a little second harmonic.
        s += 0.1f * amp * env * std::sin(2 * M_PI * 2 * f * t);
        buf[at + i] += s;
    }
}

struct Result {
    int truePos = 0, falsePos = 0, missed = 0;
    double meanErrMs = 0, maxAbsErrMs = 0, stdErrMs = 0;
};

Result evaluate(const std::vector<Hit> &truth, const std::vector<OnsetEvent> &found) {
    Result r;
    std::vector<bool> used(found.size(), false);
    std::vector<double> errs;
    int64_t tol = kSr * 20 / 1000;
    for (const auto &h : truth) {
        int best = -1;
        int64_t bestD = tol + 1;
        for (size_t j = 0; j < found.size(); ++j) {
            if (used[j]) continue;
            int64_t d = std::llabs(found[j].frame - h.frame);
            if (d < bestD) {
                bestD = d;
                best = static_cast<int>(j);
            }
        }
        if (best >= 0) {
            used[best] = true;
            ++r.truePos;
            errs.push_back((found[best].frame - h.frame) * 1000.0 / kSr);
        } else {
            ++r.missed;
        }
    }
    for (bool u : used) if (!u) ++r.falsePos;
    double sum = 0, sq = 0;
    for (double e : errs) {
        sum += e;
        r.maxAbsErrMs = std::max(r.maxAbsErrMs, std::fabs(e));
    }
    if (!errs.empty()) {
        r.meanErrMs = sum / errs.size();
        for (double e : errs) sq += (e - r.meanErrMs) * (e - r.meanErrMs);
        r.stdErrMs = std::sqrt(sq / errs.size());
    }
    return r;
}

bool runScenario(const char *name, double bpm, int stepsPerBeat, float clickAmp, bool ghosts,
                 float noiseDb, bool notch, float minRecall, int maxFalse) {
    std::mt19937 rng(1234);
    std::normal_distribution<float> jitter(0.0f, 8.0f);  // human timing jitter, ms
    std::normal_distribution<float> noise(0.0f, std::pow(10.0f, noiseDb / 20.0f));

    int seconds = 20;
    std::vector<float> buf(kSr * seconds, 0.0f);
    std::vector<Hit> truth;

    double beatFrames = kSr * 60.0 / bpm;
    double stepFrames = beatFrames / stepsPerBeat;
    int64_t start = kSr / 2;
    int beatIdx = 0;
    for (double t = start; t < buf.size() - kSr / 2; t += beatFrames, ++beatIdx) {
        addClick(buf, static_cast<int64_t>(t), beatIdx % 4 == 0, clickAmp);
    }
    int step = 0;
    for (double t = start; t < buf.size() - kSr / 2; t += stepFrames, ++step) {
        int64_t at = static_cast<int64_t>(t + jitter(rng) * kSr / 1000.0);
        float amp;
        if (ghosts) {
            amp = (step % stepsPerBeat == 0) ? 0.9f : 0.12f;
        } else {
            amp = 0.5f;
        }
        addDrum(buf, at, amp, rng, step % 8 == 0 && !ghosts);
        truth.push_back({at, amp});
    }
    for (auto &s : buf) s += noise(rng);

    OnsetDetector det(kSr);
    det.setNotchEnabled(notch);
    det.setSensitivity(0.6f);
    std::vector<OnsetEvent> found;
    int64_t frame = 1000;  // arbitrary non-zero stream offset
    for (size_t i = 0; i < buf.size(); i += 192) {
        int n = static_cast<int>(std::min<size_t>(192, buf.size() - i));
        det.process(&buf[i], n, frame + static_cast<int64_t>(i),
                    [&](const OnsetEvent &e) {
                        OnsetEvent shifted = e;
                        shifted.frame -= frame;
                        found.push_back(shifted);
                    });
    }

    Result r = evaluate(truth, found);
    double recall = static_cast<double>(r.truePos) / truth.size();
    bool ok = recall >= minRecall && r.falsePos <= maxFalse && std::fabs(r.meanErrMs) < 1.0 &&
              r.stdErrMs < 1.0;
    std::printf("%-34s hits=%3zu recall=%.3f fp=%2d missed=%2d  err mean=%+.2fms std=%.2fms max=%.2fms  %s\n",
                name, truth.size(), recall, r.falsePos, r.missed, r.meanErrMs, r.stdErrMs,
                r.maxAbsErrMs, ok ? "OK" : "FAIL");
    return ok;
}

bool runClickOnly() {
    // Only metronome clicks, loud, no drums: nothing should be detected.
    std::vector<float> buf(kSr * 10, 0.0f);
    for (int64_t t = kSr / 2, i = 0; t < static_cast<int64_t>(buf.size()) - kSr / 2; t += kSr / 2, ++i) {
        addClick(buf, t, i % 4 == 0, 0.6f);
    }
    std::mt19937 rng(7);
    std::normal_distribution<float> noise(0.0f, 0.002f);
    for (auto &s : buf) s += noise(rng);
    OnsetDetector det(kSr);
    det.setSensitivity(0.6f);
    int count = 0;
    det.process(buf.data(), static_cast<int>(buf.size()), 0, [&](const OnsetEvent &) { ++count; });
    std::printf("%-34s detections=%d  %s\n", "clicks only (notch on)", count, count == 0 ? "OK" : "FAIL");
    return count == 0;
}

// Playing along with the app through the phone speaker: the app sounds the groove
// with guide tones exactly on the grid (often louder than the drums at the mic) while
// the player hits the drums with human jitter. Only the player's hits may be detected.
bool runPlayAlong(float guideAmp, float drumAmp) {
    std::mt19937 rng(99);
    std::normal_distribution<float> jitter(0.0f, 8.0f);
    std::normal_distribution<float> noise(0.0f, 0.0018f);
    std::vector<float> buf(kSr * 16, 0.0f);
    std::vector<std::vector<float>> tones;
    for (double f : guide::kFreqs) tones.push_back(guide::tone(kSr, f));

    std::vector<Hit> truth;
    double step = kSr * 60.0 / 100 / 2;  // 8ths at 100 BPM
    int i = 0;
    for (double t = kSr / 2; t < buf.size() - kSr / 2; t += step, ++i) {
        // Rock beat guide: hi-hat every 8th, kick on 1 and 3, snare on 2 and 4.
        std::vector<int> parts = {2};
        if (i % 4 == 0) parts.push_back(0);
        if (i % 4 == 2) parts.push_back(1);
        for (int p : parts) {
            const auto &tone = tones[p];
            for (size_t k = 0; k < tone.size() && t + k < buf.size(); ++k) {
                buf[static_cast<size_t>(t) + k] += guideAmp / 0.5f * tone[k];
            }
        }
        int64_t at = static_cast<int64_t>(t + jitter(rng) * kSr / 1000.0);
        addDrum(buf, at, drumAmp, rng, i % 4 == 0);
        truth.push_back({at, drumAmp});
    }
    for (auto &s : buf) s += noise(rng);

    OnsetDetector det(kSr);
    det.setSensitivity(0.6f);
    std::vector<OnsetEvent> found;
    det.process(buf.data(), static_cast<int>(buf.size()), 0, [&](const OnsetEvent &e) { found.push_back(e); });
    Result r = evaluate(truth, found);
    double recall = static_cast<double>(r.truePos) / truth.size();
    bool ok = recall >= 0.98 && r.falsePos <= 1 && std::fabs(r.meanErrMs) < 1.0 && r.stdErrMs < 1.0;
    std::printf("play-along, guide %.1f / drums %.1f    hits=%3zu recall=%.3f fp=%2d  err mean=%+.2fms std=%.2fms  %s\n",
                guideAmp, drumAmp, truth.size(), recall, r.falsePos, r.meanErrMs, r.stdErrMs, ok ? "OK" : "FAIL");
    return ok;
}

bool runGuideOnly() {
    std::vector<float> buf(kSr * 8, 0.0f);
    std::vector<std::vector<float>> tones;
    for (double f : guide::kFreqs) tones.push_back(guide::tone(kSr, f));
    int i = 0;
    for (size_t t = kSr / 2; t < buf.size() - kSr / 2; t += kSr / 8, ++i) {
        // All four tones, alone and stacked, at full level.
        for (int p = 0; p < guide::kNumTones; ++p) {
            if (i % 5 != p && i % 5 != 4) continue;
            for (size_t k = 0; k < tones[p].size(); ++k) buf[t + k] += 1.6f * tones[p][k];
        }
    }
    std::mt19937 rng(5);
    std::normal_distribution<float> noise(0.0f, 0.0018f);
    for (auto &s : buf) s += noise(rng);
    OnsetDetector det(kSr);
    det.setSensitivity(0.6f);
    int count = 0;
    det.process(buf.data(), static_cast<int>(buf.size()), 0, [&](const OnsetEvent &) { ++count; });
    std::printf("%-34s detections=%d  %s\n", "guide tones only (0.8 peak)", count, count == 0 ? "OK" : "FAIL");
    return count == 0;
}

// A bass + chords backing loop like the one free play uses: E-B-C#m-A, bass in 8ths.
std::vector<float> backingLoop(double bpm, int bars, float level) {
    const int beat = static_cast<int>(kSr * 60.0 / bpm);
    const int bar = 4 * beat;
    const int roots[4] = {40, 47, 49, 45};  // E2 B2 C#3 A2
    const int third[4] = {4, 4, 3, 4};
    std::vector<backing::Note> notes;
    for (int b = 0; b < bars; ++b) {
        int r = roots[b % 4];
        for (int e = 0; e < 8; ++e) {
            int octave = (e % 4 == 3) ? 12 : 0;
            notes.push_back({b * bar + e * beat / 2, beat / 2 - kSr / 100, r + octave, 0.35f * level, backing::kBass});
        }
        for (int iv : {12, 12 + third[b % 4], 19}) notes.push_back({b * bar, bar - kSr / 50, r + iv, 0.08f * level, backing::kPad});
    }
    return backing::render(notes, bars * bar, kSr);
}

bool runBackingOnly(float level) {
    std::vector<float> buf = backingLoop(100, 8, level);
    std::mt19937 rng(11);
    std::normal_distribution<float> noise(0.0f, 0.0018f);
    float peak = 0;
    for (auto &s : buf) { s += noise(rng); peak = std::max(peak, std::fabs(s)); }
    OnsetDetector det(kSr);
    det.setSensitivity(0.6f);
    int count = 0;
    det.process(buf.data(), static_cast<int>(buf.size()), 0, [&](const OnsetEvent &) { ++count; });
    std::printf("backing only (peak %.2f)              detections=%d  %s\n", peak, count, count == 0 ? "OK" : "FAIL");
    return count == 0;
}

bool runDrumsOverBacking(float level) {
    std::vector<float> buf = backingLoop(100, 8, level);
    std::mt19937 rng(12);
    std::normal_distribution<float> jitter(0.0f, 8.0f);
    std::vector<Hit> truth;
    double step = kSr * 60.0 / 100 / 2;
    int i = 0;
    for (double t = kSr / 2; t < buf.size() - kSr / 2; t += step, ++i) {
        int64_t at = static_cast<int64_t>(t + jitter(rng) * kSr / 1000.0);
        addDrum(buf, at, 0.4f, rng, i % 4 == 0);
        truth.push_back({at, 0.4f});
    }
    OnsetDetector det(kSr);
    det.setSensitivity(0.6f);
    std::vector<OnsetEvent> found;
    det.process(buf.data(), static_cast<int>(buf.size()), 0, [&](const OnsetEvent &e) { found.push_back(e); });
    Result r = evaluate(truth, found);
    double recall = static_cast<double>(r.truePos) / truth.size();
    bool ok = recall >= 0.98 && r.falsePos <= 1 && std::fabs(r.meanErrMs) < 1.0 && r.stdErrMs < 1.0;
    std::printf("drums over backing                  hits=%3zu recall=%.3f fp=%2d  err mean=%+.2fms std=%.2fms  %s\n",
                truth.size(), recall, r.falsePos, r.meanErrMs, r.stdErrMs, ok ? "OK" : "FAIL");
    return ok;
}

}  // namespace

int main() {
    bool ok = true;
    ok &= runScenario("quarters 80bpm, quiet click", 80, 1, 0.05f, false, -60, true, 0.99f, 0);
    ok &= runScenario("16ths 100bpm, loud click", 100, 4, 0.4f, false, -55, true, 0.98f, 2);
    ok &= runScenario("16ths 120bpm, accents+ghosts", 120, 4, 0.3f, true, -55, true, 0.95f, 3);
    ok &= runScenario("sextuplets 90bpm", 90, 6, 0.3f, false, -55, true, 0.97f, 3);
    ok &= runScenario("16ths 100bpm, noisy room", 100, 4, 0.3f, false, -40, true, 0.97f, 3);
    ok &= runScenario("8ths 100bpm, notch off, no click", 100, 2, 0.0f, false, -55, false, 0.99f, 0);
    ok &= runClickOnly();
    ok &= runGuideOnly();
    ok &= runPlayAlong(0.8f, 0.5f);
    ok &= runPlayAlong(0.4f, 0.15f);
    ok &= runBackingOnly(1.0f);
    ok &= runBackingOnly(2.0f);
    ok &= runDrumsOverBacking(1.0f);
    std::printf(ok ? "ALL PASSED\n" : "SOME FAILED\n");
    return ok ? 0 : 1;
}
