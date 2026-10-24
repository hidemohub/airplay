"""Per-receiver PCM volume; does not modify the Windows master volume."""
import array
import json
import math
import os
from pathlib import Path
import tempfile
import time

_next_read = 0.0
_volume = -15.0


def get_volume():
    global _volume, _next_read
    now = time.monotonic()
    if now >= _next_read:
        _next_read = now + .1
        try:
            value = float(json.loads(Path('volume.json').read_text())['db'])
            if math.isfinite(value):
                _volume = max(-144.0, min(0.0, value))
        except (OSError, ValueError, KeyError, TypeError):
            pass
    return _volume


def set_volume(value):
    global _volume, _next_read
    value = float(value)
    if not math.isfinite(value):
        raise ValueError('Volume must be finite')
    _volume = max(-144.0, min(0.0, value))
    _next_read = 0.0
    fd, name = tempfile.mkstemp(prefix='volume-', suffix='.tmp', dir='.')
    try:
        with os.fdopen(fd, 'w') as output:
            json.dump({'db': _volume}, output)
        os.replace(name, 'volume.json')
    finally:
        Path(name).unlink(missing_ok=True)


def apply_volume(pcm):
    volume = get_volume()
    if volume <= -144:
        return bytes(len(pcm))
    if volume == 0:
        return pcm
    gain = 10 ** (volume / 20)
    samples = array.array('h')
    samples.frombytes(pcm)
    return array.array('h', (round(sample * gain) for sample in samples)).tobytes()
