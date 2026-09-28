// 合成宣传片配乐与音效（纯代码合成，无第三方素材）→ out/audio.wav
// 配乐：120 BPM，F 大调 I–V–vi–IV（F–C–Dm–B♭），每小节 2 秒，与画面转场对齐。
// 音效：读取 index.html 导出的 out/cues.json（先运行 node render.mjs cues）。
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.dirname(fileURLToPath(import.meta.url));
const OUT = path.join(ROOT, 'out');
const { duration: DUR, cues } = JSON.parse(fs.readFileSync(path.join(OUT, 'cues.json'), 'utf8'));
const SR = 44100;
const N = Math.ceil(DUR * SR);
const BEAT = .5, BAR = 2;

// 总线：duck（被底鼓压缩的铺底）、dry、fx（音效）、send（混响发送）
const bus = name => ({ name, L: new Float32Array(N), R: new Float32Array(N) });
const duck = bus('duck'), dry = bus('dry'), fx = bus('fx'), send = bus('send');

let seed = 20251015;
const rnd = () => { seed |= 0; seed = seed + 0x6D2B79F5 | 0; let t = Math.imul(seed ^ seed >>> 15, 1 | seed); t = t + Math.imul(t ^ t >>> 7, 61 | t) ^ t; return ((t ^ t >>> 14) >>> 0) / 4294967296; };
const mtof = m => 440 * Math.pow(2, (m - 69) / 12);
const TAU = Math.PI * 2;

/** 往总线写一段：gen(i, tLocal) 返回单声道样本；pan -1..1；sendAmt 为混响发送量 */
function put(b, t0, len, gen, { pan = 0, gain = 1, sendAmt = 0 } = {}) {
  const s0 = Math.round(t0 * SR), n = Math.round(len * SR);
  const gl = Math.cos((pan + 1) * Math.PI / 4) * gain, gr = Math.sin((pan + 1) * Math.PI / 4) * gain;
  for (let i = 0; i < n; i++) {
    const k = s0 + i; if (k < 0 || k >= N) continue;
    const v = gen(i, i / SR);
    b.L[k] += v * gl; b.R[k] += v * gr;
    if (sendAmt) { send.L[k] += v * gl * sendAmt; send.R[k] += v * gr * sendAmt; }
  }
}
/** Chamberlin 状态变量滤波器 */
function svf() {
  let low = 0, band = 0;
  return (x, fc, q = .7) => {
    const f = 2 * Math.sin(Math.PI * Math.min(fc, SR / 6) / SR);
    low += f * band; const high = x - low - band / q; band += f * high;
    return { low, band, high };
  };
}
const noise = () => rnd() * 2 - 1;

/* ============================== 配乐 ============================== */
const CHORDS = [
  { root: 41, pad: [53, 57, 60, 65], arp: [65, 69, 72, 77] },   // F
  { root: 36, pad: [52, 55, 60, 64], arp: [64, 67, 72, 76] },   // C
  { root: 38, pad: [50, 57, 62, 65], arp: [62, 65, 69, 74] },   // Dm
  { root: 34, pad: [50, 53, 58, 62], arp: [62, 65, 70, 74] },   // B♭
];
const BARS = Math.ceil(DUR / BAR);
const chordAt = b => b >= 28 ? CHORDS[0] : CHORDS[b % 4];
const breakdown = t => t >= 40 && t < 46;
const drumsOn = t => t >= 8 && t < 56 && !breakdown(t);

