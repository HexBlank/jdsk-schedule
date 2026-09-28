// 逐帧导出宣传片：本地起一个静态服务 → Playwright 调 renderAt(t) 截图 → 管道喂给 ffmpeg。
//   node render.mjs stills 1.5 9.4 20        # 导出几张静帧到 out/stills 检查画面
//   node render.mjs cues                     # 只导出音效时间表 out/cues.json
//   node render.mjs video [--fps 60]         # 导出无声视频 out/video.mp4（多进程分段后拼接）
// 环境变量 FFMPEG 指定 ffmpeg 路径（需带 libx264）。
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { spawn, execSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const ROOT = path.dirname(fileURLToPath(import.meta.url));
const OUT = path.join(ROOT, 'out');
const FFMPEG = process.env.FFMPEG || 'ffmpeg';
fs.mkdirSync(OUT, { recursive: true });

async function loadPlaywright() {
  try { return await import('playwright'); } catch {}
  const req = createRequire(path.join(execSync('npm root -g').toString().trim(), 'noop.js'));
  return req('playwright');
}

const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.png': 'image/png', '.ttf': 'font/ttf', '.wav': 'audio/wav', '.json': 'application/json' };
function serve() {
  const server = http.createServer((req, res) => {
    const p = path.join(ROOT, decodeURIComponent(new URL(req.url, 'http://x').pathname));
    if (!p.startsWith(ROOT) || !fs.existsSync(p) || fs.statSync(p).isDirectory()) { res.writeHead(404); return res.end(); }
    res.writeHead(200, { 'Content-Type': MIME[path.extname(p)] || 'application/octet-stream' });
    fs.createReadStream(p).pipe(res);
  });
  return new Promise(r => server.listen(0, '127.0.0.1', () => r(server)));
}

async function openPage(browser, base) {
  const page = await browser.newPage({ viewport: { width: 1920, height: 1080 }, deviceScaleFactor: 1 });
  page.on('pageerror', e => console.error('[page error]', e.message));
  await page.goto(`${base}/index.html?render=1`);
  await page.evaluate(async () => {
    await document.fonts.load('900 40px PromoSans', '几点上课');
    await document.fonts.ready;
    await Promise.all([...document.images].map(i => i.complete ? 0 : new Promise(r => { i.onload = i.onerror = r; })));
  });
  return page;
}

const mode = process.argv[2] || 'video';
const argv = process.argv.slice(3);
const opt = (k, d) => { const i = argv.indexOf(k); return i >= 0 ? argv[i + 1] : d; };

const server = await serve();
const base = `http://127.0.0.1:${server.address().port}`;
const { chromium } = await loadPlaywright();
const browser = await chromium.launch({ args: ['--font-render-hinting=none', '--disable-lcd-text'] });

try {
  const page = await openPage(browser, base);
  const { duration, cues } = await page.evaluate(() => ({ duration: window.DURATION, cues: window.CUES }));
  fs.writeFileSync(path.join(OUT, 'cues.json'), JSON.stringify({ duration, cues }, null, 1));

  if (mode === 'stills') {
    fs.mkdirSync(path.join(OUT, 'stills'), { recursive: true });
    for (const t of argv.map(Number)) {
      await page.evaluate(t => window.renderAt(t), t);
      const f = path.join(OUT, 'stills', `t${t.toFixed(2).padStart(6, '0')}.png`);
      await page.screenshot({ path: f });
      console.log(f);
    }
  } else if (mode === 'video') {
    const fps = +opt('--fps', 60);
    const workers = +opt('--workers', 4);
    const total = Math.round(duration * fps);
    const per = Math.ceil(total / workers);
    const started = Date.now();
    let done = 0;
    const segs = [];
    await Promise.all([...Array(workers)].map(async (_, w) => {
      const from = w * per, to = Math.min(total, from + per);
      if (from >= to) return;
      const seg = path.join(OUT, `seg${w}.mp4`); segs[w] = seg;
      const p = w === 0 ? page : await openPage(browser, base);
      const ff = spawn(FFMPEG, ['-y', '-loglevel', 'error', '-f', 'image2pipe', '-framerate', String(fps), '-c:v', 'mjpeg', '-i', '-',
        '-c:v', 'libx264', '-preset', 'medium', '-crf', '14', '-pix_fmt', 'yuv420p', '-r', String(fps), seg], { stdio: ['pipe', 'inherit', 'inherit'] });
      for (let i = from; i < to; i++) {
        await p.evaluate(t => window.renderAt(t), i / fps);
        const buf = await p.screenshot({ type: 'jpeg', quality: 95 });
        if (!ff.stdin.write(buf)) await new Promise(r => ff.stdin.once('drain', r));
        if (++done % 120 === 0) console.log(`${done}/${total} 帧 · ${((Date.now() - started) / 1000).toFixed(0)}s`);
      }
      ff.stdin.end();
      await new Promise((res, rej) => ff.on('close', c => c ? rej(new Error('ffmpeg ' + c)) : res()));
    }));
    const list = path.join(OUT, 'segs.txt');
    fs.writeFileSync(list, segs.filter(Boolean).map(s => `file '${s}'`).join('\n'));
    execSync(`"${FFMPEG}" -y -loglevel error -f concat -safe 0 -i "${list}" -c copy "${path.join(OUT, 'video.mp4')}"`);
    segs.filter(Boolean).forEach(s => fs.unlinkSync(s)); fs.unlinkSync(list);
    console.log('已导出', path.join(OUT, 'video.mp4'));
  }
} finally {
  await browser.close();
  server.close();
}
