"""Native lifecycle test; --protocol will also require installed audio packages."""
import ctypes
from ctypes import wintypes
import importlib.util
import json
import hashlib
import io
import os
import plistlib
from pathlib import Path
import subprocess
import socket
import struct
import sys
import tempfile
import time

RUNTIME = Path(__file__).resolve().parent.parent / '.runtime' / 'airplay2'
ENTRY = RUNTIME / 'receiver' / 'desktop_receiver.py'


def entry_module():
    spec = importlib.util.spec_from_file_location('desktop_receiver', ENTRY)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def wait_for(file, process):
    deadline = time.monotonic() + 10
    while not file.exists() and process.poll() is None and time.monotonic() < deadline:
        time.sleep(.05)
    if not file.exists():
        raise AssertionError(f'Worker did not become ready: exit={process.poll()}')


def check_job():
    with tempfile.TemporaryDirectory(prefix='airplay2-job-check-') as directory:
        marker = Path(directory) / 'worker.json'
        process = subprocess.Popen([str(RUNTIME / 'python.exe'), __file__, '--job-worker', str(marker)],
                                   creationflags=subprocess.CREATE_NO_WINDOW)
        child = None
        kernel = ctypes.WinDLL('kernel32', use_last_error=True)
        kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
        kernel.OpenProcess.restype = wintypes.HANDLE
        kernel.WaitForSingleObject.argtypes = [wintypes.HANDLE, wintypes.DWORD]
        kernel.CloseHandle.argtypes = [wintypes.HANDLE]
        try:
            wait_for(marker, process)
            child = kernel.OpenProcess(0x00100001, False, json.loads(marker.read_text())['child'])
            if not child:
                raise ctypes.WinError(ctypes.get_last_error())
            process.kill()
            process.wait(timeout=5)
            if kernel.WaitForSingleObject(child, 5000) != 0:
                raise AssertionError('Audio descendant survived owner termination')
            print('AIRPLAY2_WINDOWS_JOB_CLEANUP_PASSED')
        finally:
            if process.poll() is None:
                process.kill()
                process.wait(timeout=5)
            if child:
                if kernel.WaitForSingleObject(child, 0) == 258:
                    kernel.TerminateProcess.argtypes = [wintypes.HANDLE, wintypes.UINT]
                    kernel.TerminateProcess(child, 1)
                kernel.CloseHandle(child)


def exact(connection, size):
    result = b''
    while len(result) < size:
        block = connection.recv(size - len(result))
        if not block:
            raise EOFError('Peer closed before the complete response')
        result += block
    return result