// 铺底 Pad：三路失谐锯齿 + 双级低通
function pad(t0, notes, len, amp) {
  notes.forEach((m, ni) => {
    [-9, 0, 9].forEach((cents, vi) => {
      const f = mtof(m) * Math.pow(2, cents / 1200);
      let ph = rnd(), lp1 = 0, lp2 = 0, hpLp = 0;
      const rel = .9;
      put(duck, t0, len + rel, (i, tl) => {
        ph += f / SR; ph -= Math.floor(ph);
        const saw = 2 * ph - 1;
        const fc = 1400 + 500 * Math.sin(TAU * .15 * (t0 + tl));
        const a = 1 - Math.exp(-2 * Math.PI * fc / SR);
        lp1 += a * (saw - lp1); lp2 += a * (lp1 - lp2);
        hpLp += .025 * (lp2 - hpLp); // 约 180Hz 高通，给贝斯让位
        const env = Math.min(1, tl / .45) * (tl > len ? Math.max(0, 1 - (tl - len) / rel) : 1);
        return (lp2 - hpLp) * env * amp;
      }, { pan: [-.55, 0, .55][vi] * (ni % 2 ? -1 : 1), sendAmt: .35 });
    });
  });
}
// 拨弦琶音
function pluck(t0, m, amp, pan) {
  const f = mtof(m);
  put(duck, t0, .5, (i, tl) => {
    const e = Math.exp(-tl * 10) * Math.min(1, tl / .003);
    return (Math.sin(TAU * f * tl) + .35 * Math.sin(TAU * 2 * f * tl) * Math.exp(-tl * 12) + .12 * Math.sin(TAU * 3 * f * tl) * Math.exp(-tl * 20)) * e * amp;
  }, { pan, sendAmt: .5 });
}
// 贝斯：正弦次低音 + 低通锯齿
function bass(t0, m, len, amp) {
  const f = mtof(m);
  let ph = 0, lp = 0;
  put(duck, t0, len + .05, (i, tl) => {
    ph += f / SR; ph -= Math.floor(ph);
    lp += .045 * ((2 * ph - 1) - lp);
    const env = Math.min(1, tl / .006) * (.75 + .25 * Math.exp(-tl * 8)) * (tl > len ? Math.max(0, 1 - (tl - len) / .05) : 1);
    return (Math.sin(TAU * ph) * .8 + lp * .6) * env * amp;
  });
}
// 钟琴旋律
function bell(t0, m, amp, len = 1.6, pan = 0, sendAmt = .6, b = fx) {
  const f = mtof(m);
  const parts = [[1, 1, 2.2], [2, .38, 4], [3.01, .18, 6.5], [4.17, .09, 9], [5.43, .05, 12]];
  put(b, t0, len, (i, tl) => {
    let v = 0;
    for (const [r, a, d] of parts) v += Math.sin(TAU * f * r * tl) * a * Math.exp(-tl * d);
    return v * amp * Math.min(1, tl / .002);
  }, { pan, sendAmt });
}
// 鼓组
function kick(t0, amp) {
  let ph = 0;
  put(dry, t0, .45, (i, tl) => {
    const f = 45 + 110 * Math.exp(-tl * 28);
    ph += f / SR;
    return (Math.sin(TAU * ph) * Math.exp(-tl * 6.5) + noise() * .25 * Math.exp(-tl * 300)) * amp;
  });
}
function clap(t0, amp) {
  const f = svf();
  put(dry, t0, .35, (i, tl) => {
    const bursts = [0, .011, .022].reduce((s, o) => s + (tl >= o ? Math.exp(-(tl - o) * 220) : 0), 0);
    const env = bursts * .7 + Math.exp(-tl * 16) * .5;
    return f(noise(), 1500, 1.2).band * env * amp;
  }, { sendAmt: .5, pan: .05 });
}
function hat(t0, amp, open = false) {
  const f = svf();
  put(dry, t0, open ? .3 : .08, (i, tl) => f(noise(), 8000, .8).high * Math.exp(-tl * (open ? 14 : 70)) * amp, { pan: .25 });
}
function crash(t0, amp) {
  const f = svf();
  put(dry, t0, 2.6, (i, tl) => f(noise(), 6500, .6).high * Math.exp(-tl * 1.9) * amp * Math.min(1, tl / .004), { sendAmt: .4, pan: -.15 });
}
function riser(t0, len, amp) {
  const f = svf();
  put(dry, t0, len, (i, tl) => { const k = tl / len; return f(noise(), 300 + 6000 * k * k, 2.5).band * k * k * amp; }, { sendAmt: .5 });
}
function roll(t0, len, amp) {
  const steps = Math.round(len / .0625);
  for (let s = 0; s < steps; s++) {
    const k = s / steps, tt = t0 + s * .0625;
    const f = svf();
    put(dry, tt, .12, (i, tl) => f(noise(), 1800 + 1200 * k, 1).band * Math.exp(-tl * 35) * amp * (.25 + .75 * k), { sendAmt: .4 });
  }
}

// 旋律：四小节乐句，拍位/音高/时值
const PHRASE = [
  [[0, 81, 1], [1, 84, .5], [1.5, 81, .5], [2, 79, 1], [3, 77, 1]],
  [[0, 79, 1.5], [1.5, 76, .5], [2, 72, 2]],
  [[0, 74, 1], [1, 77, .5], [1.5, 81, .5], [2, 86, 1], [3, 84, 1]],
  [[0, 82, 1.5], [1.5, 81, .5], [2, 77, 1], [3, 79, 1]],
];
const melodyBars = new Map([[0, .12], [1, .13], [2, .14], [3, .14], [20, .17], [21, .17], [22, .18], [25, .14], [26, .14], [27, .14]]);

