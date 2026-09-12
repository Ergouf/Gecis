import subprocess
import threading
import sys

agy = r"C:\Users\fengj\AppData\Local\agy\bin\agy.exe"
args = [
    agy,
    "--input-format",
    "stream-json",
    "--output-format",
    "stream-json",
    "--sandbox",
    "--print-timeout",
    "40s",
]
print("args", args, flush=True)
p = subprocess.Popen(
    args,
    stdin=subprocess.PIPE,
    stdout=subprocess.PIPE,
    stderr=subprocess.PIPE,
    text=True,
    encoding="utf-8",
    bufsize=1,
)


def r(name, stream):
    for line in stream:
        print(f"[{name}] {line.rstrip()}", flush=True)


threading.Thread(target=r, args=("OUT", p.stdout), daemon=True).start()
threading.Thread(target=r, args=("ERR", p.stderr), daemon=True).start()
p.stdin.write('{"event":"user","message":{"content":"只回复两个字：收到"}}\n')
p.stdin.flush()
try:
    print("exit", p.wait(timeout=45), flush=True)
except Exception as e:
    print("timeout", e, flush=True)
    p.kill()
