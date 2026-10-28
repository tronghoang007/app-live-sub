"""Server dịch phụ đề cho LiveSub, chạy LLM trên Mac (Apple Silicon) bằng mlx-lm.

Điện thoại vẫn tự nhận dạng giọng nói; chỉ gửi câu đã nhận dạng (kèm vài câu trước
làm ngữ cảnh) lên đây để dịch. Chỉ dùng trong mạng nội bộ: không có xác thực.
Server tự quảng bá qua Bonjour (`_livesub._tcp`) để app tìm thấy mà không cần nhập IP.

    GET  /health     -> {"ok": true, "model": "..."}
    POST /translate  {"text", "source", "target", "context": [{"src", "dst"}]}
                     -> {"text": "...", "ms": 812}
"""

import argparse
import json
import re
import shutil
import socket
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from mlx_lm import generate, load

DEFAULT_MODEL = "mlx-community/Qwen3-8B-4bit"

LANG_NAMES = {
    "vi": "Vietnamese", "en": "English", "ja": "Japanese", "ko": "Korean", "zh": "Chinese",
    "yue": "Cantonese", "th": "Thai", "id": "Indonesian", "ms": "Malay", "tl": "Filipino",
    "hi": "Hindi", "fr": "French", "de": "German", "es": "Spanish", "it": "Italian",
    "pt": "Portuguese", "ru": "Russian", "tr": "Turkish", "ar": "Arabic",
}

SYSTEM_PROMPT = (
    "You translate film and video subtitles from {src} into natural, spoken {tgt}. "
    "The source line comes from speech recognition and may contain small errors: translate the most plausible meaning. "
    "Use the previous lines only as context to pick pronouns, forms of address and tone; "
    "{src} often omits the subject, so do not add one unless {tgt} needs it. "
    "Keep it as short as a subtitle. Reply with the translation only: no quotes, notes or romanization."
)

MAX_CONTEXT = 4


class Translator:
    def __init__(self, model_name):
        self.model_name = model_name
        self.model, self.tokenizer = load(model_name)
        # Một GPU, một model: dịch tuần tự từng câu.
        self.lock = threading.Lock()

    def translate(self, text, source, target, context):
        src = LANG_NAMES.get(source, source)
        tgt = LANG_NAMES.get(target, target)
        user = ""
        pairs = [c for c in context[-MAX_CONTEXT:] if c.get("src")]
        if pairs:
            lines = "\n".join(f"- {c['src']} => {c.get('dst', '')}" for c in pairs)
            user += f"Previous lines (context only, do not translate):\n{lines}\n\n"
        user += f"Translate this line:\n{text}"
        messages = [
            {"role": "system", "content": SYSTEM_PROMPT.format(src=src, tgt=tgt)},
            {"role": "user", "content": user},
        ]
        # enable_thinking chỉ có ý nghĩa với Qwen3; template của model khác bỏ qua.
        prompt = self.tokenizer.apply_chat_template(
            messages, add_generation_prompt=True, enable_thinking=False,
        )
        max_tokens = min(256, 32 + len(text) * 4)
        with self.lock:
            out = generate(self.model, self.tokenizer, prompt=prompt, max_tokens=max_tokens)
        return clean(out)


def clean(out):
    out = re.sub(r"<think>.*?</think>", "", out, flags=re.S).strip()
    out = out.splitlines()[0].strip() if out else ""
    return out.strip("\"“”「」『』")


class Handler(BaseHTTPRequestHandler):
    translator = None  # gán trong main()

    def do_GET(self):
        if self.path == "/health":
            self.reply(200, {"ok": True, "model": self.translator.model_name})
        else:
            self.reply(404, {"error": "not found"})

    def do_POST(self):
        if self.path != "/translate":
            self.reply(404, {"error": "not found"})
            return
        try:
            body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))))
            text = body["text"].strip()
            source, target = body["source"], body["target"]
            context = body.get("context") or []
        except (KeyError, ValueError, TypeError, AttributeError):
            self.reply(400, {"error": "bad request"})
            return
        t0 = time.monotonic()
        try:
            result = self.translator.translate(text, source, target, context) if text else ""
        except Exception as e:  # noqa: BLE001 - trả lỗi cho app tự chuyển sang ML Kit
            self.reply(500, {"error": str(e)})
            return
        ms = int((time.monotonic() - t0) * 1000)
        print(f"{source}->{target} {ms}ms  {text}  =>  {result}", flush=True)
        self.reply(200, {"text": result, "ms": ms})

    def reply(self, code, obj):
        data = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, fmt, *args):
        pass  # đã tự in từng câu dịch ở do_POST


def main():
    ap = argparse.ArgumentParser(description="LiveSub translation server (mlx-lm)")
    ap.add_argument("--model", default=DEFAULT_MODEL, help="repo mlx-community trên HuggingFace hoặc thư mục local")
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=8765)
    args = ap.parse_args()

    print(f"Đang nạp {args.model}…", flush=True)
    Handler.translator = Translator(args.model)
    # Chạy thử một câu để nạp hết trọng số lên GPU trước khi điện thoại gọi.
    Handler.translator.translate("こんにちは", "ja", "vi", [])
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    bonjour = advertise(args.port, args.model)
    print(f"Sẵn sàng tại http://{args.host}:{args.port}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        if bonjour:
            bonjour.terminate()


def advertise(port, model):
    """Quảng bá server qua Bonjour bằng `dns-sd` có sẵn trên macOS; tiến trình sống tới khi server tắt."""
    if not shutil.which("dns-sd"):
        print("Không có dns-sd: app sẽ không tự tìm thấy server, hãy nhập IP:port bằng tay.", flush=True)
        return None
    name = f"LiveSub {socket.gethostname().split('.')[0]}"[:63]
    print(f"Đang quảng bá \"{name}\" (_livesub._tcp) để app tự tìm thấy", flush=True)
    return subprocess.Popen(
        ["dns-sd", "-R", name, "_livesub._tcp", "local", str(port), f"model={model.split('/')[-1]}"],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )


if __name__ == "__main__":
    main()