for (let b = 0; b < BARS; b++) {
  const t0 = b * BAR;
  if (t0 >= DUR) break;
  const ch = chordAt(b);
  const last = b >= 28;
  const intro = t0 < 4;
  // Pad
  pad(t0, ch.pad, last ? 1.2 : BAR, last ? .034 : intro ? .03 : breakdown(t0) ? .036 : .028);
  // 琶音
  if (!last) {
    const pat = [0, 2, 1, 3, 2, 1, 3, 2, 0, 2, 1, 3, 2, 3, 1, 2];
    for (let s = 0; s < 16; s++) {
      const tt = t0 + s * BAR / 16;
      const amp = (intro ? .024 + .012 * (tt / 4) : breakdown(tt) ? .042 : .03) * (s % 4 === 0 ? 1.25 : 1);
      pluck(tt, ch.arp[pat[s]], amp, s % 2 ? .35 : -.35);
    }
  }
  // 贝斯
  if (t0 >= 8 && !last) {
    if (breakdown(t0)) bass(t0, ch.root + 12, BAR - .05, .08);
    else for (let e = 0; e < 8; e++) bass(t0 + e * BEAT / 2, ch.root + 12 + (e % 4 === 3 ? 12 : 0), BEAT / 2 - .03, .1);
  }
  // 旋律
  if (melodyBars.has(b)) for (const [bt, m, len] of PHRASE[b % 4]) bell(t0 + bt * BEAT, m, melodyBars.get(b), Math.max(1.2, len * BEAT + 1), 0, .55, duck);
}
// 结尾和弦
pad(56, [41, 53, 57, 60, 65, 69], 1.2, .026);
[65, 72, 77, 81].forEach((m, i) => bell(56 + i * .04, m, .1, 2.2, (i - 1.5) * .3, .7));

// 鼓
const kicks = [];
for (let t = 4; t < 56; t += BEAT) {
  const beatInBar = Math.round((t % BAR) / BEAT);
  if (t < 8) { if (beatInBar % 2 === 0) { kick(t, .3); kicks.push(t); } continue; }
  if (breakdown(t)) continue;
  kick(t, .42); kicks.push(t);
  if (beatInBar % 2 === 1) clap(t, .32);
  hat(t + BEAT / 2, .09, (Math.round(t / BEAT) % 4) === 3);
  hat(t + BEAT / 4, .035); hat(t + BEAT * 3 / 4, .035);
}
for (let t = 40; t < 46; t += BEAT / 2) hat(t, .03);
kick(56, .45); kicks.push(56);
[8, 24, 34, 46, 50, 56].forEach(t => crash(t, t === 56 ? .16 : .12));
riser(6, 2, .07); roll(7, 1, .12);
riser(44.5, 1.5, .06); roll(45, 1, .1);
riser(48.5, 1.5, .05);

// 侧链：底鼓压住铺底
const duckGain = new Float32Array(N).fill(1);
for (const tk of kicks) {
  const s0 = Math.round(tk * SR), len = Math.round(.35 * SR);
  for (let i = 0; i < len && s0 + i < N; i++) duckGain[s0 + i] = Math.min(duckGain[s0 + i], 1 - .35 * Math.exp(-i / SR / .1));
}

