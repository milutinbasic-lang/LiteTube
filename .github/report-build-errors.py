"""On a failed build, surface compiler errors as an annotation (readable via the GitHub API)."""
import re

lines = open('build.log', errors='replace').read().splitlines()
picked = []
for i, line in enumerate(lines):
    if re.match(r'^(e: |ERROR:|> )', line) or 'What went wrong' in line or 'error:' in line:
        picked.extend(lines[i:i + 3])
text = '\n'.join(dict.fromkeys(picked))[:6000] or 'Build failed (no compiler errors found in log)'
print('::error title=BUILD::' + text.replace('%', '%25').replace('\r', '').replace('\n', '%0A'))
