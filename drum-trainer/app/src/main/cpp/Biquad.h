#pragma once

#include <cmath>

// RBJ-cookbook biquad, transposed direct form II.
class Biquad {
public:
    void setNotch(double sampleRate, double freq, double q) {
        double w0 = 2.0 * M_PI * freq / sampleRate;
        double alpha = std::sin(w0) / (2.0 * q);
        double a0 = 1.0 + alpha;
        b0_ = static_cast<float>(1.0 / a0);
        b1_ = static_cast<float>(-2.0 * std::cos(w0) / a0);
        b2_ = static_cast<float>(1.0 / a0);
        a1_ = static_cast<float>(-2.0 * std::cos(w0) / a0);
        a2_ = static_cast<float>((1.0 - alpha) / a0);
    }

    void setHighPass(double sampleRate, double freq, double q) {
        double w0 = 2.0 * M_PI * freq / sampleRate;
        double alpha = std::sin(w0) / (2.0 * q);
        double c = std::cos(w0);
        double a0 = 1.0 + alpha;
        b0_ = static_cast<float>((1.0 + c) / 2.0 / a0);
        b1_ = static_cast<float>(-(1.0 + c) / a0);
        b2_ = static_cast<float>((1.0 + c) / 2.0 / a0);
        a1_ = static_cast<float>(-2.0 * c / a0);
        a2_ = static_cast<float>((1.0 - alpha) / a0);
    }

    float process(float x) {
        float y = b0_ * x + z1_;
        z1_ = b1_ * x - a1_ * y + z2_;
        z2_ = b2_ * x - a2_ * y;
        return y;
    }

    void reset() { z1_ = z2_ = 0.0f; }

private:
    float b0_ = 1, b1_ = 0, b2_ = 0, a1_ = 0, a2_ = 0;
    float z1_ = 0, z2_ = 0;
};
