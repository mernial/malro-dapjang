const { chromium } = require('playwright'); const { spawn } = require('child_process');
(async () => {
  const b = await chromium.launch(); const p = await b.newPage({ viewport: { width: 1080, height: 1920 } });
  await p.goto('file://' + __dirname + '/promo.html'); await p.evaluate(() => document.fonts.ready);
  const ff = spawn('ffmpeg', ['-y','-loglevel','error','-f','image2pipe','-framerate','30','-i','-','-f','lavfi','-i','anullsrc=r=44100:cl=stereo',
    '-shortest','-c:v','libx264','-pix_fmt','yuv420p','-crf','18','-preset','medium','-r','30','-c:a','aac','-movflags','+faststart','promo.mp4'], { stdio: ['pipe','inherit','inherit'] });
  const N = 60 * 30;
  for (let i = 0; i < N; i++) {
    await p.evaluate(t => seek(t), i / 30);
    const buf = await p.screenshot({ type: 'jpeg', quality: 95 });
    if (!ff.stdin.write(buf)) await new Promise(r => ff.stdin.once('drain', r));
  }
  ff.stdin.end(); await new Promise(r => ff.on('close', r)); await b.close();
})();
