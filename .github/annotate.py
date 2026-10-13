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

def screen(path, title):
    try:
        ui = open(path, errors='replace').read()
    except FileNotFoundError:
        ui = ''
    texts = [t for t in re.findall(r'text="([^"]+)"', ui) if t.strip()]
    note('warning', title, ' | '.join(texts)[:3500] or '(empty)')

def show(path, title):
    try:
        note('warning', title, open(path).read() or '(none)')
    except FileNotFoundError:
        note('warning', title, '(missing)')

screen('ui.xml', 'SCREEN_VIDEO')
try:
    raw = open('ui.xml', errors='replace').read()
    nodes = re.findall(r'<node [^>]*class="android.widget.(?:TextView|Button)"[^>]*>', raw)
    note('warning', 'SCREEN_VIDEO_NODES', '\n'.join(nodes)[:7000] or '(none)')
except FileNotFoundError:
    pass
show('activity.txt', 'ACTIVITY')
show('media.txt', 'MEDIA_SESSION')
show('media_bg.txt', 'MEDIA_SESSION_BACKGROUND')
screen('ui_channel.xml', 'SCREEN_CHANNEL')

app = [l for l in log.splitlines()
       if re.search(r'LiteTube|litetube|ExoPlayer|MediaCodec.*rror|PlaybackService|Media3', l)
       and re.search(r' [EW] ', l)]
note('warning', 'APP_LOG', '\n'.join(app[-60:]) or '(none)')
