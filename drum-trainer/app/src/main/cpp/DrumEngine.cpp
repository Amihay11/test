#include "DrumEngine.h"

#include <android/log.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <ctime>

#define LOG_TAG "DrumEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

int64_t nowNanos() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000000000LL + ts.tv_nsec;
}

}  // namespace

DrumEngine::DrumEngine() = default;

DrumEngine::~DrumEngine() { stop(); }

bool DrumEngine::start() {
    std::lock_guard<std::mutex> lock(lifecycleMutex_);
    if (running_.load()) return true;

    // Discard anything left over from a previous session.
    float drain[1024];
    while (inputRing_.pop(drain, 1024) > 0) {}
    AlignedOnset drainOnsets[64];
    while (alignedRing_.pop(drainOnsets, 64) > 0) {}

    firstCallback_ = true;
    inputBaseFrame_.store(-1);
    inputFramesRead_ = 0;
    timestampsOk_.store(false);
    alignHistory_.clear();
    clicksActive_.store(false);
    for (auto &v : voices_) v.sound = nullptr;
    needsRestart_.store(false);

    if (!openStreams()) {
        closeStreams();
        return false;
    }
    buildClickSounds();

    running_.store(true);
    analysisThread_ = std::thread(&DrumEngine::analysisLoop, this);

    if (input_->requestStart() != oboe::Result::OK || output_->requestStart() != oboe::Result::OK) {
        LOGW("Failed to start streams");
        running_.store(false);
        if (analysisThread_.joinable()) analysisThread_.join();
        closeStreams();
        return false;
    }
    LOGI("Started: rate=%d outBurst=%d inPreset=%d", sampleRate_, output_->getFramesPerBurst(),
         static_cast<int>(input_->getInputPreset()));
    return true;
}

void DrumEngine::stop() {
    std::lock_guard<std::mutex> lock(lifecycleMutex_);
    bool wasRunning = running_.exchange(false);
    if (analysisThread_.joinable()) analysisThread_.join();
    if (wasRunning || output_ || input_) closeStreams();
}

bool DrumEngine::openStreams() {
    oboe::AudioStreamBuilder out;
    out.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(oboe::ChannelCount::Mono)
        ->setUsage(oboe::Usage::Game)
        ->setDataCallback(this)
        ->setErrorCallback(this);
    if (out.openStream(output_) != oboe::Result::OK) {
        LOGW("Could not open output stream");
        return false;
    }
    sampleRate_ = output_->getSampleRate();
    output_->setBufferSizeInFrames(output_->getFramesPerBurst() * 2);

    const oboe::InputPreset presets[] = {oboe::InputPreset::Unprocessed,
                                         oboe::InputPreset::VoiceRecognition,
                                         oboe::InputPreset::Generic};
    for (auto preset : presets) {
        oboe::AudioStreamBuilder in;
        in.setDirection(oboe::Direction::Input)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(oboe::ChannelCount::Mono)
            ->setSampleRate(sampleRate_)
            ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
            ->setInputPreset(preset);
        if (in.openStream(input_) == oboe::Result::OK) break;
        input_.reset();
    }
    if (!input_) {
        LOGW("Could not open input stream");
        return false;
    }
    inputScratch_.assign(std::max(4096, input_->getBufferCapacityInFrames()), 0.0f);
    return true;
}

void DrumEngine::closeStreams() {
    if (output_) {
        output_->stop();
        output_->close();
        output_.reset();
    }
    if (input_) {
        input_->stop();
        input_->close();
        input_.reset();
    }
}

void DrumEngine::buildClickSounds() {
    const int len = sampleRate_ * 40 / 1000;
    const int attackLen = sampleRate_ * 3 / 1000;
    auto tone = [&](double freq, float amp) {
        std::vector<float> s(len);
        for (int i = 0; i < len; ++i) {
            double t = static_cast<double>(i) / sampleRate_;
            double env = i < attackLen ? 0.5 - 0.5 * std::cos(M_PI * i / attackLen) : 1.0;
            env *= std::exp(-t / 0.008);
            s[i] = static_cast<float>(amp * env * std::sin(2.0 * M_PI * freq * t));
        }
        return s;
    };
    clickSounds_[kClickNormal] = tone(kClickFreqNormal, 0.5f);
    clickSounds_[kClickAccent] = tone(kClickFreqAccent, 0.8f);

    // Short broadband burst: easy to time precisely through speaker and microphone.
    const int burstLen = sampleRate_ * 4 / 1000;
    std::vector<float> burst(burstLen);
    uint32_t seed = 12345;
    for (int i = 0; i < burstLen; ++i) {
        seed = seed * 1664525u + 1013904223u;
        float white = static_cast<float>(seed >> 8) / static_cast<float>(1 << 24) * 2.0f - 1.0f;
        double t = static_cast<double>(i) / sampleRate_;
        burst[i] = static_cast<float>(0.9 * white * std::exp(-t / 0.0008));
    }
    clickSounds_[kClickBroadband] = burst;
}

