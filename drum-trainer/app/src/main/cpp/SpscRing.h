#pragma once

#include <atomic>
#include <cstddef>
#include <vector>

// Lock-free single-producer / single-consumer ring buffer.
// Capacity is rounded up to a power of two.
template <typename T>
class SpscRing {
public:
    explicit SpscRing(size_t capacity) {
        size_t c = 1;
        while (c < capacity) c <<= 1;
        buf_.resize(c);
        mask_ = c - 1;
    }

    // Producer side. Returns the number of items actually written.
    size_t push(const T *items, size_t count) {
        size_t head = head_.load(std::memory_order_relaxed);
        size_t tail = tail_.load(std::memory_order_acquire);
        size_t free = buf_.size() - (head - tail);
        size_t n = count < free ? count : free;
        for (size_t i = 0; i < n; ++i) buf_[(head + i) & mask_] = items[i];
        head_.store(head + n, std::memory_order_release);
        return n;
    }

    bool push(const T &item) { return push(&item, 1) == 1; }

    // Consumer side. Returns the number of items read.
    size_t pop(T *out, size_t maxCount) {
        size_t tail = tail_.load(std::memory_order_relaxed);
        size_t head = head_.load(std::memory_order_acquire);
        size_t avail = head - tail;
        size_t n = maxCount < avail ? maxCount : avail;
        for (size_t i = 0; i < n; ++i) out[i] = buf_[(tail + i) & mask_];
        tail_.store(tail + n, std::memory_order_release);
        return n;
    }

    size_t available() const {
        return head_.load(std::memory_order_acquire) - tail_.load(std::memory_order_acquire);
    }

private:
    std::vector<T> buf_;
    size_t mask_ = 0;
    std::atomic<size_t> head_{0};
    std::atomic<size_t> tail_{0};
};
