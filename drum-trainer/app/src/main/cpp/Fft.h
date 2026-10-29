#pragma once

#include <cmath>
#include <complex>
#include <vector>

// Minimal in-place radix-2 FFT. Size must be a power of two.
class Fft {
public:
    explicit Fft(int size) : n_(size), twiddle_(size / 2), bitrev_(size) {
        for (int i = 0; i < n_ / 2; ++i) {
            double a = -2.0 * M_PI * i / n_;
            twiddle_[i] = {static_cast<float>(std::cos(a)), static_cast<float>(std::sin(a))};
        }
        int bits = 0;
        while ((1 << bits) < n_) ++bits;
        for (int i = 0; i < n_; ++i) {
            int r = 0;
            for (int b = 0; b < bits; ++b) {
                if (i & (1 << b)) r |= 1 << (bits - 1 - b);
            }
            bitrev_[i] = r;
        }
    }

    int size() const { return n_; }

    void forward(std::vector<std::complex<float>> &data) const {
        for (int i = 0; i < n_; ++i) {
            int j = bitrev_[i];
            if (j > i) std::swap(data[i], data[j]);
        }
        for (int len = 2; len <= n_; len <<= 1) {
            int half = len / 2;
            int step = n_ / len;
            for (int start = 0; start < n_; start += len) {
                for (int k = 0; k < half; ++k) {
                    std::complex<float> t = twiddle_[k * step] * data[start + k + half];
                    std::complex<float> u = data[start + k];
                    data[start + k] = u + t;
                    data[start + k + half] = u - t;
                }
            }
        }
    }

private:
    int n_;
    std::vector<std::complex<float>> twiddle_;
    std::vector<int> bitrev_;
};
