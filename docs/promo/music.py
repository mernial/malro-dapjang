# 홍보 영상 배경음악·효과음을 직접 합성 (저작권 걱정 없음)
import numpy as np, json, subprocess
from scipy.signal import fftconvolve, butter, sosfilt
from scipy.io import wavfile

SR = 48000
DUR = 60.0
N = int(SR * DUR)
rng = np.random.default_rng(7)

def note(n):  # 'A4' 같은 이름 → Hz
    names = {'C':0,'C#':1,'D':2,'D#':3,'E':4,'F':5,'F#':6,'G':7,'G#':8,'A':9,'A#':10,'B':11}
    name, octv = n[:-1], int(n[-1])
    midi = 12 * (octv + 1) + names[name]
    return 440 * 2 ** ((midi - 69) / 12)

def env_adsr(n, a, d, s, r_start, r):
    t = np.arange(n) / SR
    e = np.where(t < a, t / a, s + (1 - s) * np.exp(-(t - a) / d))
    if r_start is not None:
        e *= np.where(t < r_start, 1, np.exp(-(t - r_start) / r))
    return e

def add(buf, sig, at, gain=1.0, pan=0.0):
    i = int(at * SR)
    if i < 0: sig = sig[-i:]; i = 0
    if i >= len(buf): return
    sig = sig[: len(buf) - i]
    l = np.cos((pan + 1) * np.pi / 4); r = np.sin((pan + 1) * np.pi / 4)
    buf[i:i + len(sig), 0] += sig * gain * l * 1.414
    buf[i:i + len(sig), 1] += sig * gain * r * 1.414

def lp(x, f, order=2):
    return sosfilt(butter(order, f, 'low', fs=SR, output='sos'), x)
def hp(x, f, order=2):
    return sosfilt(butter(order, f, 'high', fs=SR, output='sos'), x)

# ── 악기 ──
def piano(f, dur=3.5, vel=1.0):
    n = int(dur * SR); t = np.arange(n) / SR
    s = np.zeros(n)
    for k in range(1, 9):
        fk = f * k * (1 + 0.0004 * k * k)          # 살짝 어긋난 배음 = 진짜 현 느낌
        amp = (1 / k ** 1.6) * (0.6 + 0.4 * vel)
        dec = 2.8 / (1 + 0.55 * (k - 1)) * (220 / max(f, 110)) ** 0.3
        s += amp * np.sin(2 * np.pi * fk * t + rng.uniform(0, 6.28)) * np.exp(-t / dec)
    att = np.minimum(1, t / 0.006)
    hammer = lp(rng.standard_normal(n) * np.exp(-t / 0.012), 2500) * 0.05
    out = (s * att + hammer) * vel
    return lp(out, 4800 + 2500 * vel)               # 펠트 피아노처럼 부드럽게

def pad(freqs, dur):
    n = int(dur * SR); t = np.arange(n) / SR
    s = np.zeros(n)
    for f in freqs:
        for c in (-6, 0, 6):
            fc = f * 2 ** (c / 1200)
            s += np.sin(2 * np.pi * fc * t + rng.uniform(0, 6.28)) + 0.18 * np.sin(4 * np.pi * fc * t)
    e = np.minimum(1, t / 1.4) * np.minimum(1, (dur - t) / 1.4).clip(0, 1)
    return hp(lp(s * e / len(freqs) / 3, 2200), 160)

def bass(f, dur):
    n = int(dur * SR); t = np.arange(n) / SR
    s = np.sin(2 * np.pi * f * t) + 0.25 * np.sin(4 * np.pi * f * t)
    e = np.minimum(1, t / 0.04) * (0.55 + 0.45 * np.exp(-t / 1.2)) * np.minimum(1, (dur - t) / 0.25).clip(0, 1)
    return hp(lp(s * e, 400), 38)

def kick():
    n = int(0.45 * SR); t = np.arange(n) / SR
    f = 48 + 70 * np.exp(-t / 0.04)
    ph = 2 * np.pi * np.cumsum(f) / SR
    return np.sin(ph) * np.exp(-t / 0.16) * np.minimum(1, t / 0.002)

