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
    texts = [a or b for a, b in re.findall(r'text="([^"]*)"|text=\'([^\']*)\'', ui) if (a or b).strip()]
    note('warning', title, ' | '.join(texts)[:3500] or '(empty)')

def show(path, title):
    try:
        note('warning', title, open(path).read() or '(none)')
    except FileNotFoundError:
        note('warning', title, '(missing)')

screen('ui.xml', 'SCREEN_VIDEO')
show('activity.txt', 'ACTIVITY')
show('media.txt', 'MEDIA_SESSION')
show('media_bg.txt', 'MEDIA_SESSION_BACKGROUND')
screen('ui_channel.xml', 'SCREEN_CHANNEL')
for label in ('A0', 'A', 'B'):
    show(f'orient_{label}.txt', f'ORIENTATION_{label}')
show('fs_tap.txt', 'FULLSCREEN_TAP')
orient = [l for l in log.splitlines() if 'LiteTube: orientation' in l]
note('warning', 'ORIENTATION_LOG', '\n'.join(orient[-40:]) + f'\n(total {len(orient)} changes)')

app = [l for l in log.splitlines()
       if re.search(r'LiteTube|litetube|ExoPlayer|MediaCodec.*rror|PlaybackService|Media3', l)
       and re.search(r' [EW] ', l)]
note('warning', 'APP_LOG', '\n'.join(app[-60:]) or '(none)')
