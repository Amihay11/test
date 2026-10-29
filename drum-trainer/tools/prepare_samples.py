"""Turns the Pearl Master Studio samples into the app's drum sounds.

Download the originals first (CC BY 3.0, "Pearl Master Studio Pack 1" by enoe, via
https://github.com/oramics/sampled/tree/master/DRUMS/pearl-master-studio), then:

    python3 tools/prepare_samples.py <folder with the .wav files> app/src/main/assets/sounds

Each sound is mixed to mono, trimmed so the hit starts on the first sample (the app
schedules a sound at the frame the note is due), resampled to 48 kHz with a windowed
sinc, faded out, levelled, and written as 16-bit WAV named after the pad id.
"""
import sys
import wave
from pathlib import Path

import numpy as np

# pad id -> (source file, level relative to full scale)
SOUNDS = {
    "kick": ("kick-01.wav", 0.95),
    "snare": ("snare-01.wav", 0.85),
    "hihat": ("hihat-closed.wav", 0.55),
    "tom_high": ("tom-01.wav", 0.75),
    "tom_mid": ("tom-02.wav", 0.75),
    "tom_floor": ("tom-03.wav", 0.8),
    "crash": ("crash-01.wav", 0.6),
    "ride": ("ride-01.wav", 0.5),
}
OUT_RATE = 48000
FADE_MS = 30


def read(path):
    with wave.open(str(path)) as w:
        assert w.getsampwidth() == 2
        x = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float64) / 32768
        return x.reshape(-1, w.getnchannels()).mean(axis=1), w.getframerate()


def resample(x, sr_in, sr_out, taps=32):
    """Band-limited interpolation (Hann-windowed sinc)."""
    if sr_in == sr_out:
        return x
    n_out = int(len(x) * sr_out / sr_in)
    t = np.arange(n_out) * sr_in / sr_out
    cutoff = min(1.0, sr_out / sr_in) * 0.95
    base = np.floor(t).astype(int)
    out = np.zeros(n_out)
    padded = np.concatenate([np.zeros(taps), x, np.zeros(taps + 1)])
    for k in range(-taps + 1, taps + 1):
        idx = base + k
        d = t - idx
        w = 0.5 + 0.5 * np.cos(np.pi * d / taps)
        out += padded[idx + taps] * cutoff * np.sinc(cutoff * d) * w
    return out


def main(src, dst):
    dst.mkdir(parents=True, exist_ok=True)
    for pad, (name, level) in SOUNDS.items():
        x, sr = read(src / name)
        peak = np.abs(x).max()
        start = max(0, int(np.argmax(np.abs(x) > peak * 0.05)) - int(sr * 0.0003))
        y = resample(x[start:], sr, OUT_RATE)
        fade = int(OUT_RATE * FADE_MS / 1000)
        y[-fade:] *= np.linspace(1, 0, fade)
        y *= level / np.abs(y).max()
        with wave.open(str(dst / f"{pad}.wav"), "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(OUT_RATE)
            w.writeframes((np.clip(y, -1, 1) * 32767).astype(np.int16).tobytes())
        print(f"{pad:10s} {name:18s} {len(y) / OUT_RATE:.2f}s")


if __name__ == "__main__":
    main(Path(sys.argv[1]), Path(sys.argv[2]))
