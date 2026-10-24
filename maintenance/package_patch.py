"""Extract build dependencies or package compiled repairs, preserving nested JAR metadata."""
import pathlib
import shutil
import sys
import zipfile
import io
import struct
import re

ROOT = pathlib.Path(__file__).resolve().parent.parent
JAR = ROOT / 'airplay-server-1.1.2.jar'
BACKUP = ROOT / 'maintenance/java-airplay-server-1.1.2.original.jar'
BUILD = ROOT / 'maintenance/inspect'


def brand(data, name):
    if name.endswith('.jar'):
        result = io.BytesIO()
        with zipfile.ZipFile(io.BytesIO(data)) as source, zipfile.ZipFile(result, 'w') as output:
            for info in source.infolist():
                output.writestr(info, brand(source.read(info.filename), info.filename))
        return result.getvalue()
    if name.endswith('.class') and 'wtf/nanoka/airplay/' in name:
        count = struct.unpack_from('>H', data, 8)[0]
        out = bytearray(data[:10]); pos = 10; index = 1
        sizes = {3:4,4:4,5:8,6:8,7:2,8:2,9:4,10:4,11:4,12:4,15:3,16:2,17:4,18:4,19:2,20:2}
        while index < count:
            tag = data[pos]; out.append(tag); pos += 1
            if tag == 1:
                length = struct.unpack_from('>H', data, pos)[0]; pos += 2
                value = data[pos:pos+length]; pos += length
                value = re.sub(b'Java [Aa]ir[Pp]lay', b'airplay', value)
                out.extend(struct.pack('>H', len(value))); out.extend(value)
            else:
                size = sizes[tag]; out.extend(data[pos:pos+size]); pos += size
                if tag in (5,6): index += 1
            index += 1
        out.extend(data[pos:]); return bytes(out)
    if name.endswith(('.properties', '.txt', '.xml')):
        data = re.sub(b'Java [Aa]ir[Pp]lay', b'airplay', data)
    return data

if sys.argv[1] == 'extract':
    if not BACKUP.exists():
        shutil.copy2(JAR, BACKUP)
    with zipfile.ZipFile(BACKUP) as source:
        for name in source.namelist():
            if name.startswith('BOOT-INF/classes/') and not name.endswith('/'):
                target = BUILD / name.removeprefix('BOOT-INF/classes/')
            elif name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
                target = BUILD / 'libs' / name.split('/')[-1]
            else:
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(source.read(name))
    with zipfile.ZipFile(BUILD / 'libs/gstreamer-1.1.2.jar') as source:
        for relative in ('ui/VisionPlayerWindow.class', 'ui/DetachedVideoWindow.class', 'GstPlayerSwing.class'):
            name = 'wtf/nanoka/airplay/player/gstreamer/' + relative
            target = BUILD / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(source.read(name))
    with zipfile.ZipFile(BUILD / 'libs/server-1.1.2.jar') as source:
        name = 'wtf/nanoka/airplay/server/internal/handler/video/VideoCodecConfiguration.class'
        target = BUILD / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(source.read(name))
