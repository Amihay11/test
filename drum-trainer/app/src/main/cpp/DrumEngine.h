#pragma once

#include <oboe/Oboe.h>

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>

#include "BackingSynth.h"
#include "OnsetDetector.h"
#include "SpscRing.h"

enum ClickType : int {
    kClickNormal = 0,
    kClickAccent = 1,
    kClickBroadband = 2,  // used for loopback latency calibration (not notched)
    kPadSoundBase = 3,    // kPadSoundBase + Pad.ordinal: drum sounds for playing patterns
    kGuideSoundBase = 11, // + 0..3: tonal guide sounds the detector ignores (GuideTones.h)
    kBackingSound = 15,   // the free-play backing loop, rendered by setBacking()
};

constexpr int kNumSounds = kBackingSound + 1;

struct ScheduledClick {
    int64_t frame;  // output-stream frame index
    int type;
    float gain;     // 1 = normal; accents louder, ghost notes softer
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

    // Renders a backing loop (see BackingSynth.h) into the kBackingSound slot. Stops the
    // current schedule; call before setClicks.
    void setBacking(const std::vector<backing::Note> &notes, int loopFrames);

    // Replaces the synthesized sound of a drum (0..7, the Pad order) with a recorded
    // sample, resampled to the stream rate. Kept across restarts.
    void setPadSample(int pad, std::vector<float> samples, int sampleRate);

    // Plays a sound as soon as possible (a tap on the on-screen drums). Any thread.
    void trigger(int type, float gain);

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
    void startVoice(int type, int64_t frame, float gain);
    void waitForCallbacks();
    std::vector<float> padSound(int pad) const;
    bool isPadSound(const std::vector<float> *s) const {
        return s >= &clickSounds_[kPadSoundBase] && s < &clickSounds_[kPadSoundBase + kNumPadSamples];
    }

    static constexpr int kNumPadSamples = 8;
    struct Sample {
        std::vector<float> data;
        int rate = 0;
    };
    Sample padSamples_[kNumPadSamples];
    // While a drum sound is being replaced, no drum voice plays.
    std::atomic<bool> swappingPads_{false};
    void buildClickSounds();

    std::shared_ptr<oboe::AudioStream> output_;
    std::shared_ptr<oboe::AudioStream> input_;
    int sampleRate_ = 48000;

    // Click schedule: written only while clicksActive_ is false and the audio thread
    // has acknowledged it (see setClicks).
    std::vector<ScheduledClick> clicks_;
    size_t nextClick_ = 0;
    std::atomic<bool> clicksActive_{false};
    struct Trigger {
        int type;
        float gain;
    };
    SpscRing<Trigger> triggers_{64};
    std::mutex triggerMutex_;  // several UI threads may trigger; the ring has one producer
    std::atomic<bool> dropBacking_{false};
    std::atomic<uint64_t> callbackCount_{0};

    struct Voice {
        const std::vector<float> *sound = nullptr;
        int64_t startFrame = 0;
        float gain = 1.0f;
    };
    // Enough for 16th notes on several drums while a crash rings out.
    Voice voices_[24];
    std::vector<float> clickSounds_[kNumSounds];

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
