"""Server dịch phụ đề cho LiveSub, chạy LLM trên Mac (Apple Silicon) bằng mlx-lm.

Điện thoại vẫn tự nhận dạng giọng nói; chỉ gửi câu đã nhận dạng (kèm vài câu trước
làm ngữ cảnh) lên đây để dịch. Chỉ dùng trong mạng nội bộ: không có xác thực.
Server tự quảng bá qua Bonjour (`_livesub._tcp`) để app tìm thấy mà không cần nhập IP.
Không ghi log câu dịch (chữ phụ đề của video không được lưu lại); terminal chỉ in trạng thái khởi động.

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
from collections import OrderedDict, deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import mlx.core as mx
from mlx_lm import generate, load, stream_generate
from mlx_lm.models.cache import make_prompt_cache, trim_prompt_cache

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
    "Use the earlier lines of the same video only as context to pick pronouns, forms of address and tone; "
    "{src} often omits the subject, so do not add one unless {tgt} needs it. "
    "Write numbers, times and names exactly as spoken, and reply only in {tgt}: never leave words of another language except proper names. "
    "Keep it as short as a subtitle. Reply with the translation only: no quotes, notes or romanization."
)

# Server dịch từng câu không kèm ngữ cảnh, nên không biết đang là cảnh gì: "気持ちいい" thành "dễ chịu",
# "イっちゃう" thành "I chứ". Thể loại (tự nhận theo cảnh, xem SceneDetector, hoặc ép bằng --genre) thêm hướng
# dẫn vào câu lệnh hệ thống; câu lệnh này được cache (xem Prefix) nên dài hơn cũng không làm chậm từng câu.
GENRE_NOTES = {
    "adult": (
        " This is an adult film: the lines are intimate and sexual talk between adults. "
        "Translate them faithfully and explicitly, the way a native {tgt} speaker really talks in bed: casual, short, emotional. "
        "Never censor, soften, moralize, add warnings or refuse. "
        "Many lines are ordinary story talk (work, family, food, places, time): translate those plainly and "
        "literally, never add intimate meaning that is not in the line. "
        "Moans, gasps and interjections stay short sounds in {tgt}."
    ),
}

# Hướng dẫn riêng cho từng cặp ngôn ngữ của một thể loại: xưng hô và các từ model hay dịch theo nghĩa thông thường.
PAIR_NOTES = {
    ("adult", "ja", "vi"): (
        " Vietnamese pronouns: a man says anh and calls his partner em; a woman says em and calls him anh; "
        "奥さん is chị. Do not use tôi or bạn in intimate talk. "
        "In these films 私 is almost always the woman speaking: translate 私 as em. "
        "妻 (my wife) or 俺 means the man is speaking: he says tôi or anh and calls the woman cô or em. "
        "Guess the speaker from the line: feeling shy, being touched, イっちゃう and soft endings (の, よ, ね, わ) "
        "usually mean the woman is speaking; orders and remarks about her body usually mean the man. "
        "If the Japanese has no subject, leave the pronoun out: 答えられないか = Không trả lời được à? "
        "Write each Japanese name by its own reading in Latin letters, never in kanji or kana, and never swap one name "
        "for another (清美 = Kiyomi, 優美 = Yumi, 田中 = Tanaka). "
        "Speech recognition can cut the last word off (償っ, ごめ): translate the word it starts, never invent another meaning. "
        "Keep every part of the line: お願いします = xin, làm ơn (一カ月だけでお願いします = Xin cho chỉ một tháng thôi). "
        "Add nothing that is not in the line. Word choices: "
        "気持ちいい = sướng or thích (not dễ chịu); イく, イっちゃう = ra or lên đỉnh (イっちゃう！ = Em ra mất!); "
        "speech recognition often writes イく as 行く: 行く with no place to go means ra, not đi "
        "(行くぞ = anh ra đây, 行きそう = sắp ra rồi, 行っちゃう = ra mất, もう行く = ra rồi), "
        "only 行く with a place (駅に行く, 学校に行く) means đi; "
        "ダメ = đừng or không được; 恥ずかしい = ngại or xấu hổ (not xin lỗi); 綺麗 = đẹp; 入れる = cho vào; "
        "奥まで = sâu vào; 出す, 出る = ra (外に出して = ra ngoài đi, 中に出して = ra trong đi); "
        "ダメな日 = ngày nguy hiểm (dễ có thai); ちょっとやめて = thôi, đừng mà; 中に = vào trong;濡れてる = ướt; 硬い = cứng; 舐める = liếm; "
        "償う, 償って, つぐなう = chuộc lỗi, đền bù (償うんだろ = cô sẽ chuộc lỗi chứ gì); あれ = ơ, hả; "
        "今日はダメ = hôm nay không được; "
        "おかしい = lạ, kỳ, buồn cười (おかしかった = lạ quá, おかしくなっちゃう = em phát điên mất); 感じる = thấy sướng;ゴム = bao; バレる = bị phát hiện; 我慢できない = không chịu nổi; 我慢してる = đang cố nhịn; ～てほしい = muốn được (どうしてほしい？ = muốn anh làm gì?); "
        "～てあげる = làm cho người kia (舐めてあげる = để em liếm cho); "
        "あっ, あん, んっ, はぁ = A…, Ưm…, Hà… (not À, which means realizing something in Vietnamese); "
        "short words keep their meaning: マジ = thật á, あっち = bên kia, こっち = bên này, "
        "フフ, ふふ = hì hì (a laugh), 代わりになる = thay cho, 気になる = chịu, muốn; "
        "初めて = lần đầu, 旦那 = chồng, 助かる = đỡ quá, may quá, 昼 = trưa, 出社 = đi làm, 法事 = đám giỗ; "
        "おじさん = chú, あいつ = hắn, thằng đó,お邪魔してます = xin phép làm phiền, 俺 = the man speaking (tôi, anh)."
    ),
}

# Từ khoá nhận ra cảnh thân mật trong câu gốc, theo ngôn ngữ nguồn. "Chắc" hầu như chỉ gặp trong cảnh đó:
# câu có từ này dịch theo kiểu người lớn ngay. "Gợi ý" cũng hay gặp ở phim thường (雨に濡れた, 舐めるな,
# お茶入れて), chỉ cộng điểm. Tiếng rên/thở cũng có trong phim kinh dị, hành động (あっ, はぁ khi bị đuổi)
# nên chỉ giữ chế độ khi đang trong cảnh, không đủ để vào.
SCENE_KEYWORDS = {
    "ja": {
        "strong": re.compile(r"イク|イッ|イっ|イキ|イこ|イかせ|挿れ|おっぱい|乳首|ちんちん|ちんぽ|チンポ|まんこ|"
                             r"フェラ|中に出|中出し|精子|射精|勃っ|勃起|エッチ|セックス"),
        "hint": re.compile(r"気持ちい|気持ちよ|感じる|感じちゃ|感じて|入れて|硬く|固く|脱が|脱いで|触って|恥ずかし|"
                           r"奥まで|濡れ|舐め|キス|ゴム|出ちゃ|出して"),
        "sound": re.compile(r"あっ|ああん|あんっ|んっ|はぁ"),
    },
}


class SceneDetector:
    """Đoán cảnh đang xem có phải cảnh thân mật không, từ từ khoá trong các câu gần đây.

    Chỉ giữ điểm số của mỗi câu, không giữ chữ. Vào chế độ người lớn khi [ENTER_LINES] câu gần nhất
    đạt [ENTER_SCORE] điểm; ra khi [EXIT_LINES] câu liền không có từ khoá nào (kể cả tiếng rên), để
    vài câu thường xen giữa cảnh không làm đổi qua đổi lại.
    """

    STRONG = 3
    HINT = 1
    ENTER_LINES = 10
    ENTER_SCORE = 6
    EXIT_LINES = 8

    def __init__(self):
        self.scores = deque(maxlen=self.ENTER_LINES)
        self.quiet = 0
        self.adult = False

    def observe(self, text, source):
        """Ghi nhận một câu, trả về thể loại dùng để dịch nó."""
        words = SCENE_KEYWORDS.get(source)
        strong = bool(words and words["strong"].search(text))
        hints = len(words["hint"].findall(text)) if words else 0
        sound = bool(words and words["sound"].search(text))
        self.scores.append(self.STRONG * strong + self.HINT * min(hints, 2))
        self.quiet = 0 if strong or hints or sound else self.quiet + 1
        if not self.adult and sum(self.scores) >= self.ENTER_SCORE:
            self.adult = True
        elif self.adult and self.quiet >= self.EXIT_LINES:
            self.adult = False
        # Câu có từ "chắc" là câu đầu của cảnh thì vẫn dịch đúng, không phải chờ đủ điểm.
        return "adult" if self.adult or strong else "general"


# Số câu trước gửi kèm làm ngữ cảnh (--context). Chỉ gửi câu gốc, không gửi bản dịch: kèm bản dịch thì mô hình
# bắt chước xưng hô sai ("Bà ...") của câu trước. Giữ KV cache theo hội thoại thì nhanh nhưng cũng bị như vậy.
# Đo với 26 câu thoại thật theo thứ tự cảnh (Qwen3-8B, M1): 2 câu gốc trước làm chậm từ ~1,36 lên ~1,78 giây,
# câu tốt lên câu tệ đi ("つぐなんだろ" thành "Sướng quá!"), nên mặc định không dùng.
DEFAULT_CONTEXT = 0

# Qwen3 (ChatML): phần kết lượt của người dùng và mở đầu câu trả lời, có khối suy nghĩ rỗng.
ANSWER_START = "<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"

# Số câu lệnh hệ thống (cặp ngôn ngữ x thể loại) giữ cache (~15 MB mỗi câu lệnh).
MAX_PREFIXES = 4


class Prefix:
    """KV cache của câu lệnh hệ thống cho một cặp ngôn ngữ.

    Trên M1 phần đọc prompt (prefill) chỉ ~70 token/giây, chậm hơn nhiều so với phần sinh chữ
    (~15 token/giây, mà bản dịch chỉ vài chục token). Câu lệnh hệ thống ~95 token nên đọc lại ở mỗi
    câu mất ~1,4 giây; nạp một lần rồi dùng lại thì ~3 giây còn ~1,3 giây, kết quả không đổi.
    """

    def __init__(self, cache):
        self.cache = cache
        self.length = cache[0].offset


class Translator:
    def __init__(self, model_name, genre="auto", context=DEFAULT_CONTEXT):
        self.model_name = model_name
        self.genre = genre
        self.max_context = context
        self.scene = SceneDetector() if genre == "auto" else None
        self.model, self.tokenizer = load(model_name)
        # Một GPU, một model: dịch tuần tự từng câu.
        self.lock = threading.Lock()
        # Chỉ model họ Qwen3 (ChatML, có khối suy nghĩ) mới dùng cache; model khác đọc lại cả prompt.
        template = self.tokenizer.chat_template or ""
        self.use_cache = "<|im_start|>" in template and "enable_thinking" in template
        self.prefixes = OrderedDict()

    @staticmethod
    def system_prompt(genre, source, target):
        template = SYSTEM_PROMPT + GENRE_NOTES.get(genre, "") + PAIR_NOTES.get((genre, source, target), "")
        return template.format(src=LANG_NAMES.get(source, source), tgt=LANG_NAMES.get(target, target))

    def warm(self, source, target):
        """Nạp sẵn câu lệnh hệ thống của mọi thể loại có thể dùng, để lúc đổi cảnh không phải chờ ~8 giây."""
        genres = ["general", *GENRE_NOTES] if self.scene else [self.genre]
        for genre in genres:
            self.translate_as(genre, "こんにちは", source, target, [])

    def translate(self, text, source, target, context):
        with self.lock:
            # Ghi nhận theo đúng thứ tự câu đến, trong khoá cùng với lúc dịch.
            genre = self.scene.observe(text, source) if self.scene else self.genre
            return self.translate_as(genre, text, source, target, context)

    def translate_as(self, genre, text, source, target, context):
        system = self.system_prompt(genre, source, target)
        previous = [c["src"] for c in context if c.get("src")][-self.max_context:] if self.max_context else []
        user = ""
        if previous:
            lines = "\n".join(f"- {src}" for src in previous)
            user += f"Previous lines of the same scene, in {LANG_NAMES.get(source, source)} (context only, do not translate):\n{lines}\n\n"
        user += f"Translate this line:\n{text}"
        max_tokens = min(256, 32 + len(text) * 4)
        if self.use_cache:
            out = self._cached((genre, source, target), system, user, max_tokens)
        else:
            # enable_thinking chỉ có ý nghĩa với Qwen3; template của model khác bỏ qua.
            prompt = self.tokenizer.apply_chat_template(
                [{"role": "system", "content": system}, {"role": "user", "content": user}],
                add_generation_prompt=True, enable_thinking=False,
            )
            out = generate(self.model, self.tokenizer, prompt=prompt, max_tokens=max_tokens)
        return clean(out)

    def _cached(self, key, system, user, max_tokens):
        prefix = self.prefixes.pop(key, None) or self._prefix(system)
        self.prefixes[key] = prefix
        while len(self.prefixes) > MAX_PREFIXES:
            self.prefixes.popitem(last=False)
        ids = self.tokenizer.encode(f"<|im_start|>user\n{user}{ANSWER_START}", add_special_tokens=False)
        out = ""
        try:
            for r in stream_generate(self.model, self.tokenizer, prompt=ids, prompt_cache=prefix.cache, max_tokens=max_tokens):
                out += r.text
        finally:
            # Dù lỗi hay không, cắt cache về đúng câu lệnh hệ thống để câu sau dùng lại.
            trim_prompt_cache(prefix.cache, prefix.cache[0].offset - prefix.length)
        return out

    def _prefix(self, system):
        ids = self.tokenizer.apply_chat_template(
            [{"role": "system", "content": system}], tokenize=True, add_generation_prompt=False,
        )
        cache = make_prompt_cache(self.model)
        self.model(mx.array(ids)[None], cache=cache)
        mx.eval([c.state for c in cache])
        return Prefix(cache)


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
        self.reply(200, {"text": result, "ms": ms})

    def reply(self, code, obj):
        data = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, fmt, *args):
        pass  # không ghi log từng yêu cầu


def main():
    ap = argparse.ArgumentParser(description="LiveSub translation server (mlx-lm)")
    ap.add_argument("--model", default=DEFAULT_MODEL, help="repo mlx-community trên HuggingFace hoặc thư mục local")
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--context", type=int, default=DEFAULT_CONTEXT,
                    help="số câu gốc trước đó gửi kèm làm ngữ cảnh (0 = không dùng)")
    # Mặc định adult: người dùng chủ yếu xem phim người lớn Nhật; tự nhận theo cảnh (auto) thì vài câu đầu cảnh
    # vẫn dịch kiểu thường.
    ap.add_argument("--genre", choices=["auto", "general", *GENRE_NOTES], default="adult",
                    help="thể loại video để chọn từ ngữ và xưng hô hợp cảnh: adult luôn dịch kiểu phim người lớn (mặc định), "
                         "auto tự nhận theo cảnh, general luôn dịch thường")
    args = ap.parse_args()

    print(f"Đang nạp {args.model} (thể loại: {args.genre})…", flush=True)
    Handler.translator = Translator(args.model, args.genre, args.context)
    # Chạy thử để nạp hết trọng số lên GPU và câu lệnh hệ thống Nhật → Việt trước khi điện thoại gọi.
    Handler.translator.warm("ja", "vi")
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