elif sys.argv[1] == 'package':
    changes = {'BOOT-INF/classes/application.properties': (ROOT / 'config/application.properties').read_bytes()}
    for file in (BUILD / 'wtf/nanoka/airplay/app').glob('DesktopLifecycle*.class'):
        changes['BOOT-INF/classes/wtf/nanoka/airplay/app/' + file.name] = file.read_bytes()
    for file in (BUILD / 'wtf/nanoka/airplay/app').glob('AirPlay2Audio*.class'):
        changes['BOOT-INF/classes/wtf/nanoka/airplay/app/' + file.name] = file.read_bytes()
    config = 'wtf/nanoka/airplay/app/config/UserSettingsPropertySource.class'
    changes['BOOT-INF/classes/' + config] = (BUILD / config).read_bytes()
    tray = 'wtf/nanoka/airplay/app/menu/SystemTrayMenu.class'
    changes['BOOT-INF/classes/' + tray] = (BUILD / tray).read_bytes()
    ui = 'wtf/nanoka/airplay/player/gstreamer/ui/'
    for file in (BUILD / ui).glob('WindowPinControls*.class'):
        changes['BOOT-INF/classes/' + ui + file.name] = file.read_bytes()
    for file in (BUILD / ui).glob('StreamTelemetry*.class'):
        changes['BOOT-INF/classes/' + ui + file.name] = file.read_bytes()
    video = 'wtf/nanoka/airplay/server/internal/handler/video/'
    for file in (BUILD / video).glob('CodecConfigurationSupport*.class'):
        changes['BOOT-INF/classes/' + video + file.name] = file.read_bytes()
    nested = io.BytesIO()
    with zipfile.ZipFile(BUILD / 'libs/gstreamer-1.1.2.jar') as source, zipfile.ZipFile(nested, 'w') as output:
        for info in source.infolist():
            if info.filename in (ui + 'VisionPlayerWindow.class', ui + 'DetachedVideoWindow.class', 'wtf/nanoka/airplay/player/gstreamer/GstPlayerSwing.class'):
                data = (BUILD / info.filename).read_bytes()
            else:
                data = source.read(info.filename)
            if info.filename in (ui + 'messages.properties', ui + 'messages_zh.properties'):
                chinese = info.filename.endswith('messages_zh.properties')
                labels = {
                    'airplay2.title': 'AirPlay 2 音乐' if chinese else 'AirPlay 2 music',
                    'airplay2.description': '实验性音乐接收；在音乐输出列表选择带 Music 的设备，不支持精确多设备同步' if chinese else 'Experimental audio receiver; choose the Music device in audio output. Accurate multiroom sync is unavailable.',
                    'airplay2.enabled': '启用音乐接收' if chinese else 'Enable music receiver',
                    'airplay2.state.stopped': '未启动' if chinese else 'Stopped',
                    'airplay2.state.disabled': '已关闭' if chinese else 'Disabled',
                    'airplay2.state.starting': '正在启动' if chinese else 'Starting',
                    'airplay2.state.ready': '可接收音乐' if chinese else 'Ready for music',
                    'airplay2.state.error': '音乐接收启动失败，移入查看原因' if chinese else 'Music receiver failed; hover for details',
                    'desktop.close.title': '\u5173\u95ed\u7a97\u53e3\u65f6' if chinese else 'When closing the window',
                    'desktop.close.description': '\u7acb\u5373\u4fdd\u5b58\uff1b\u6258\u76d8\u4e0d\u53ef\u7528\u65f6\u9000\u51fa\u7a0b\u5e8f' if chinese else 'Saved immediately; exits if the system tray is unavailable',
                    'desktop.close.exit': '\u9000\u51fa\u7a0b\u5e8f' if chinese else 'Exit application',
                    'desktop.close.tray': '\u9690\u85cf\u5230\u6258\u76d8' if chinese else 'Hide to system tray',
                    'stream.resolution.pending': '\u5206\u8fa8\u7387\u68c0\u6d4b\u4e2d' if chinese else 'Detecting resolution',
                    'stream.codec.pending': '\u7f16\u7801\u68c0\u6d4b\u4e2d' if chinese else 'Detecting codec',
                    'stream.fps.pending': '\u5e27\u7387\u68c0\u6d4b\u4e2d' if chinese else 'Measuring FPS',
                    'stream.decoded': '\u89e3\u7801\u786e\u8ba4' if chinese else 'Decoder verified',
                    'stream.parameters': '\u7801\u6d41\u53c2\u6570' if chinese else 'Stream parameters',
                    'stream.hevc.notSelected': '\u5df2\u5141\u8bb8 H.265\uff0c\u53d1\u9001\u7aef\u9009\u62e9 H.264' if chinese else 'H.265 allowed; sender chose H.264',
                    'settings.hevc.description': '\u5141\u8bb8\u53d1\u9001\u7aef\u534f\u5546 H.265\uff1b\u5b9e\u9645\u7f16\u7801\u53d6\u51b3\u4e8e\u53d1\u9001\u7aef\uff0c\u4fdd\u5b58\u5e76\u91cd\u542f\u540e\u9700\u91cd\u65b0\u6295\u5c4f' if chinese else 'Allow H.265 negotiation; the sender selects the actual codec. Save, restart and reconnect.',
                }
                data += ('\n' + '\n'.join(key + '=' + value for key, value in labels.items()) + '\n').encode('utf-8')
            output.writestr(info, data)
    changes['BOOT-INF/lib/gstreamer-1.1.2.jar'] = nested.getvalue()
    nested_server = io.BytesIO()
    with zipfile.ZipFile(BUILD / 'libs/server-1.1.2.jar') as source, zipfile.ZipFile(nested_server, 'w') as output:
        for info in source.infolist():
            output.writestr(info, (BUILD / info.filename).read_bytes()
                            if info.filename == video + 'VideoCodecConfiguration.class' else source.read(info.filename))
    changes['BOOT-INF/lib/server-1.1.2.jar'] = nested_server.getvalue()
    if len(changes) < 3:
        raise RuntimeError('Compiled lifecycle classes missing')
    destination = pathlib.Path(sys.argv[2]).resolve() if len(sys.argv) > 2 else JAR
    temporary = destination.with_suffix('.building.jar')
    with zipfile.ZipFile(BACKUP) as source, zipfile.ZipFile(temporary, 'w') as output:
        for info in source.infolist():
            output.writestr(info, brand(changes.pop(info.filename, source.read(info.filename)), info.filename))
        for name, data in changes.items():
            output.writestr(name, brand(data, name), compress_type=zipfile.ZIP_DEFLATED)
    temporary.replace(destination)
else:
    raise ValueError('Expected extract or package')
