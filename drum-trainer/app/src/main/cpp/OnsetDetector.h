#pragma once

#include <complex>
#include <cstdint>
#include <vector>

#include "Biquad.h"
#include "Fft.h"

// Spectral shape of a hit, used to tell the drums apart.
constexpr int kNumBands = 20;

struct OnsetEvent {
    int64_t frame;   // input-stream frame index of the attack
    float strength;  // peak absolute amplitude (linear, 0..1)
    // Log power (dB) in kNumBands mel-spaced bands over the first ~43 ms of the hit.
    float bandsDb[kNumBands];
    // Energy of the second half of that window relative to the first half (dB):
    // cymbals ring on, toms and the kick die away.
    float decayDb;
};

// Click tone frequencies used by the metronome. The detector notches these
// (and their second harmonics) out so the phone's own click is not heard as a hit.
constexpr double kClickFreqNormal = 1600.0;
constexpr double kClickFreqAccent = 2400.0;

// Spectral-flux onset detector with an adaptive (median) threshold and
// sample-accurate refinement of the attack time in the time domain.
class OnsetDetector {
public:
    static constexpr int kFftSize = 512;
    static constexpr int kHop = 128;

    explicit OnsetDetector(int sampleRate);

    // 0 = least sensitive, 1 = most sensitive.
    void setSensitivity(float s);
    void setNotchEnabled(bool enabled) { notchEnabled_ = enabled; }
    void setMinInterOnsetMs(float ms);

    // Feeds consecutive samples. firstFrame is the absolute frame index of x[0];
    // it is only used the first time (later calls are assumed to be contiguous).
    template <typename Callback>
    void process(const float *x, int n, int64_t firstFrame, Callback &&onOnset) {
        if (!started_) {
            nextFrame_ = firstFrame;
            started_ = true;
        }
        for (int i = 0; i < n; ++i) {
            float y = highPass_.process(x[i]);
            if (notchEnabled_) {
                for (auto &f : notches_) y = f.process(y);
            }
            hist_[nextFrame_ & kHistMask] = y;
            attackHist_[nextFrame_ & kHistMask] = attackHighPass_.process(y);
            ++nextFrame_;
            if (++hopFill_ == kHop) {
                hopFill_ = 0;
                analyzeHop(onOnset);
            }
        }
    }

    int64_t nextFrame() const { return nextFrame_; }
    float lastFlux() const { return fluxCur_; }

private:
    static constexpr int kHistSize = 8192;
    static constexpr int kFeatureLen = 2048;  // samples after the attack used for features
    static constexpr int kHistMask = kHistSize - 1;
    static constexpr int kMedianLen = 20;  // ~53 ms of flux history at 48 kHz
    static constexpr int kLag = 2;  // SuperFlux: compare against the spectrum kLag hops back
    static constexpr int kWarmupHops = kFftSize / kHop + kLag;

    template <typename Callback>
    void analyzeHop(Callback &onOnset) {
        float flux = computeFlux();
        fluxPrev2_ = fluxPrev1_;
        fluxPrev1_ = fluxCur_;
        fluxCur_ = flux;
        int64_t endPrev1 = nextFrame_ - kHop;  // window end when fluxPrev1_ was computed

        emitReady(onOnset);

        float thr = threshold();
        fluxHistory_[fluxHistPos_] = flux;
        fluxHistPos_ = (fluxHistPos_ + 1) % kMedianLen;
        if (fluxHistCount_ < kMedianLen) ++fluxHistCount_;

        bool isPeak = fluxPrev1_ > fluxPrev2_ && fluxPrev1_ >= fluxCur_ && fluxPrev1_ > thr;
        if (hopsSeen_++ < kWarmupHops || !isPeak) return;

        OnsetEvent ev{};
        if (refine(endPrev1, ev)) {
            if (ev.frame - lastOnsetFrame_ >= minGapFrames_ && ev.strength >= gate_) {
                lastOnsetFrame_ = ev.frame;
                pending_.push_back(ev);  // emitted once the whole feature window is in
            }
        }
    }

    template <typename Callback>
    void emitReady(Callback &onOnset) {
        size_t kept = 0;
        for (size_t i = 0; i < pending_.size(); ++i) {
            OnsetEvent &ev = pending_[i];
            if (nextFrame_ >= ev.frame + kFeatureLen) {
                computeFeatures(ev);
                onOnset(static_cast<const OnsetEvent &>(ev));
            } else {
                pending_[kept++] = ev;
            }
        }
        pending_.resize(kept);
    }

    float computeFlux();
    float threshold();
    bool refine(int64_t windowEnd, OnsetEvent &out);
    void computeFeatures(OnsetEvent &ev);

    Fft featFft_{kFeatureLen};
    std::vector<float> featWindow_;
    std::vector<std::complex<float>> featSpec_;
    // Triangular mel filters: for each band, (first bin, weights).
    std::vector<std::pair<int, std::vector<float>>> melFilters_;
    std::vector<OnsetEvent> pending_;

    int sampleRate_;
    Fft fft_;
    std::vector<float> window_;
    std::vector<std::complex<float>> spec_;
    std::vector<float> logMag_;
    std::vector<std::vector<float>> pastMax_;  // max-filtered spectra of the last kLag hops
    int pastPos_ = 0;
    std::vector<uint8_t> binEnabled_;
    std::vector<float> hist_;
    // High-passed copy used to pin down attack times: a drum's attack is broadband,
    // while sustained bass (e.g. the free-play backing) would mask it at full band.
    std::vector<float> attackHist_;
    Biquad attackHighPass_;
    float fluxHistory_[kMedianLen] = {};
    int fluxHistPos_ = 0;
    int fluxHistCount_ = 0;

    Biquad highPass_;
    std::vector<Biquad> notches_;
    bool notchEnabled_ = true;

    bool started_ = false;
    int64_t nextFrame_ = 0;
    int hopFill_ = 0;
    int64_t hopsSeen_ = 0;
    float fluxPrev2_ = 0, fluxPrev1_ = 0, fluxCur_ = 0;
    int64_t lastOnsetFrame_ = INT64_MIN / 2;
    int64_t minGapFrames_;
    float thrMultiplier_ = 1.6f;
    float thrDelta_ = 0.02f;
    float gate_ = 0.01f;
};
