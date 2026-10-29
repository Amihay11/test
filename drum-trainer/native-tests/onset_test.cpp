// Host-side test for the onset detector: synthesizes drum-like hits played
// through a "speaker" into a "room", mixes in leakage of the metronome click
// tones and background noise, then checks recall, false positives and timing.
//
// Build & run:  ./run.sh
#include <cmath>
#include <cstdio>
#include <random>
#include <vector>

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
    std::printf(ok ? "ALL PASSED\n" : "SOME FAILED\n");
    return ok ? 0 : 1;
}