/* ============================== 音效 ============================== */
function whoosh(t0, d, v = 1, up = false) {
  const f = svf();
  put(fx, t0, d, (i, tl) => {
    const k = tl / d;
    const env = Math.pow(Math.sin(Math.PI * Math.pow(k, up ? 1.4 : .7)), 2);
    const fc = up ? 300 + 3500 * k * k : 3200 * Math.pow(1 - k, 1.5) + 350;
    return f(noise(), fc + 1500 * env, 1.4).band * env * .45 * v;
  }, { sendAmt: .35, pan: 0 });
}
const SFX = {
  whoosh: c => whoosh(c.t, c.d || .6, c.v ?? 1, !!c.up),
  swish: c => whoosh(c.t, .35, .45, true),
  swipe: c => whoosh(c.t, .38, .6),
  sheet: c => { const f = svf(); put(fx, c.t, .35, (i, tl) => { const k = tl / .35; return f(noise(), 250 + 900 * k, 1).band * Math.sin(Math.PI * k) * .35; }, { sendAmt: .15 }); },
  sheetDown: c => { const f = svf(); put(fx, c.t, .3, (i, tl) => { const k = tl / .3; return f(noise(), 1100 - 800 * k, 1).band * Math.sin(Math.PI * k) * .28; }, { sendAmt: .15 }); },
  pop: c => {
    const f0 = c.f || 500; let ph = 0;
    put(fx, c.t, .22, (i, tl) => { const f = f0 * (1 + .9 * Math.exp(-tl * 45)); ph += f / SR; return (Math.sin(TAU * ph) + .25 * Math.sin(TAU * 2 * ph)) * Math.exp(-tl * 22) * Math.min(1, tl / .002) * .32 * (c.v ?? 1); }, { sendAmt: .25, pan: (rnd() - .5) * .5 });
  },
  bubble: c => {
    const f0 = c.f || 440; let ph = 0;
    put(fx, c.t, .35, (i, tl) => { const f = f0 * (.6 + .9 * (1 - Math.exp(-tl * 30))); ph += f / SR; return Math.sin(TAU * ph) * Math.exp(-tl * 11) * Math.min(1, tl / .003) * .4; }, { sendAmt: .35 });
  },
  tap: c => { const f = svf(); put(fx, c.t, .06, (i, tl) => (Math.sin(TAU * 2300 * tl) * .6 * Math.exp(-tl * 160) + f(noise(), 5000, .7).high * .4 * Math.exp(-tl * 260)) * .32); },
  type: c => { const fr = 1500 + rnd() * 500; const f = svf(); put(fx, c.t, .05, (i, tl) => (Math.sin(TAU * fr * tl) * Math.exp(-tl * 180) * .5 + f(noise(), 3500, .8).band * Math.exp(-tl * 240) * .5) * .26 * (c.v ?? 1), { pan: (rnd() - .5) * .4 }); },
  tick: c => put(fx, c.t, .12, (i, tl) => Math.sin(TAU * (c.f || 1500) * tl) * Math.exp(-tl * 45) * .16 * (c.v ?? 1), { sendAmt: .4, pan: (rnd() - .5) * .6 }),
  blip: c => put(fx, c.t, .18, (i, tl) => (Math.sin(TAU * (c.f || 900) * tl) + .3 * Math.sin(TAU * 2 * (c.f || 900) * tl)) * Math.exp(-tl * 22) * .2 * (c.v ?? 1), { sendAmt: .35 }),
  ding: c => { bell(c.t, 88, .22, 2.2, -.1, .5); bell(c.t + .14, 93, .2, 2.4, .1, .5); },
  success: c => [84, 88, 91].forEach((m, i) => bell(c.t + i * .075, m, .13, 1.2, (i - 1) * .3, .5)),
  sparkle: c => { for (let i = 0; i < 12; i++) { const fr = 2200 + rnd() * 4200, tt = c.t + rnd() * .7; put(fx, tt, .15, (j, tl) => Math.sin(TAU * fr * tl) * Math.exp(-tl * 30) * .07, { pan: rnd() * 1.6 - .8, sendAmt: .8 }); } },
  shimmer: c => [89, 93, 96, 101].forEach((m, i) => { const f = mtof(m); put(fx, c.t + i * .06, 1.6, (j, tl) => Math.sin(TAU * f * tl) * Math.min(1, tl / .25) * Math.exp(-tl * 2.4) * .035 * (1 + .3 * Math.sin(TAU * 7 * tl)), { pan: (i - 1.5) * .4, sendAmt: .9 }); }),
  swell: c => [65, 69, 72, 77, 81].forEach((m, i) => { const f = mtof(m); put(fx, c.t, 3, (j, tl) => Math.sin(TAU * f * tl + i) * Math.min(1, tl / 1.2) * Math.exp(-Math.max(0, tl - 1.2) * 1.6) * .03, { pan: (i - 2) * .35, sendAmt: .9 }); }),
  heart: c => { [89, 93, 96, 101].forEach((m, i) => bell(c.t + i * .09, m, .07, 1, (i - 1.5) * .4, .8)); kickSoft(c.t); kickSoft(c.t + .22); },
  stamp: c => { let ph = 0; const f = svf(); put(fx, c.t, .35, (i, tl) => { ph += (60 + 90 * Math.exp(-tl * 30)) / SR; return (Math.sin(TAU * ph) * Math.exp(-tl * 12) * .6 + f(noise(), 900, .7).low * Math.exp(-tl * 30) * .5) * .7; }, { sendAmt: .2 }); },
  impact: c => { let ph = 0; put(fx, c.t, 1.4, (i, tl) => { ph += (34 + 70 * Math.exp(-tl * 9)) / SR; return Math.sin(TAU * ph) * Math.exp(-tl * 3.2) * .7 * (c.v ?? 1); }); crash(c.t, .13); whoosh(c.t - .02, .5, .4); },
  data: c => { for (let i = 0; i < 14; i++) { const fr = 1100 + i * 90; put(fx, c.t + i * .08, .07, (j, tl) => Math.sin(TAU * fr * tl) * Math.exp(-tl * 50) * .08, { pan: -.6 + i * .09, sendAmt: .3 }); } },
};
function kickSoft(t0) { let ph = 0; put(fx, t0, .25, (i, tl) => { ph += (55 + 50 * Math.exp(-tl * 25)) / SR; return Math.sin(TAU * ph) * Math.exp(-tl * 14) * .3; }); }
for (const c of cues) (SFX[c.type] || (() => console.warn('未知音效', c.type)))(c);