void DrumEngine::setClicks(const std::vector<ScheduledClick> &clicks) {
    clearClicks();
    clicks_ = clicks;
    nextClick_ = 0;
    clicksActive_.store(true, std::memory_order_release);
}

void DrumEngine::clearClicks() {
    clicksActive_.store(false, std::memory_order_release);
    // Wait until the audio thread has finished any callback that may still be reading
    // the old schedule.
    if (running_.load()) {
        uint64_t seen = callbackCount_.load();
        for (int i = 0; i < 200 && callbackCount_.load() < seen + 2; ++i) {
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
        }
    }
}

void DrumEngine::setSensitivity(float s) { pendingSensitivity_.store(s); }

void DrumEngine::setNotchEnabled(bool enabled) { pendingNotch_.store(enabled); }

int DrumEngine::pollOnsets(AlignedOnset *out, int maxCount) {
    return static_cast<int>(alignedRing_.pop(out, static_cast<size_t>(maxCount)));
}

int64_t DrumEngine::playheadFrame() {
    auto out = output_;
    if (!out) return writeFrame();
    auto ts = out->getTimestamp(CLOCK_MONOTONIC);
    if (ts) {
        double elapsed = static_cast<double>(nowNanos() - ts.value().timestamp) * sampleRate_ / 1e9;
        return ts.value().position + static_cast<int64_t>(elapsed);
    }
    return writeFrame() - out->getBufferSizeInFrames();
}

void DrumEngine::renderClicks(float *out, int32_t numFrames, int64_t firstFrame) {
    int64_t endFrame = firstFrame + numFrames;
    if (clicksActive_.load(std::memory_order_acquire)) {
        while (nextClick_ < clicks_.size() && clicks_[nextClick_].frame < endFrame) {
            const ScheduledClick &c = clicks_[nextClick_++];
            const std::vector<float> &sound = clickSounds_[std::clamp(c.type, 0, 2)];
            if (c.frame + static_cast<int64_t>(sound.size()) <= firstFrame) continue;  // too late
            for (auto &v : voices_) {
                if (v.sound == nullptr) {
                    v.sound = &sound;
                    v.startFrame = c.frame;
                    break;
                }
            }
        }
    }
    for (auto &v : voices_) {
        if (v.sound == nullptr) continue;
        const std::vector<float> &s = *v.sound;
        int64_t len = static_cast<int64_t>(s.size());
        for (int32_t i = 0; i < numFrames; ++i) {
            int64_t idx = firstFrame + i - v.startFrame;
            if (idx >= 0 && idx < len) out[i] += s[idx];
        }
        if (endFrame - v.startFrame >= len) v.sound = nullptr;
    }
}