class Client:
    def __init__(self, port):
        self.socket = socket.create_connection(('127.0.0.1', port), timeout=8)
        self.outgoing = self.incoming = None
        self.out_count = self.in_count = self.sequence = 0
        self.buffer = b''

    def request(self, method, path, body=b'', content_type='application/x-apple-binary-plist'):
        from Crypto.Cipher import ChaCha20_Poly1305
        self.sequence += 1
        type_header = f'Content-Type: {content_type}\r\n' if method != 'GET' else ''
        data = (f'{method} {path} RTSP/1.0\r\nCSeq: {self.sequence}\r\n{type_header}'
                f'Content-Length: {len(body)}\r\n\r\n').encode() + body
        if self.outgoing is None:
            self.socket.sendall(data)
        else:
            for position in range(0, len(data), 1024):
                block = data[position:position + 1024]
                length = struct.pack('<H', len(block))
                cipher = ChaCha20_Poly1305.new(key=self.outgoing, nonce=b'\0' * 4 + struct.pack('<Q', self.out_count))
                cipher.update(length)
                encrypted, tag = cipher.encrypt_and_digest(block)
                # Exercise a fragmented two-byte HAP length field.
                self.socket.sendall(length[:1]); time.sleep(.002)
                self.socket.sendall(length[1:] + encrypted + tag)
                self.out_count += 1

        def receive():
            if self.incoming is None:
                return self.socket.recv(8192)
            length = exact(self.socket, 2)
            frame = exact(self.socket, int.from_bytes(length, 'little') + 16)
            cipher = ChaCha20_Poly1305.new(key=self.incoming, nonce=b'\0' * 4 + struct.pack('<Q', self.in_count))
            cipher.update(length)
            self.in_count += 1
            return cipher.decrypt_and_verify(frame[:-16], frame[-16:])

        while b'\r\n\r\n' not in self.buffer:
            self.buffer += receive()
        header, self.buffer = self.buffer.split(b'\r\n\r\n', 1)
        lines = header.decode().split('\r\n')
        if ' 200 ' not in lines[0]:
            raise AssertionError(lines[0])
        headers = dict(line.lower().split(': ', 1) for line in lines[1:])
        size = int(headers.get('content-length', '0'))
        while len(self.buffer) < size:
            self.buffer += receive()
        result, self.buffer = self.buffer[:size], self.buffer[size:]
        return result

    def pair(self):
        from ap2.pairing.hap import Tlv8
        from ap2.pairing.srp import N, g
        import hkdf
        start = Tlv8.encode([6, b'\x01', 0, b'\x00', 19, b'\x10'])
        response = Tlv8.decode(self.request('POST', '/pair-setup', start, 'application/pairing+tlv8'))
        assert response[6] == b'\x02'
        salt = response[2]
        B = int.from_bytes(response[3], 'big')
        number = lambda value: value.to_bytes(max(1, (value.bit_length() + 7) // 8), 'big')
        digest = lambda *parts: int.from_bytes(hashlib.sha512(b''.join(number(p) if isinstance(p, int) else p for p in parts)).digest(), 'big')
        pad = lambda value: value.to_bytes(384, 'big')
        a = int.from_bytes(os.urandom(64), 'big')
        A = pow(g, a, N)
        k = digest(pad(N), pad(g))
        x = digest(salt, digest(b'Pair-Setup:3939'))
        u = digest(pad(A), pad(B))
        S = pow((B - k * pow(g, x, N)) % N, a + u * x, N)
        K = digest(S)
        proof = digest(digest(N) ^ digest(g), digest(b'Pair-Setup'), salt, A, B, K)
        verify = Tlv8.encode([6, b'\x03', 3, number(A), 4, number(proof)])
        response = Tlv8.decode(self.request('POST', '/pair-setup', verify, 'application/pairing+tlv8'))
        assert response[6] == b'\x04'
        assert int.from_bytes(response[4], 'big') == digest(A, proof, K)
        prk = hkdf.hkdf_extract(b'Control-Salt', number(K))
        self.outgoing = hkdf.hkdf_expand(prk, b'Control-Write-Encryption-Key', 32)
        self.incoming = hkdf.hkdf_expand(prk, b'Control-Read-Encryption-Key', 32)


def check_protocol():
    with tempfile.TemporaryDirectory(prefix='airplay2-protocol-check-') as directory:
        directory = Path(directory)
        with socket.socket() as port_socket:
            port_socket.bind(('127.0.0.1', 0))
            port = port_socket.getsockname()[1]
        status, stop = directory / 'status.properties', directory / 'stop'
        process = subprocess.Popen([str(RUNTIME / 'python.exe'), str(ENTRY), '--parent', str(os.getpid()),
            '--address', '127.0.0.1', '--name', 'AirPlay Test Music', '--port', str(port),
            '--state', str(directory), '--status', str(status), '--stop', str(stop), '--no-mdns'],
            creationflags=subprocess.CREATE_NO_WINDOW)
        client = None
        try:
            wait_for(status, process)
            client = Client(port)
            info = plistlib.loads(client.request('GET', '/info'))
            assert info['protocolVersion'] == '1.1'
            assert info['features'] & (1 << 48) and info['features'] & (1 << 40)
            client.pair()
            verified = plistlib.loads(client.request('GET', '/info'))
            assert info['deviceID'] == verified['deviceID']
            client.request('SETUP', '/stream', plistlib.dumps({'timingProtocol': 'PTP'}, fmt=plistlib.FMT_BINARY))
            # Both realtime and buffered stream negotiation must create actual
            # Windows workers, not merely advertise a feature bit.
            for kind in (96, 103):
                stream = {'type': kind, 'audioFormat': 1 << 11, 'ct': 1, 'spf': 352,
                          'shk': b'\x12' * 32, 'latencyMin': 11025, 'latencyMax': 88200}
                result = plistlib.loads(client.request('SETUP', '/stream',
                    plistlib.dumps({'streams': [stream]}, fmt=plistlib.FMT_BINARY)))
                descriptor = next(s for s in result['streams'] if s['type'] == kind)
                assert descriptor['dataPort'] > 0 and descriptor['controlPort'] > 0
                time.sleep(.5)
                client.request('TEARDOWN', '/stream', plistlib.dumps({'streams': [descriptor]}, fmt=plistlib.FMT_BINARY))
            print('AIRPLAY2_SRP_ENCRYPTED_CONTROL_AND_STREAM_NEGOTIATION_PASSED')
        finally:
            if client:
                client.socket.close()
            stop.touch()
            try:
                process.wait(timeout=8)
            except subprocess.TimeoutExpired:
                process.kill(); process.wait(timeout=3)
            if process.returncode != 0:
                print((directory / 'receiver.log').read_text(encoding='utf-8', errors='replace'))
                raise AssertionError(f'Protocol receiver exit: {process.returncode}')
            log = (directory / 'receiver.log').read_text(encoding='utf-8', errors='replace')
            if 'Traceback (most recent call last)' in log:
                raise AssertionError(log)


def check_decoding():
    import av
    from ap2.connections.audio import Audio, AirplayAudFmt, RTP_REALTIME
    from ap2.desktop_audio import set_volume
    from Crypto.Cipher import ChaCha20_Poly1305
    previous = Path.cwd()
    with tempfile.TemporaryDirectory(prefix='airplay2-decode-check-') as directory:
        os.chdir(directory)
        try:
            set_volume(0)
            for codec, audio_format in [('aac', AirplayAudFmt.AAC_LC_44100_2), ('alac', AirplayAudFmt.ALAC_44100_16_2)]:
                encoder = av.CodecContext.create(codec, 'w')
                encoder.sample_rate = 44100
                encoder.layout = 'stereo'
                encoder.format = 'fltp' if codec == 'aac' else 's16p'
                encoder.open()
                samples = encoder.frame_size
                frame = av.AudioFrame(format=encoder.format.name, layout='stereo', samples=samples)
                frame.sample_rate = 44100
                for plane in frame.planes:
                    plane.update(bytes(plane.buffer_size))
                packets = list(encoder.encode(frame)) + list(encoder.encode(None))
                class Parameters:
                    def get_extra_data(self): return encoder.extradata
                receiver = Audio(None, b'\x42' * 32, audio_format=audio_format.value,
                                 aud_params=Parameters(), spf=samples)
                receiver.init_audio_sink()
                try:
                    decoded = b''
                    for sequence, packet in enumerate(packets):
                        header = struct.pack('!BBHII', 0x80, 96, sequence, sequence * samples, 1)
                        nonce = sequence.to_bytes(8, 'little')
                        cipher = ChaCha20_Poly1305.new(key=b'\x42' * 32, nonce=nonce)
                        cipher.update(header[4:12])
                        payload, tag = cipher.encrypt_and_digest(bytes(packet))
                        decoded += receiver.process(RTP_REALTIME(header + payload + tag + nonce)) or b''
                    assert len(decoded) >= samples * 2 * 2, (codec, len(decoded), samples)
                    assert not any(decoded), 'Silence fixture must remain silence'
                    receiver.sink.write(decoded[:2048])
                finally:
                    receiver.sink.close(); receiver.pa.terminate()
                print(f'AIRPLAY2_{codec.upper()}_DECRYPT_DECODE_AND_WINDOWS_OUTPUT_PASSED')
        finally:
            import logging
            logging.shutdown()
            os.chdir(previous)


if __name__ == '__main__':
    if len(sys.argv) > 1 and sys.argv[1] == '--job-worker':
        import os
        guard = entry_module().guard_process_tree(os.getppid())
        child = subprocess.Popen([str(RUNTIME / 'python.exe'), '-c', 'import time;time.sleep(60)'],
                                 creationflags=subprocess.CREATE_NO_WINDOW)
        Path(sys.argv[2]).write_text(json.dumps({'child': child.pid}))
        time.sleep(60)
    else:
        check_job()
        if '--protocol' in sys.argv:
            check_protocol()
            check_decoding()