/* ============================== 混响与母带 ============================== */
function freeverb(inL, inR) {
  const combT = [1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617], apT = [556, 441, 341, 225];
  const mk = (spread) => ({
    combs: combT.map(s => ({ buf: new Float32Array(s + spread), i: 0, store: 0 })),
    aps: apT.map(s => ({ buf: new Float32Array(s + spread), i: 0 })),
  });
  const run = (inp, st) => {
    const out = new Float32Array(N), fb = .86, damp = .25;
    for (let n = 0; n < N; n++) {
      const x = inp[n] * .015;
      let y = 0;
      for (const c of st.combs) {
        const o = c.buf[c.i]; c.store = o * (1 - damp) + c.store * damp;
        c.buf[c.i] = x + c.store * fb; if (++c.i >= c.buf.length) c.i = 0; y += o;
      }
      for (const a of st.aps) {
        const b = a.buf[a.i]; const o = -y + b; a.buf[a.i] = y + b * .5; if (++a.i >= a.buf.length) a.i = 0; y = o;
      }
      out[n] = y;
    }
    return out;
  };
  return [run(inL, mk(0)), run(inR, mk(23))];
}
const [revL, revR] = freeverb(send.L, send.R);
const L = new Float32Array(N), R = new Float32Array(N);
let peak = 0;
for (let n = 0; n < N; n++) {
  const t = n / SR;
  const fadeOut = t > DUR - 1.6 ? Math.max(0, (DUR - t) / 1.6) : 1;
  const fadeIn = Math.min(1, t / .02);
  const g = duckGain[n];
  L[n] = ((duck.L[n] * g + dry.L[n]) * .9 + fx.L[n] + revL[n] * 2.2) * fadeOut * fadeIn;
  R[n] = ((duck.R[n] * g + dry.R[n]) * .9 + fx.R[n] + revR[n] * 2.2) * fadeOut * fadeIn;
  peak = Math.max(peak, Math.abs(L[n]), Math.abs(R[n]));
}
const k = Math.tanh(1.2);
const pcm = Buffer.alloc(N * 4);
for (let n = 0; n < N; n++) {
  const l = Math.tanh(1.2 * L[n] / peak) / k * .92, r = Math.tanh(1.2 * R[n] / peak) / k * .92;
  pcm.writeInt16LE(Math.round(l * 32767), n * 4);
  pcm.writeInt16LE(Math.round(r * 32767), n * 4 + 2);
}
const hdr = Buffer.alloc(44);
hdr.write('RIFF', 0); hdr.writeUInt32LE(36 + pcm.length, 4); hdr.write('WAVE', 8); hdr.write('fmt ', 12);
hdr.writeUInt32LE(16, 16); hdr.writeUInt16LE(1, 20); hdr.writeUInt16LE(2, 22); hdr.writeUInt32LE(SR, 24);
hdr.writeUInt32LE(SR * 4, 28); hdr.writeUInt16LE(4, 32); hdr.writeUInt16LE(16, 34); hdr.write('data', 36); hdr.writeUInt32LE(pcm.length, 40);
fs.writeFileSync(path.join(OUT, 'audio.wav'), Buffer.concat([hdr, pcm]));
console.log(`已导出 out/audio.wav · ${DUR}s · ${cues.length} 个音效 · 原始峰值 ${peak.toFixed(2)}`);
