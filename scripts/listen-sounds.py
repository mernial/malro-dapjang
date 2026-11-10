# 말로 답장 듣기 신호음: 부드럽고 따뜻한 '실로폰·벨' 두 음 (날카로운 고음·딸깍 소리 없이)
import numpy as np, scipy.io.wavfile as w, scipy.signal as sg, subprocess
SR = 44100
def note(f, dur=0.9, amp=1.0):
    t = np.arange(int(SR*dur))/SR
    env = (1 - np.exp(-t/0.008)) * np.exp(-t/0.28)          # 부드러운 시작, 은은한 꼬리
    x = (np.sin(2*np.pi*f*t)
         + 0.28*np.sin(2*np.pi*2*f*t)*np.exp(-t/0.12)          # 맑은 배음 조금
         + 0.06*np.sin(2*np.pi*3.01*f*t)*np.exp(-t/0.06))
    return amp*x*env
def mix(parts, total):
    out = np.zeros(int(SR*total))
    for start, sig in parts:
        i = int(SR*start); out[i:i+len(sig)] += sig[:len(out)-i]
    return out
def finish(x, name):
    b, a = sg.butter(2, 3800/(SR/2)); x = sg.lfilter(b, a, x)     # 고음을 깎아 날카롭지 않게
    # 작은 방 울림
    y = x.copy()
    for d, g in [(0.031, .22), (0.047, .16), (0.073, .1), (0.11, .06)]:
        k = int(SR*d); y[k:] += g*x[:-k]
    y = y/np.max(np.abs(y))*0.42                                 # 크게 울리지 않게
    fade = np.linspace(1, 0, int(SR*0.08)); y[-len(fade):] *= fade
    w.write(name+'.wav', SR, (y*32767).astype(np.int16))
    subprocess.run(['ffmpeg','-y','-loglevel','error','-i',name+'.wav','-c:a','libvorbis','-q:a','4',name+'.ogg'], check=True)
C5, E5, G5, A4 = 523.25, 659.25, 783.99, 440.0
finish(mix([(0, note(G5*0.5*2/2*1.0 if False else 587.33, 0.7, .9)), (0.11, note(783.99, 0.8, 1.0))], 0.95), 'listen_start')  # 레→솔 올라감
finish(mix([(0, note(783.99, 0.7, .9)), (0.12, note(587.33, 0.9, 1.0))], 1.05), 'listen_done')                                 # 솔→레 내려옴
