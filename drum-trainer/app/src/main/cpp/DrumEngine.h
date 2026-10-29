#pragma once

#include <oboe/Oboe.h>

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>

#include "OnsetDetector.h"
#include "SpscRing.h"

enum ClickType : int {
    kClickNormal = 0,
    kClickAccent = 1,
    kClickBroadband = 2,  // used for loopback latency calibration (not notched)
};

struct ScheduledClick {
    int64_t frame;  // output-stream frame index
    int type;
};

struct AlignedOnset {
    double frame;    // onset time expressed as an output-stream frame index
    float strength;
    float bandsDb[kNumBands];
    float decayDb;
};

// Full-duplex engine: the metronome is rendered in the output callback, which also
// drains the microphone stream. Both streams are clocked by the same audio device,
// and the output/input frame relationship is measured from their hardware timestamps,
// so a detected hit can be compared directly with the click that was played.
class DrumEngine : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    DrumEngine();
    ~DrumEngine() override;

    bool start();
    void stop();
    bool isRunning() const { return running_.load(); }
    bool needsRestart() const { return needsRestart_.load(); }
    int sampleRate() const { return sampleRate_; }

    // Frame of the output stream that is currently audible (best estimate).
    int64_t playheadFrame();
    // Next frame the output callback will render.
    int64_t writeFrame() const { return writeFrame_.load(std::memory_order_acquire); }

    // Replaces the click schedule. Clicks must be sorted by frame.
    void setClicks(const std::vector<ScheduledClick> &clicks);
    void clearClicks();

    void setSensitivity(float s);
    void setNotchEnabled(bool enabled);

    // Pops detected onsets (aligned to the output clock). Returns count written.
    int pollOnsets(AlignedOnset *out, int maxCount);

    float inputPeak() { return inputPeak_.exchange(0.0f); }
    bool timestampsAligned() const { return timestampsOk_.load(); }
    double inputMinusOutputFrames() const { return inMinusOut_.load(); }
    int xruns() const { return xruns_.load(); }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream, void *audioData,
                                          int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) override;

private:
    bool openStreams();
    void closeStreams();
    void analysisLoop();
    void updateAlignment();
    void renderClicks(float *out, int32_t numFrames, int64_t firstFrame);
    void buildClickSounds();

    std::shared_ptr<oboe::AudioStream> output_;
    std::shared_ptr<oboe::AudioStream> input_;
    int sampleRate_ = 48000;

    // Click schedule: written only while clicksActive_ is false and the audio thread
    // has acknowledged it (see setClicks).
    std::vector<ScheduledClick> clicks_;
    size_t nextClick_ = 0;
    std::atomic<bool> clicksActive_{false};
    std::atomic<uint64_t> callbackCount_{0};

    struct Voice {
        const std::vector<float> *sound = nullptr;
        int64_t startFrame = 0;
    };
    Voice voices_[4];
    std::vector<float> clickSounds_[3];

    std::atomic<int64_t> writeFrame_{0};
    bool firstCallback_ = true;
    std::atomic<int64_t> inputBaseFrame_{-1};
    int64_t inputFramesRead_ = 0;
    std::vector<float> inputScratch_;

    SpscRing<float> inputRing_{1 << 17};
    SpscRing<AlignedOnset> alignedRing_{1024};

    std::atomic<float> inputPeak_{0.0f};
    std::atomic<bool> timestampsOk_{false};
    std::atomic<double> inMinusOut_{0.0};
    std::vector<double> alignHistory_;
    std::atomic<int64_t> outputFrameAtInputStart_{0};

    std::atomic<float> pendingSensitivity_{0.5f};
    std::atomic<bool> pendingNotch_{true};

    std::thread analysisThread_;
    std::atomic<bool> running_{false};
    std::atomic<bool> needsRestart_{false};
    std::atomic<int> xruns_{0};
    std::mutex lifecycleMutex_;
};