def shaker():
    n = int(0.12 * SR); t = np.arange(n) / SR
    return hp(rng.standard_normal(n), 6000, 4) * np.minimum(1, t / 0.015) * np.exp(-t / 0.035)

def bell(f, dur=4.0):
    n = int(dur * SR); t = np.arange(n) / SR
    s = (np.sin(2 * np.pi * f * t + 1.2 * np.sin(2 * np.pi * f * 3.5 * t) * np.exp(-t / 0.4)) * np.exp(-t / 1.6)
         + 0.35 * np.sin(2 * np.pi * f * 2.76 * t) * np.exp(-t / 0.6)
         + 0.2 * np.sin(2 * np.pi * f * 5.4 * t) * np.exp(-t / 0.25))
    return s * np.minimum(1, t / 0.002)

def reverb_ir(sec=3.2, damp=0.35):
    n = int(sec * SR); t = np.arange(n) / SR
    ir = np.zeros((n, 2))
    for ch in range(2):
        x = rng.standard_normal(n) * np.exp(-t / damp * 1.0) * np.exp(-t * 1.2)
        # 시간이 갈수록 어두워지게
        x = 0.6 * lp(x, 5000) + 0.4 * lp(x, 1500)
        x[: int(0.012 * SR)] = 0  # 첫 반사 전 틈
        ir[:, ch] = x / np.sqrt(np.sum(x ** 2))
    return ir

IR = reverb_ir()
def verb(buf, wet):
    out = np.zeros_like(buf)
    for ch in range(2):
        out[:, ch] = fftconvolve(buf[:, ch], IR[:, ch])[: len(buf)]
    return buf * (1 - wet) + out * wet * 1.6

# ── 곡 (BPM 80, 한 마디 3초) ──
BAR = 3.0; BEAT = 0.75
prog = [
    ('G2', ['B3', 'D4', 'F#4', 'A4']),     # Gmaj9
    ('A2', ['C#4', 'E4', 'F#4', 'B4']),    # A6/9
    ('F#2', ['A3', 'C#4', 'E4', 'F#4']),   # F#m7
    ('B2', ['D4', 'F#4', 'A4', 'C#5']),    # Bm9
]
END_CHORD = ('D2', ['F#3', 'A3', 'C#4', 'E4', 'A4'])  # Dmaj9 마무리

music = np.zeros((N + SR * 4, 2))
pads = np.zeros_like(music); keys = np.zeros_like(music); low = np.zeros_like(music); perc = np.zeros_like(music)

bars = int(54 / BAR)  # 0~54초: 18마디, 그 뒤 마무리 화음
for b in range(bars):
    t0 = b * BAR
    root, ch = prog[b % 4]
    fr = [note(x) for x in ch]
    add(pads, pad(fr, BAR + 1.4), t0 - 0.2, 0.2)
    # 피아노: 마디 첫 박에 화음(살짝 흩어 치기)
    for j, f in enumerate(fr):
        add(keys, piano(f, 4.0, 0.55), t0 + j * 0.018, 0.16, pan=-0.3 + 0.2 * j)
    # 5초 뒤부터 잔잔한 분산화음 (8분음표)
    if t0 >= 5.5:
        pattern = [0, 2, 1, 3, 2, 1, 3, 2]
        for k, p in enumerate(pattern):
            if k == 0: continue
            f = fr[p] * (2 if p < 2 and k % 2 else 1)
            add(keys, piano(f, 2.5, 0.35 + 0.1 * (k % 2 == 0)), t0 + k * BEAT / 2, 0.075, pan=0.35 * np.sin(k))
    # 베이스
    if t0 >= 5.5:
        add(low, bass(note(root), BAR), t0, 0.17)
    # 27~53초: 아주 부드러운 박동
    if 27 <= t0 < 53:
        for beat in range(4):
            if beat in (0, 2): add(perc, kick(), t0 + beat * BEAT, 0.2)
        for e8 in range(8):
            add(perc, shaker(), t0 + e8 * BEAT / 2, 0.05 if e8 % 2 else 0.025, pan=0.25)

