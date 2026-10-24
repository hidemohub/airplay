"""Windows audio sidecar for airplay; protocol implementation: openairplay.

Upstream revision: 6c343d3679ddb561c61566985acaaf587d0a3bd3.
This experimental receiver does not implement accurate multiroom clock sync.
"""
import argparse
import ctypes
from ctypes import wintypes
import hashlib
import importlib.util
import json
import logging
import multiprocessing
import os
from pathlib import Path
import socket
import sys
import threading
import time
import uuid


def guard_process_tree(parent_pid):
    """Keep every spawned audio/control worker in a kill-on-close Windows job."""
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    kernel.CreateJobObjectW.argtypes = [ctypes.c_void_p, wintypes.LPCWSTR]
    kernel.CreateJobObjectW.restype = wintypes.HANDLE
    kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
    kernel.OpenProcess.restype = wintypes.HANDLE
    kernel.GetCurrentProcess.restype = wintypes.HANDLE
    kernel.SetInformationJobObject.argtypes = [wintypes.HANDLE, ctypes.c_int, ctypes.c_void_p, wintypes.DWORD]
    kernel.AssignProcessToJobObject.argtypes = [wintypes.HANDLE, wintypes.HANDLE]
    kernel.WaitForSingleObject.argtypes = [wintypes.HANDLE, wintypes.DWORD]
    kernel.CloseHandle.argtypes = [wintypes.HANDLE]

    class Basic(ctypes.Structure):
        _fields_ = [('process_time', ctypes.c_int64), ('job_time', ctypes.c_int64),
                    ('flags', wintypes.DWORD), ('min_working', ctypes.c_size_t),
                    ('max_working', ctypes.c_size_t), ('active', wintypes.DWORD),
                    ('affinity', ctypes.c_size_t), ('priority', wintypes.DWORD),
                    ('scheduling', wintypes.DWORD)]

    class Io(ctypes.Structure):
        _fields_ = [(name, ctypes.c_uint64) for name in
                    ('read_ops', 'write_ops', 'other_ops', 'read_bytes', 'write_bytes', 'other_bytes')]

    class Extended(ctypes.Structure):
        _fields_ = [('basic', Basic), ('io', Io), ('process_memory', ctypes.c_size_t),
                    ('job_memory', ctypes.c_size_t), ('peak_process', ctypes.c_size_t),
                    ('peak_job', ctypes.c_size_t)]

    parent = kernel.OpenProcess(0x00100000, False, parent_pid)
    if not parent:
        raise ctypes.WinError(ctypes.get_last_error())
    job = kernel.CreateJobObjectW(None, None)
    limits = Extended()
    limits.basic.flags = 0x2000  # JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
    if not job or not kernel.SetInformationJobObject(job, 9, ctypes.byref(limits), ctypes.sizeof(limits)):
        raise ctypes.WinError(ctypes.get_last_error())
    if not kernel.AssignProcessToJobObject(job, kernel.GetCurrentProcess()):
        raise ctypes.WinError(ctypes.get_last_error())
    # Retain these handles until process exit; closing the job also kills us.
    return kernel, job, parent


def load_engine():
    spec = importlib.util.spec_from_file_location('desktop_ap2_engine', Path(__file__).with_name('ap2-receiver.py'))
    engine = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = engine
    spec.loader.exec_module(engine)
    return engine


