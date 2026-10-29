#include "OnsetDetector.h"

#include <algorithm>
#include <cmath>

OnsetDetector::OnsetDetector(int sampleRate)
    : sampleRate_(sampleRate),
      fft_(kFftSize),
      window_(kFftSize),
      spec_(kFftSize),
      logMag_(kFftSize / 2 + 1, 0.0f),
      pastMax_(kLag, std::vector<float>(kFftSize / 2 + 1, 0.0f)),
      binEnabled_(kFftSize / 2 + 1, 1),
      hist_(kHistSize, 0.0f) {
    float windowSum = 0.0f;
    for (int i = 0; i < kFftSize; ++i) {
        window_[i] = 0.5f - 0.5f * std::cos(2.0f * static_cast<float>(M_PI) * i / kFftSize);
        windowSum += window_[i];
    }
    for (auto &w : window_) w /= windowSum;

    highPass_.setHighPass(sampleRate, 40.0, 0.707);

    const double clickFreqs[] = {kClickFreqNormal, kClickFreqAccent,
                                 2 * kClickFreqNormal, 2 * kClickFreqAccent};
    for (double f : clickFreqs) {
        Biquad b;
        b.setNotch(sampleRate, f, 6.0);
        notches_.push_back(b);
    }

    // Bins that carry click energy are ignored by the flux (only when notching).
    double binHz = static_cast<double>(sampleRate) / kFftSize;
    for (int k = 0; k <= kFftSize / 2; ++k) {
        double fk = k * binHz;
        for (double f : clickFreqs) {
            if (std::fabs(fk - f) <= 3 * binHz) binEnabled_[k] = 0;
        }
    }

    setMinInterOnsetMs(45.0f);
    setSensitivity(0.5f);
}

void OnsetDetector::setSensitivity(float s) {
    s = std::clamp(s, 0.0f, 1.0f);
    // Higher sensitivity -> lower flux threshold and lower amplitude gate.
    thrMultiplier_ = 3.0f - 1.5f * s;                         // 3.0 .. 1.5
    thrDelta_ = 0.3f * std::pow(10.0f, -1.0f * s);            // 0.3 .. 0.03
    gate_ = 0.03f * std::pow(10.0f, -1.5f * s);               // ~-30 dBFS .. ~-60 dBFS
}

void OnsetDetector::setMinInterOnsetMs(float ms) {
    minGapFrames_ = static_cast<int64_t>(ms * sampleRate_ / 1000.0f);
}

float OnsetDetector::computeFlux() {
    int64_t start = nextFrame_ - kFftSize;
    for (int i = 0; i < kFftSize; ++i) {
        spec_[i] = {hist_[(start + i) & kHistMask] * window_[i], 0.0f};
    }
    fft_.forward(spec_);

    double binHz = static_cast<double>(sampleRate_) / kFftSize;
    int lowBin = std::max(1, static_cast<int>(60.0 / binHz));
    int highBin = std::min(kFftSize / 2, static_cast<int>(14000.0 / binHz));

    const int nBins = kFftSize / 2 + 1;
    for (int k = 0; k < nBins; ++k) logMag_[k] = std::log1p(1000.0f * std::abs(spec_[k]));

    // Positive difference against the max-filtered spectrum kLag hops ago. The lag and the
    // max filter keep the noisy decay of a drum from producing new "onsets".
    std::vector<float> &ref = pastMax_[pastPos_];
    float flux = 0.0f;
    int used = 0;
    for (int k = lowBin; k <= highBin; ++k) {
        if (notchEnabled_ && !binEnabled_[k]) continue;
        float d = logMag_[k] - ref[k];
        if (d > 0) flux += d;
        ++used;
    }
    for (int k = 0; k < nBins; ++k) {
        float m = logMag_[k];
        if (k > 0) m = std::max(m, logMag_[k - 1]);
        if (k + 1 < nBins) m = std::max(m, logMag_[k + 1]);
        ref[k] = m;
    }
    pastPos_ = (pastPos_ + 1) % kLag;
    return used > 0 ? flux / used : 0.0f;
}

float OnsetDetector::threshold() {
    if (fluxHistCount_ == 0) return 1e9f;
    float tmp[kMedianLen];
    std::copy(fluxHistory_, fluxHistory_ + fluxHistCount_, tmp);
    int mid = fluxHistCount_ / 2;
    std::nth_element(tmp, tmp + mid, tmp + fluxHistCount_);
    return tmp[mid] * thrMultiplier_ + thrDelta_;
}

bool OnsetDetector::refine(int64_t windowEnd, OnsetEvent &out) {
    const int shortLen = sampleRate_ / 2000;      // 0.5 ms: "what is happening now"
    const int beforeLen = sampleRate_ * 8 / 1000;  // 8 ms: "what was ringing before"
    int64_t oldest = nextFrame_ - kHistSize + beforeLen + 1;
    int64_t from = std::max({windowEnd - kFftSize - kHop, lastOnsetFrame_ + minGapFrames_ / 2, oldest});
    int64_t to = nextFrame_ - shortLen;  // exclusive
    if (from >= to) return false;

    auto absAt = [&](int64_t i) { return std::fabs(hist_[i & kHistMask]); };
    auto maxAbs = [&](int64_t a, int64_t b) {
        float m = 0.0f;
        for (int64_t i = a; i < b; ++i) m = std::max(m, absAt(i));
        return m;
    };

    // The attack is where the short-term level jumps the most relative to what was
    // already ringing (the previous note's tail, reflections, noise).
    const float eps = 1e-5f;
    int64_t best = -1;
    float bestRatio = 0.0f;
    float bestBefore = 0.0f;
    float peak = 0.0f;
    for (int64_t i = from; i < to; ++i) {
        float now = maxAbs(i, i + shortLen);
        float before = maxAbs(i - beforeLen, i);
        peak = std::max(peak, now);
        float ratio = (now + eps) / (before + eps);
        if (ratio > bestRatio) {
            bestRatio = ratio;
            best = i;
            bestBefore = before;
        }
    }
    if (best < 0 || peak <= 0.0f) return false;
    peak = std::max(peak, maxAbs(to, nextFrame_));

    // Sample-accurate start: first sample in the short window that clearly exceeds
    // the level that was there before.
    float level = std::max(1.5f * bestBefore, 0.05f * peak);
    int64_t attack = best;
    for (int64_t i = best; i < best + shortLen; ++i) {
        if (absAt(i) > level) {
            attack = i;
            break;
        }
    }

    out.frame = attack;
    out.strength = peak;
    return true;
}
