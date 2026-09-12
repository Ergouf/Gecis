import subprocess
import sys
import threading

agy = r"C:\Users\fengj\AppData\Local\agy\bin\agy.exe"
args = [agy, "--input-format", "stream-json", "--output-format", "stream-json", "--print-timeout", "45s"]
print("starting", flush=True)
p = subprocess.Popen(
    args,
    stdin=subprocess.PIPE,
    stdout=subprocess.PIPE,
    stderr=subprocess.PIPE,
    text=True,
    encoding="utf-8",
    bufsize=1,
)
print("pid", p.pid, flush=True)


def reader(name, stream):
    for line in stream:
        print(f"[{name}] {line.rstrip()}", flush=True)


t1 = threading.Thread(target=reader, args=("OUT", p.stdout), daemon=True)
t2 = threading.Thread(target=reader, args=("ERR", p.stderr), daemon=True)
t1.start()
t2.start()
msg = '{"event":"user","message":{"content":"只回复两个字：收到"}}\n'
print("writing", flush=True)
p.stdin.write(msg)
p.stdin.flush()
try:
    code = p.wait(timeout=60)
    print("exit", code, flush=True)
except subprocess.TimeoutExpired:
    print("TIMEOUT kill", flush=True)
    p.kill()
    p.wait(timeout=10)
print("done", flush=True)