def configure_engine(engine, address, name, state_dir):
    identity_path = state_dir / 'identity.json'
    if not identity_path.exists():
        identity_path.write_text(json.dumps({'id': str(uuid.uuid4())}), encoding='utf-8')
    identity = json.loads(identity_path.read_text(encoding='utf-8'))['id']
    raw = bytearray(hashlib.sha256(identity.encode()).digest()[:6])
    raw[0] = (raw[0] | 2) & 0xfe
    engine.PI = identity.encode()
    engine.DEVICE_ID_BIN = bytes(raw)
    engine.DEVICE_ID = ':'.join(f'{value:02X}' for value in raw)
    engine.IPV4 = engine.IPADDR = address
    engine.IPV6 = None
    engine.IP4ADDR_BIN = engine.IPADDR_BIN = socket.inet_aton(address)
    engine.DEBUG = False
    engine.DISABLE_VM = False
    # Do not claim to be a PTP master: upstream has no accurate PTP clock.
    engine.DISABLE_PTP_MASTER = True
    engine.args = argparse.Namespace(mdns=name)
    engine.SCR_LOG = engine.get_screen_logger('Receiver', level='INFO')
    engine.DEV_PROPS = engine.DeviceProperties(engine.PI, False)
    engine.DEV_PROPS.setDeviceName(name)
    engine.DEV_NAME = name
    engine.setup_global_structs(engine.args)
    from ap2.desktop_audio import get_volume, set_volume
    engine.get_volume = get_volume
    engine.set_volume = set_volume
    # Group and pairing identifiers must be unique and stable per installation.
    engine.mdns_props['gid'] = identity
    engine.device_info['name'] = name


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--parent', type=int, required=True)
    parser.add_argument('--address', required=True)
    parser.add_argument('--name', required=True)
    parser.add_argument('--port', type=int, default=7001)
    parser.add_argument('--state', type=Path, required=True)
    parser.add_argument('--status', type=Path, required=True)
    parser.add_argument('--stop', type=Path, required=True)
    parser.add_argument('--no-mdns', action='store_true', help='Local protocol tests only')
    options = parser.parse_args()
    options.state.mkdir(parents=True, exist_ok=True)
    output = open(options.state / 'receiver.log', 'w', encoding='utf-8', buffering=1)
    sys.stdout = sys.stderr = output
    os.chdir(options.state)
    (options.state / 'pairings').mkdir(exist_ok=True)
    guard = guard_process_tree(options.parent)
    engine = load_engine()
    configure_engine(engine, options.address, options.name, options.state)
    # During Save and Restart, the old JVM releases its audio port shortly after
    # the new JVM signals readiness. Allow that handover without stealing a port.
    for attempt in range(41):
        if options.stop.exists():
            return
        try:
            server = engine.AP2Server((options.address, options.port), engine.AP2Handler)
            break
        except OSError as error:
            if error.winerror != 10048 or attempt == 40:
                raise
            time.sleep(.2)
    server.daemon_threads = True
    server.block_on_close = False
    # Upstream server_close never closes its listening socket. Use TCPServer's
    # implementation; shutdown is called from this thread after serving starts.
    import socketserver
    server.server_close = lambda: socketserver.TCPServer.server_close(server)
    zc = None
    thread = threading.Thread(target=server.serve_forever, kwargs={'poll_interval': 0.2}, daemon=True)
    try:
        if not options.no_mdns:
            from zeroconf import Zeroconf, ServiceInfo, IPVersion
            zc = Zeroconf(interfaces=[options.address], ip_version=IPVersion.V4Only)
            info = ServiceInfo('_airplay._tcp.local.', f'{options.name}._airplay._tcp.local.',
                               addresses=[socket.inet_aton(options.address)], port=options.port,
                               properties=engine.mdns_props,
                               server=f'ap2-{engine.DEVICE_ID_BIN.hex()}.local.')
            zc.register_service(info)
            engine.MDNS_OBJ = (zc, info)
            def update_mdns(mac, receiver_name, addresses):
                # Reuse the selected adapter, port and existing Zeroconf object.
                # Upstream's helper creates a new object on every status change.
                updated = ServiceInfo('_airplay._tcp.local.', f'{options.name}._airplay._tcp.local.',
                    addresses=[socket.inet_aton(options.address)], port=options.port,
                    properties=engine.mdns_props, server=f'ap2-{engine.DEVICE_ID_BIN.hex()}.local.')
                zc.update_service(updated)
                return (zc, updated)
            engine.register_mdns = update_mdns
        thread.start()
        status = f'state=ready\npid={os.getpid()}\nport={options.port}\naddress={options.address}\nname={options.name}\nexperimental=true\npreciseSync=false\n'
        temporary = options.status.with_suffix('.tmp')
        temporary.write_text(status, encoding='utf-8')
        temporary.replace(options.status)
        print(status, flush=True)
        while not options.stop.exists():
            if guard[0].WaitForSingleObject(guard[2], 500) != 258:
                break
    finally:
        options.status.unlink(missing_ok=True)
        if thread.is_alive():
            server.shutdown()
        for connection in list(server.connections.values()):
            try:
                connection.close()
            except Exception:
                pass
        server.server_close()
        if zc:
            zc.unregister_all_services()
            zc.close()
        # Includes audio, RTCP and event subprocesses, even if a sender vanished.
        for child in multiprocessing.active_children():
            child.terminate()
        for child in multiprocessing.active_children():
            child.join(timeout=2)
        output.flush()


if __name__ == '__main__':
    multiprocessing.freeze_support()
    multiprocessing.set_start_method('spawn')
    try:
        main()
    except BaseException:
        import traceback
        traceback.print_exc()
        sys.stderr.flush()
        os._exit(1)
    # Avoid waiting indefinitely for upstream worker threads at interpreter exit.
    os._exit(0)
