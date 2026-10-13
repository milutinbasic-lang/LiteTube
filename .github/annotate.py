"""Turns emulator output into ::warning/::error annotations."""
import re

def esc(s):
    return s.replace('%', '%25').replace('\r', '').replace('\n', '%0A')

def note(kind, title, text):
    for i in range(0, max(len(text), 1), 3500):
        print(f"::{kind} title={title}::{esc(text[i:i + 3500])}", flush=True)

log = open('logcat.txt', errors='replace').read()

crashes = re.findall(r'(?m)^.*FATAL EXCEPTION.*\n(?:.*(?:AndroidRuntime|at |Caused by).*\n?){0,60}', log)
for c in crashes[:3]:
    note('error', 'CRASH', c)
if not crashes:
    note('notice', 'CRASH', 'no crash')

ui = open('ui.xml', errors='replace').read() if True else ''
texts = [t for t in re.findall(r'text="([^"]+)"', ui) if t.strip()]
note('warning', 'SCREEN', ' | '.join(texts)[:3500] or '(empty)')
note('warning', 'ACTIVITY', open('activity.txt').read() or '(none)')
note('warning', 'MEDIA_SESSION', open('media.txt').read() or '(none)')

app = [l for l in log.splitlines()
       if re.search(r'LiteTube|litetube|ExoPlayer|MediaCodec.*rror|PlaybackService|Media3', l)
       and re.search(r' [EW] ', l)]
note('warning', 'APP_LOG', '\n'.join(app[-60:]) or '(none)')
