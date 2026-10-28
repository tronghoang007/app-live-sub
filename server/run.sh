#!/bin/sh
# Chạy server dịch LiveSub. Lần đầu tự tạo venv và cài mlx-lm; model tải về ~/.cache/huggingface.
#   ./run.sh                                         # model mặc định (Qwen3-8B-4bit)
#   ./run.sh --model mlx-community/gemma-3-12b-it-4bit
set -e
cd "$(dirname "$0")"
if [ ! -x .venv/bin/python ]; then
    python3 -m venv .venv
    .venv/bin/pip install -q --upgrade pip
    .venv/bin/pip install -q -r requirements.txt
fi
ip=$(ipconfig getifaddr en0 2>/dev/null || true)
[ -n "$ip" ] && echo "IP máy này trong mạng Wi-Fi: $ip  → nhập \"$ip:8765\" vào app (Cài đặt → Server dịch)"
exec .venv/bin/python -W ignore livesub_server.py "$@"
