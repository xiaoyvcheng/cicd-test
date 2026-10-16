#!/usr/bin/env python3
"""Run remote commands over SSH with password. Usage:
  python ssh_run.py HOST USER PASS 'command' [--sudo]
  python ssh_run.py HOST USER PASS --upload LOCAL REMOTE
"""
import sys
import paramiko

def connect(host, user, password):
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(host, port=22, username=user, password=password, timeout=30)
    return c

def run(host, user, password, cmd, sudo=False):
    if sudo:
        # Escape single quotes for bash
        escaped = cmd.replace("'", "'\"'\"'")
        cmd = f"echo '{password}' | sudo -S -p '' bash -lc '{escaped}'"
    c = connect(host, user, password)
    try:
        stdin, stdout, stderr = c.exec_command(cmd, get_pty=True, timeout=600)
        out = stdout.read().decode("utf-8", errors="replace")
        err = stderr.read().decode("utf-8", errors="replace")
        code = stdout.channel.recv_exit_status()
        enc = getattr(sys.stdout, "encoding", None) or "utf-8"
        sys.stdout.buffer.write((out + (err or "")).encode(enc, errors="replace"))
        sys.stdout.buffer.write(f"\n[exit={code}]\n".encode(enc, errors="replace"))
        return code
    finally:
        c.close()

def upload(host, user, password, local, remote):
    c = connect(host, user, password)
    try:
        sftp = c.open_sftp()
        sftp.put(local, remote)
        sftp.close()
        print(f"uploaded {local} -> {remote}")
        return 0
    finally:
        c.close()

def main():
    if len(sys.argv) < 5:
        print(__doc__)
        sys.exit(2)
    host, user, password = sys.argv[1], sys.argv[2], sys.argv[3]
    if sys.argv[4] == "--upload":
        sys.exit(upload(host, user, password, sys.argv[5], sys.argv[6]))
    sudo = "--sudo" in sys.argv
    args = [a for a in sys.argv[4:] if a != "--sudo"]
    cmd = " ".join(args)
    sys.exit(run(host, user, password, cmd, sudo=sudo))

if __name__ == "__main__":
    main()