# 마무리 화음
t0 = 54.0
fr = [note(x) for x in END_CHORD[1]]
add(pads, pad(fr, 7.0), t0 - 0.2, 0.24)
for j, f in enumerate(fr):
    add(keys, piano(f, 6.5, 0.6), t0 + j * 0.06, 0.15, pan=-0.4 + 0.2 * j)
add(low, bass(note(END_CHORD[0]), 6.0), t0, 0.16)
add(keys, bell(note('A5'), 5), t0 + 0.4, 0.05, pan=0.3)

music = verb(pads, 0.5) + verb(keys, 0.38) + low + verb(perc, 0.12)
music = music[:N]

# 처음 1초 서서히, 끝 3초 서서히
t = np.arange(N) / SR
fade = np.minimum(1, t / 1.2) * np.clip((DUR - t) / 3.0, 0, 1)
music *= fade[:, None]

# ── 목소리 자리에서 음악 낮추기 ──
voices = json.load(open('voices.json'))   # [[파일, 시작초, 길이초], ...]
duck = np.ones(N)
for _, at, ln in voices:
    a, b = at - 0.35, at + ln + 0.5
    duck = np.minimum(duck, np.interp(t, [a - 0.35, a, b, b + 0.6], [1, 0.32, 0.32, 1], left=1, right=1))
music *= duck[:, None]

# ── 효과음 ──
sfx = np.zeros((N + SR * 4, 2))
def swish(dur=0.7):
    n = int(dur * SR); tt = np.arange(n) / SR
    x = rng.standard_normal(n)
    # 소리가 열렸다 닫히는 느낌: 대역통과의 중심을 올렸다 내림
    lo = lp(x, 900); hi = lp(x, 3500)
    mix = np.interp(tt, [0, dur * 0.6, dur], [0, 1, 0.2])
    e = np.sin(np.pi * np.clip(tt / dur, 0, 1)) ** 2
    return hp((lo * (1 - mix) + hi * mix) * e, 250)

def tap():
    n = int(0.25 * SR); tt = np.arange(n) / SR
    click = np.sin(2 * np.pi * 2300 * tt) * np.exp(-tt / 0.006)
    body = np.sin(2 * np.pi * 320 * tt) * np.exp(-tt / 0.035)
    return (0.5 * click + body) * np.minimum(1, tt / 0.0008)

def chime(notes, gap=0.07, g=1.0):
    out = np.zeros(int((4.5 + gap * len(notes)) * SR))
    for i, nn in enumerate(notes):
        b = bell(note(nn), 4.0)
        s0 = int(i * gap * SR); out[s0:s0 + len(b)] += b * (0.8 ** i)
    return out * g

def send_whoosh():
    n = int(0.32 * SR); tt = np.arange(n) / SR
    f = 380 + 900 * (tt / tt[-1]) ** 1.5
    tone = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.sin(np.pi * tt / tt[-1]) ** 2
    air = hp(rng.standard_normal(n), 2500) * np.sin(np.pi * tt / tt[-1]) ** 3 * 0.25
    return tone * 0.6 + air

events = json.load(open('sfx.json'))
for kind, at in events:
    if kind == 'swish':  add(sfx, swish(), at - 0.25, 0.05)
    if kind == 'intro':  add(sfx, chime(['D6', 'A6', 'F#6'], 0.11, 1), at, 0.06, pan=0.15)
    if kind == 'tap':    add(sfx, tap(), at, 0.22, pan=0.25)
    if kind == 'result': add(sfx, chime(['A5', 'D6']), at, 0.075, pan=-0.1)
    if kind == 'found':  add(sfx, chime(['F#5', 'A5', 'D6'], 0.09), at, 0.075, pan=0.1)
    if kind == 'send':   add(sfx, send_whoosh(), at, 0.12, pan=0.3)
    if kind == 'soft':   add(sfx, chime(['D6'], g=0.6), at, 0.03, pan=0.2)
sfx = verb(sfx, 0.35)[:N]

out = music + sfx
wavfile.write('bgm_sfx.wav', SR, (out / max(1.0, np.abs(out).max() / 0.7)).astype(np.float32))
print('peak', np.abs(out).max())
