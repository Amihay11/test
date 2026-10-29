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
      hist_(kHistSize, 0.0f),
      attackHist_(kHistSize, 0.0f) {
    float windowSum = 0.0f;
    for (int i = 0; i < kFftSize; ++i) {
        window_[i] = 0.5f - 0.5f * std::cos(2.0f * static_cast<float>(M_PI) * i / kFftSize);
        windowSum += window_[i];
    }
    for (auto &w : window_) w /= windowSum;

    highPass_.setHighPass(sampleRate, 40.0, 0.707);
    attackHighPass_.setHighPass(sampleRate, 300.0, 0.707);

    const double clickFreqs[] = {kClickFreqNormal, kClickFreqAccent,
                                 2 * kClickFreqNormal, 2 * kClickFreqAccent};
    // Two notches in series per frequency: one alone lets the attack of a tone through.
    for (double f : clickFreqs) {
        Biquad b;
        b.setNotch(sampleRate, f, 6.0);
        notches_.push_back(b);
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

    // Feature analysis: Hann window over kFeatureLen samples and mel filters 50 Hz..16 kHz.
    featWindow_.resize(kFeatureLen);
    featSpec_.resize(kFeatureLen);
    for (int i = 0; i < kFeatureLen; ++i) {
        featWindow_[i] = 0.5f - 0.5f * std::cos(2.0f * static_cast<float>(M_PI) * i / kFeatureLen);
    }
    auto mel = [](double f) { return 2595.0 * std::log10(1.0 + f / 700.0); };
    auto invMel = [](double m) { return 700.0 * (std::pow(10.0, m / 2595.0) - 1.0); };
    double featBinHz = static_cast<double>(sampleRate) / kFeatureLen;
    double fMax = std::min(16000.0, sampleRate / 2.0 - featBinHz);
    double mLo = mel(50.0), mHi = mel(fMax);
    for (int b = 0; b < kNumBands; ++b) {
        double lo = invMel(mLo + (mHi - mLo) * b / (kNumBands + 1));
        double mid = invMel(mLo + (mHi - mLo) * (b + 1) / (kNumBands + 1));
        double hi = invMel(mLo + (mHi - mLo) * (b + 2) / (kNumBands + 1));
        int first = static_cast<int>(std::floor(lo / featBinHz));
        int last = static_cast<int>(std::ceil(hi / featBinHz));
        std::vector<float> w;
        for (int k = first; k <= last; ++k) {
            double f = k * featBinHz;
            double v = f <= mid ? (f - lo) / (mid - lo) : (hi - f) / (hi - mid);
            w.push_back(static_cast<float>(std::max(0.0, v)));
        }
        // Low bands can be narrower than one bin: always keep the nearest bin.
        bool any = false;
        for (float v : w) any = any || v > 0;
        if (!any) {
            first = static_cast<int>(std::lround(mid / featBinHz));
            w.assign(1, 1.0f);
        }
        melFilters_.emplace_back(first, std::move(w));
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

    auto absAt = [&](int64_t i) { return std::fabs(attackHist_[i & kHistMask]); };
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

    // Strength is the full-band peak (what the ear hears as loudness).
    float strength = 0.0f;
    for (int64_t i = best; i < nextFrame_; ++i) strength = std::max(strength, std::fabs(hist_[i & kHistMask]));

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
    out.strength = strength;
    return true;
}

void OnsetDetector::computeFeatures(OnsetEvent &ev) {
    const int half = kFeatureLen / 2;
    double e1 = 1e-12, e2 = 1e-12;
    for (int i = 0; i < kFeatureLen; ++i) {
        float x = hist_[(ev.frame + i) & kHistMask];
        featSpec_[i] = {x * featWindow_[i], 0.0f};
        (i < half ? e1 : e2) += static_cast<double>(x) * x;
    }
    featFft_.forward(featSpec_);
    for (int b = 0; b < kNumBands; ++b) {
        const auto &f = melFilters_[b];
        double p = 1e-14;
        for (size_t j = 0; j < f.second.size(); ++j) {
            int k = f.first + static_cast<int>(j);
            if (k < 0 || k > kFeatureLen / 2) continue;
            p += f.second[j] * std::norm(featSpec_[k]);
        }
        ev.bandsDb[b] = static_cast<float>(10.0 * std::log10(p));
    }
    ev.decayDb = static_cast<float>(10.0 * std::log10(e2 / e1));
}
