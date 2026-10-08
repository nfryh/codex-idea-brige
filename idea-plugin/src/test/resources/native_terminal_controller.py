# -*- coding: utf-8 -*-
# 控制本机已有 Codex 的隔离终端测试；使用原生审核接口，不接触真实认证或配置。
import fcntl
import json
import os
import pty
import re
import select
import signal
import struct
import subprocess
import sys
import termios
import time
import urllib.request

cli, home, cwd, descriptor, launcher = sys.argv[1:]
env = dict(os.environ)
env.update(CODEX_HOME=home, HOME=home, TERM='xterm-256color', HTTP_PROXY='http://127.0.0.1:1', HTTPS_PROXY='http://127.0.0.1:1', NO_PROXY='127.0.0.1,localhost', OPENAI_API_KEY='fixture-not-real')
env.pop('ICB_ENDPOINT_FILE', None)
backend = subprocess.Popen([cli, 'app-server'], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, env=env, cwd=cwd)
serial = 0
buffer = bytearray()


def rpc(method, params):
    """通过原生应用服务器接口审核仅存在于测试目录的回调。"""
    global serial, buffer
    serial += 1
    backend.stdin.write((json.dumps({'jsonrpc': '2.0', 'id': serial, 'method': method, 'params': params}) + '\n').encode())
    backend.stdin.flush()
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        while b'\n' in buffer:
            line, _, tail = buffer.partition(b'\n')
            buffer = bytearray(tail)
            message = json.loads(line)
            if message.get('id') == serial:
                if 'error' in message:
                    raise RuntimeError('NATIVE_RPC_REJECTED_' + method)
                return message['result']
        if select.select([backend.stdout], [], [], 0.2)[0]:
            data = os.read(backend.stdout.fileno(), 65536)
            if not data:
                break
            buffer.extend(data)
    raise RuntimeError('NATIVE_RPC_TIMEOUT_' + method)


pid = None
fd = None
try:
    rpc('initialize', {'clientInfo': {'name': 'icb-isolated-fixture', 'version': '1.0.0'}, 'capabilities': {'experimentalApi': True}})
    backend.stdin.write(b'{"jsonrpc":"2.0","method":"initialized","params":{}}\n')
    backend.stdin.flush()
    hooks = rpc('hooks/list', {'cwds': [cwd]})['data'][0]['hooks']
    assert len(hooks) == 5
    trusts = {hook['key']: {'trusted_hash': hook['currentHash']} for hook in hooks}
    rpc('config/batchWrite', {'edits': [{'keyPath': 'hooks.state', 'value': trusts, 'mergeStrategy': 'upsert'}], 'reloadUserConfig': True})
    assert all(hook['trustStatus'] == 'trusted' for hook in rpc('hooks/list', {'cwds': [cwd]})['data'][0]['hooks'])
    print(json.dumps({'fixtureHookEnabled': [hook['enabled'] for hook in hooks]}), flush=True)
    backend.stdin.close()
    backend.wait(timeout=5)
    env.update(ICB_ENDPOINT_FILE=descriptor, ICB_REAL_CODEX=cli, ICB_DIRECT_MODE_SUPPORTED='true')
    env['PATH'] = launcher + os.pathsep + env.get('PATH', '')
    fields = dict(line.split('=', 1) for line in open(descriptor).read().splitlines() if '=' in line)
    headers = {'Content-Type': 'application/json; charset=utf-8', 'Authorization': 'Bearer ' + fields['auth_token'],
               'X-ICB-Protocol': '1', 'X-ICB-Instance-Id': fields['instance_id'], 'X-ICB-Project-Id': fields['project_id'], 'X-ICB-Terminal-Id': fields['terminal_id']}
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(urllib.request.Request(fields['origin'] + '/v1/health', data=b'{}', headers=headers), timeout=2) as response:
        print(json.dumps({'fixtureBridgeHealthy': response.status == 200}), flush=True)
    pid, fd = pty.fork()
    if pid == 0:
        os.chdir(cwd)
        # 用户入口仍是 codex；参数由测试所用的同一生产启动脚本处理。
        os.execve('/bin/zsh', ['/bin/zsh', '-f', '-c', 'codex'], env)
    fcntl.ioctl(fd, termios.TIOCSWINSZ, struct.pack('HHHH', 35, 160, 0, 0))
    print(json.dumps({'started': True}), flush=True)
    deadline = time.monotonic() + 40
    terminal_output = bytearray()
    input_buffer = bytearray()
    while time.monotonic() < deadline:
        readable = select.select([fd, sys.stdin], [], [], 0.1)[0]
        if fd in readable:
            try:
                data = os.read(fd, 65536)
            except OSError:
                break
            if not data:
                break
            terminal_output.extend(data)
            if b'\x1b[6n' in data:
                os.write(fd, b'\x1b[1;1R')
        if sys.stdin in readable:
            data = os.read(sys.stdin.fileno(), 65536)
            if not data:
                break
            input_buffer.extend(data)
        close_requested = False
        while b'\n' in input_buffer:
            line, _, tail = input_buffer.partition(b'\n')
            input_buffer = bytearray(tail)
            command = json.loads(line)
            if command['op'] == 'paste':
                os.write(fd, b'\x1b[F\x1b[200~' + command['text'].encode() + b'\x1b[201~')
                print(json.dumps({'pasted': True}), flush=True)
            elif command['op'] == 'submit':
                time.sleep(0.2)
                os.write(fd, b'\r')
                print(json.dumps({'submitted': True}), flush=True)
            elif command['op'] == 'close':
                close_requested = True
            elif command['op'] == 'diagnostics':
                # 仅用于隔离测试，屏幕只含测试路径、假登录和公开夹具，不取用户真实会话。
                text = re.sub(r'\x1b\[[0-?]*[ -/]*[@-~]', '', terminal_output.decode('utf-8', 'replace'))
                rows = subprocess.check_output(['ps', '-Ao', 'pid,ppid'], text=True).splitlines()[1:]
                owners = {pid}
                for _ in range(8):
                    owners.update(int(row.split()[0]) for row in rows if int(row.split()[1]) in owners)
                arguments = subprocess.check_output(['ps', '-p', ','.join(str(owner) for owner in owners), '-o', 'args='], text=True)
                print(json.dumps({'fixtureNativeDirectMode': '--no-daemon' in arguments, 'fixtureTerminalTail': text[-4500:]}), flush=True)
        if close_requested:
            break
finally:
    if fd is not None:
        os.close(fd)
    if pid is not None:
        try:
            os.killpg(pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
        deadline = time.monotonic() + 3
        while time.monotonic() < deadline:
            if os.waitpid(pid, os.WNOHANG)[0]:
                break
            time.sleep(0.05)
        else:
            os.kill(pid, signal.SIGKILL)
            os.waitpid(pid, 0)
    if backend.poll() is None:
        backend.terminate()
        backend.wait(timeout=5)
