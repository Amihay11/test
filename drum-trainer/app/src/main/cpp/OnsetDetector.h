#pragma once

#include <complex>
#include <cstdint>
#include <vector>

#include "Biquad.h"
#include "Fft.h"

struct OnsetEvent {
    int64_t frame;   // input-stream frame index of the attack
    float strength;  // peak absolute amplitude (linear, 0..1)
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
    static constexpr int kHistSize = 4096;
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
                onOnset(ev);
            }
        }
    }

    float computeFlux();
    float threshold();
    bool refine(int64_t windowEnd, OnsetEvent &out);

    int sampleRate_;
    Fft fft_;
    std::vector<float> window_;
    std::vector<std::complex<float>> spec_;
    std::vector<float> logMag_;
    std::vector<std::vector<float>> pastMax_;  // max-filtered spectra of the last kLag hops
    int pastPos_ = 0;
    std::vector<uint8_t> binEnabled_;
    std::vector<float> hist_;
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