oboe::DataCallbackResult DrumEngine::onAudioReady(oboe::AudioStream *stream, void *audioData,
                                                  int32_t numFrames) {
    auto *out = static_cast<float *>(audioData);
    std::fill(out, out + numFrames, 0.0f);

    if (firstCallback_) {
        firstCallback_ = false;
        writeFrame_.store(stream->getFramesWritten(), std::memory_order_release);
    }
    int64_t firstFrame = writeFrame_.load(std::memory_order_relaxed);

    // Drain everything the microphone has captured so far (non-blocking).
    if (input_) {
        float peak = 0.0f;
        for (int guard = 0; guard < 8; ++guard) {
            if (inputBaseFrame_.load(std::memory_order_relaxed) < 0) {
                outputFrameAtInputStart_.store(firstFrame, std::memory_order_relaxed);
                int64_t base = input_->getFramesRead();
                auto r = input_->read(inputScratch_.data(), static_cast<int32_t>(inputScratch_.size()), 0);
                if (!r || r.value() <= 0) break;
                inputBaseFrame_.store(base, std::memory_order_release);
                inputRing_.push(inputScratch_.data(), static_cast<size_t>(r.value()));
                for (int i = 0; i < r.value(); ++i) peak = std::max(peak, std::fabs(inputScratch_[i]));
                inputFramesRead_ += r.value();
                continue;
            }
            auto r = input_->read(inputScratch_.data(), static_cast<int32_t>(inputScratch_.size()), 0);
            if (!r || r.value() <= 0) break;
            size_t pushed = inputRing_.push(inputScratch_.data(), static_cast<size_t>(r.value()));
            if (pushed < static_cast<size_t>(r.value())) xruns_.fetch_add(1);
            for (int i = 0; i < r.value(); ++i) peak = std::max(peak, std::fabs(inputScratch_[i]));
            inputFramesRead_ += r.value();
        }
        if (peak > inputPeak_.load(std::memory_order_relaxed)) {
            inputPeak_.store(peak, std::memory_order_relaxed);
        }
    }

    renderClicks(out, numFrames, firstFrame);

    writeFrame_.store(firstFrame + numFrames, std::memory_order_release);
    callbackCount_.fetch_add(1, std::memory_order_release);
    return oboe::DataCallbackResult::Continue;
}

void DrumEngine::onErrorAfterClose(oboe::AudioStream *, oboe::Result error) {
    LOGW("Stream error: %s", oboe::convertToText(error));
    needsRestart_.store(true);
}

void DrumEngine::updateAlignment() {
    auto out = output_;
    auto in = input_;
    if (!out || !in) return;
    auto to = out->getTimestamp(CLOCK_MONOTONIC);
    auto ti = in->getTimestamp(CLOCK_MONOTONIC);
    if (to && ti) {
        // Input frame index minus output frame index at the same physical instant.
        double k = static_cast<double>(ti.value().position - to.value().position) +
                   static_cast<double>(to.value().timestamp - ti.value().timestamp) * sampleRate_ / 1e9;
        alignHistory_.push_back(k);
        if (alignHistory_.size() > 15) alignHistory_.erase(alignHistory_.begin());
        std::vector<double> sorted = alignHistory_;
        std::nth_element(sorted.begin(), sorted.begin() + sorted.size() / 2, sorted.end());
        inMinusOut_.store(sorted[sorted.size() / 2]);
        timestampsOk_.store(true);
    } else if (!timestampsOk_.load()) {
        // Without timestamps, assume both streams started together. Only a per-session
        // loopback calibration can then correct the remaining offset.
        int64_t base = inputBaseFrame_.load();
        if (base >= 0) inMinusOut_.store(static_cast<double>(base - outputFrameAtInputStart_.load()));
    }
}

void DrumEngine::analysisLoop() {
    OnsetDetector detector(sampleRate_);
    float sensitivity = -1.0f;
    bool notch = !pendingNotch_.load();
    std::vector<float> buf(4096);
    int64_t consumed = 0;
    auto lastAlign = std::chrono::steady_clock::now() - std::chrono::seconds(1);

    while (running_.load()) {
        float s = pendingSensitivity_.load();
        if (s != sensitivity) {
            sensitivity = s;
            detector.setSensitivity(s);
        }
        bool n = pendingNotch_.load();
        if (n != notch) {
            notch = n;
            detector.setNotchEnabled(n);
        }

        auto now = std::chrono::steady_clock::now();
        if (now - lastAlign > std::chrono::milliseconds(250)) {
            lastAlign = now;
            updateAlignment();
            if (output_) xruns_.store(std::max(xruns_.load(), output_->getXRunCount().value()));
        }

        int64_t base = inputBaseFrame_.load(std::memory_order_acquire);
        size_t got = base >= 0 ? inputRing_.pop(buf.data(), buf.size()) : 0;
        if (got == 0) {
            std::this_thread::sleep_for(std::chrono::milliseconds(2));
            continue;
        }
        double k = inMinusOut_.load();
        detector.process(buf.data(), static_cast<int>(got), base + consumed, [&](const OnsetEvent &e) {
            AlignedOnset a{static_cast<double>(e.frame) - k, e.strength};
            alignedRing_.push(a);
        });
        consumed += static_cast<int64_t>(got);
    }
}
