# AirPlay 2 audio backend

Protocol source: https://github.com/openairplay/airplay2-receiver

Revision: `6c343d3679ddb561c61566985acaaf587d0a3bd3`.
The original checkout is retained at the workspace's `airplay2-audio` directory.
The supplied `airplay2-win-master` was examined but does not provide this audio protocol.

Desktop changes: `desktop_receiver.py` owns interface selection, a stable separate
identity, the music service port, Windows process lifetime, Bonjour cleanup and
restart handover. It keeps pairing data in the user's `.java-airplay/airplay2`
directory. `ap2/desktop_audio.py` implements application-specific PCM volume.
Audio decoding is adapted to PyAV 16, signed 16-bit output and valid PCM frame
lengths. HAP framing handles fragmented length fields and peer disconnects.
File logs rotate and reuse handlers. The event reader no longer writes a debug
file when debug logging is disabled.

This is an experimental audio receiver. Accurate multiroom synchronization is
not implemented by the upstream project and is not added by these changes.
The service does not claim to be a PTP master. The Java mirror receiver remains
separate, with its existing H.264/H.265 decoder and window controls.

Runtime: official Python 3.12.10 Windows x64 embedded distribution, retaining its
PSF license and original signed binaries. Original archive SHA-256:
`4acbed6dd1c744b0376e3b1cf57ce906f9dc9e95e68824584c8099a63025a3c3`.
Exact third-party package versions are retained in `maintenance/airplay2-requirements.lock`.
Package licenses and notices remain in `site-packages` distribution metadata.
