"""Runs the edge challenge end to end from the runner, then asks the account API.

The point is to learn, outside the app, whether the hand-solved challenge makes
the account API accept a request that is not Firefox: the same steps the app
takes in Kotlin.
"""
import hashlib
import json
import re
import sys

import requests

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:158.0) Gecko/20100101 Firefox/158.0"
ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
API = "https://api.accounts.firefox.com/v1/account/login"
SITE = "https://accounts.firefox.com"

notes = []


def note(text):
    notes.append(text)
    print(text)


session = requests.Session()
session.headers.update({"User-Agent": UA, "Accept-Encoding": "identity"})


def solve_pow(base, target_hex):
    target = bytes.fromhex(target_hex)
    for a in ALPHABET:
        for b in ALPHABET:
            candidate = (base + a + b).encode()
            if hashlib.sha256(candidate).digest() == target:
                return a + b
    return None


def parse_init(script, prefix):
    decoder = json.JSONDecoder()
    starts = [m.end() - 1 for m in re.finditer(r"\binit\s*\(\s*\[", script)]
    for start in reversed(starts):
        try:
            challenges, end = decoder.raw_decode(script[start:])
        except ValueError:
            continue
        rest = script[start + end:]
        tokens = []
        i = 0
        while len(tokens) < 2 and i < len(rest):
            if rest[i] == '"':
                try:
                    value, used = decoder.raw_decode(rest[i:])
                except ValueError:
                    break
                tokens.append(value)
                i += used
            else:
                i += 1
        if len(tokens) < 2:
            continue
        token, script_prefix = tokens
        if script_prefix != prefix or not token:
            continue
        return challenges, token
    return None


def answer(challenges_data, prefix_url, token, type_):
    if type_ == "pow":
        data = challenges_data.get("data", {})
        suffix = solve_pow(data.get("base", ""), data.get("hash", ""))
        if suffix is None:
            raise RuntimeError("pow has no answer")
        return {
            "ty": "pow",
            "base": data.get("base", ""),
            "answer": suffix,
            "hmac": data.get("hmac", ""),
            "expires": data.get("expires", ""),
        }
    if type_ == "pat":
        response = session.post(
            prefix_url + "/pat?token=" + requests.utils.quote(token),
            headers={
                "Accept": "text/plain",
                "Content-Type": "application/json",
                "Origin": SITE,
            },
            timeout=20,
        )
        auth = ""
        if response.status_code == 200:
            try:
                auth = response.json().get("auth", "")
            except ValueError:
                auth = ""
        return {"ty": "pat", "auth": auth}
    if type_ == "clientmetrics":
        return {
            "ty": "clientmetrics",
            "webdriver": False,
            "bot_detection_result": {"bot_detected": False, "bot_kind": None},
            "browser_metrics": {"client_data": "{}", "error_trace": None},
            "detector_results": {},
            "v": 2,
        }
    raise RuntimeError("unknown challenge type " + type_)


page = session.get(SITE + "/", headers={"Accept": "text/html,application/xhtml+xml"}, timeout=20)
html = page.text
note("page=%d marks=%d clientchallenge=%s" % (
    page.status_code, html.count("/_fs-ch-"), "Client Challenge" in html))

match = re.search(r"/_fs-ch-[A-Za-z0-9]+", html)
if not match:
    note("no-challenge-prefix")
    print("::notice::" + " | ".join(notes))
    sys.exit(0)
prefix = match.group(0)
prefix_url = SITE + prefix

script = session.get(prefix_url + "/script.js?reload=true", timeout=20).text
note("script=%d bytes init=%d" % (len(script), len(re.findall(r"\binit\s*\(", script))))

parsed = parse_init(script, prefix)
if parsed is None:
    note("init-not-parsed")
    print("::notice::" + " | ".join(notes))
    sys.exit(0)

challenges, token = parsed
note("challenges=" + ",".join(c.get("ty", "?") for c in challenges))

for round_index in range(8):
    answers = [answer(c, prefix_url, token, c.get("ty", "")) for c in challenges]
    response = session.post(
        prefix_url + "/fst-post-back",
        data=json.dumps({"token": token, "data": answers}),
        headers={"Content-Type": "application/json", "Origin": SITE, "Accept": "application/json"},
        timeout=20,
    )
    note("postback=%d" % response.status_code)
    result = response.json()
    if result.get("status") == "success":
        break
    challenges = result.get("ch") or []
    token = result.get("tok", "")
    if not challenges or not token:
        note("no-next-round")
        break

after = session.get(SITE + "/", headers={"Accept": "text/html"}, timeout=20).text
note("cleared=%s cookies=%d" % ("Client Challenge" not in after, len(session.cookies)))

api = session.post(
    API,
    data=json.dumps({"email": "nobody@example.com", "authPW": "00" * 32}),
    headers={"Content-Type": "application/json", "Accept": "application/json"},
    timeout=20,
)
note("api=%d %s" % (api.status_code, api.text[:120].replace("\n", " ")))
print("::notice::" + " | ".join(notes))
