#!/usr/bin/env python3
"""Synthesises tutti's intro chime: app/src/main/res/raw/intro_chime.ogg.

Two soft bell tones a fifth apart (D5, then A5 70 ms later), sine partials only, a short
attack and an exponential decay, faded to silence by 560 ms and peaking near -14 dBFS:
warm and quiet, a landing rather than a jingle. Standard library only; ffmpeg (libvorbis)
encodes it. Deterministic, so rerunning it rewrites the same sound.

    python3 scripts/intro-chime.py
"""
import math
import os
import struct
import subprocess
import tempfile
import wave

RATE = 44100
LENGTH_S = 0.56
PEAK = 10 ** (-14 / 20)

# (start s, fundamental Hz, level): the second tone is a touch quieter, so it rounds off.
TONES = [(0.0, 587.33, 1.0), (0.07, 880.0, 0.7)]
# A soft bell: the fundamental, a quiet octave, a quieter inharmonic shimmer.
PARTIALS = [(1.0, 1.0, 0.20), (2.0, 0.18, 0.12), (2.76, 0.05, 0.07)]
ATTACK_S = 0.008
TAIL_S = 0.12


def sample(t: float) -> float:
    value = 0.0
    for start, freq, level in TONES:
        local = t - start
        if local < 0:
            continue
        attack = min(1.0, local / ATTACK_S)
        for ratio, gain, decay in PARTIALS:
            value += level * gain * attack * math.exp(-local / decay) * math.sin(2 * math.pi * freq * ratio * local)
    # The last stretch fades to exact silence, so nothing clicks at the end.
    remaining = LENGTH_S - t
    if remaining < TAIL_S:
        value *= 0.5 - 0.5 * math.cos(math.pi * max(0.0, remaining) / TAIL_S)
    return value


def main() -> None:
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    out = os.path.join(root, "app", "src", "main", "res", "raw", "intro_chime.ogg")
    frames = [sample(i / RATE) for i in range(int(LENGTH_S * RATE))]
    scale = PEAK / max(abs(v) for v in frames)
    with tempfile.TemporaryDirectory() as tmp:
        pcm = os.path.join(tmp, "chime.wav")
        with wave.open(pcm, "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(RATE)
            w.writeframes(b"".join(struct.pack("<h", round(v * scale * 32767)) for v in frames))
        subprocess.run(
            ["ffmpeg", "-y", "-loglevel", "error", "-i", pcm, "-map_metadata", "-1", "-fflags", "+bitexact",
             "-c:a", "libvorbis", "-q:a", "2", out],
            check=True,
        )
    print(f"{out}: {os.path.getsize(out)} bytes")


if __name__ == "__main__":
    main()
