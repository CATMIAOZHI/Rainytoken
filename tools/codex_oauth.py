import argparse
import base64
import json
import time
import httpx
from openai import OpenAI, AuthenticationError

AUTH_URL  = "https://auth.openai.com/oauth/authorize"
TOKEN_URL = "https://auth.openai.com/oauth/token"
BASE_URL  = "https://chatgpt.com/backend-api/wham"

# Originally the Codex CLI client ID; OpenCode uses the same value.
# No standard allocation mechanism exists for third-party apps.
CLIENT_ID   = "app_EMoamEEZ73f0CkXaXp7hrann"
CLIENT_NAME = "sample-script"
CLIENT_VER  = "0.0.1"

# seconds before expiry to trigger refresh
SAFETY_MARGIN = 30

__all__ = [
    "AuthManager",
    "extract_account_id",
    "cmd_login",
]


def extract_account_id(id_token: str | None, access_token: str | None) -> str | None:
    """Extract account_id from JWT with 3-level fallback.

    No signature verification needed — we only read the payload for account_id.
    The '-len % 4' padding trick avoids adding '=' when length is already a multiple of 4.
    Tries id_token first, then access_token, because the claim location varies by token.
    """
    for token in [id_token, access_token]:
        if not token:
            continue
        try:
            payload_b64 = token.split(".")[1]
            payload_b64 += "=" * (-len(payload_b64) % 4)
            payload = json.loads(base64.urlsafe_b64decode(payload_b64))
        except Exception:
            continue
        # 1. top-level chatgpt_account_id
        if aid := payload.get("chatgpt_account_id"):
            return aid
        # 2. inside https://api.openai.com/auth namespace
        if aid := payload.get("https://api.openai.com/auth", {}).get("chatgpt_account_id"):
            return aid
        # 3. organizations[0].id
        orgs = payload.get("organizations", [])
        if orgs and (aid := orgs[0].get("id")):
            return aid
    return None


class AuthManager:
    def __init__(self, path: str = "auth.json", tokens: dict = None):
        self.path = path
        if tokens is not None:
            self._set_data(tokens)
        else:
            self.data = self._load()

    def _load(self) -> dict:
        """Returns {} on first run (no auth.json yet); FileNotFoundError is expected."""
        try:
            with open(self.path) as f:
                return json.load(f)
        except FileNotFoundError:
            return {}

    def _save(self):
        with open(self.path, "w") as f:
            json.dump(self.data, f, indent=2, ensure_ascii=False)

    def _set_data(self, tokens: dict, fallback_account_id=None):
        expires_in   = tokens.get("expires_in") or 3600
        expires      = int(time.time() * 1000) + expires_in * 1000
        id_token     = tokens.get("id_token")
        access_token = tokens.get("access_token")
        account_id   = extract_account_id(id_token, access_token) or fallback_account_id
        data = {
            "type":    "oauth",
            "access":  access_token,
            "refresh": tokens.get("refresh_token"),
            "expires": expires,
        }
        if account_id:
            data["accountId"] = account_id
        self.data = data

    def refresh(self):
        """POST grant_type=refresh_token; _set_data recalculates expiry and updates account_id."""
        resp = httpx.post(
            TOKEN_URL,
            data={
                "grant_type":    "refresh_token",
                "refresh_token": self.data["refresh"],
                "client_id":     CLIENT_ID,
            },
        )
        if not (200 <= resp.status_code < 300):
            raise RuntimeError(f"Token refresh failed: {resp.status_code}")
        self._set_data(resp.json(), self.data.get("accountId"))
        self._save()

    def ensure_valid(self):
        now_ms = int(time.time() * 1000)
        if not self.data.get("access") or self.data.get("expires", 0) < now_ms + SAFETY_MARGIN * 1000:
            self.refresh()

    def make_client(self) -> OpenAI:
        """api_key accepts the OAuth access token directly;
        the library formats it as "Authorization: Bearer <token>".
        """
        headers = {"User-Agent": f"{CLIENT_NAME}/{CLIENT_VER}"}
        if account_id := self.data.get("accountId"):
            headers["ChatGPT-Account-Id"] = account_id
        return OpenAI(
            api_key=self.data["access"],
            base_url=BASE_URL,
            default_headers=headers,
        )

    def auto_refresh(self, f, *args, **kwargs):
        """Checks token validity before calling f, and retries once on HTTP 401.

        f receives self as its first argument so it can call make_client()
        after a refresh and get a client with the updated token.
        """
        self.ensure_valid()
        try:
            f(self, *args, **kwargs)
        except AuthenticationError:
            self.refresh()
            f(self, *args, **kwargs)


def cmd_login():
    """Run the Authorization Code + PKCE flow to obtain OAuth tokens.

    Opens a browser for the user to log in, receives the auth code via a local
    HTTP callback server, exchanges it for tokens, and saves them to auth.json.
    """
    import datetime
    import hashlib
    import secrets
    import threading
    import webbrowser
    from http.server import BaseHTTPRequestHandler, HTTPServer
    from urllib.parse import parse_qs, urlparse
    from authlib.integrations.httpx_client import OAuth2Client

    # SCOPE and REDIRECT_URI are used only inside cmd_login.
    SCOPE        = "openid profile email offline_access"
    REDIRECT_URI = "http://localhost:1455/auth/callback"

    # authlib's OAuth2Client does not auto-add code_challenge even when
    # code_challenge_method="S256" is passed to the constructor, so generate manually.
    code_verifier = secrets.token_urlsafe(96)
    digest = hashlib.sha256(code_verifier.encode()).digest()
    code_challenge = base64.urlsafe_b64encode(digest).rstrip(b"=").decode()

    # authlib manages state and builds the authorization URL.
    # code_challenge and code_challenge_method must be passed explicitly here.
    # OpenAI-specific params are appended to the URL as extra query parameters.
    oauth = OAuth2Client(client_id=CLIENT_ID, redirect_uri=REDIRECT_URI, scope=SCOPE)
    auth_url, state = oauth.create_authorization_url(
        AUTH_URL,
        code_challenge=code_challenge,
        code_challenge_method="S256",
        id_token_add_organizations="true",  # OpenAI-specific
        codex_cli_simplified_flow="true",   # OpenAI-specific
        originator="opencode",              # OpenAI-specific
    )

    # Verify state on callback to prevent CSRF (attacker-supplied auth codes).
    class CallbackHandler(BaseHTTPRequestHandler):
        def do_GET(self):
            parsed = urlparse(self.path)
            if parsed.path == "/auth/callback":
                params = parse_qs(parsed.query)
                received_state = params.get("state", [None])[0]

                error = None
                code = None
                if received_state != self.server.expected_state:
                    error = "CSRF error: state mismatch"
                elif "error" in params:
                    error = params.get("error_description", ["Unknown error"])[0]
                else:
                    code = params.get("code", [None])[0]

                self.server.auth_code = code
                self.server.error = error

                self.send_response(200)
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.end_headers()
                if self.server.auth_code:
                    html = "<html><body><h1>Authentication successful</h1><p>You can close this tab.</p></body></html>"
                else:
                    html = f"<html><body><h1>Authentication failed</h1><p>{self.server.error}</p></body></html>"
                self.wfile.write(html.encode())
                threading.Thread(target=self.server.shutdown).start()
            else:
                self.send_response(404)
                self.end_headers()

        def log_message(self, format, *args):
            pass  # suppress server logs

    server = HTTPServer(("localhost", 1455), CallbackHandler)
    server.auth_code      = None
    server.error          = None
    server.expected_state = state  # state returned by create_authorization_url()

    print("Opening browser for authentication...")
    print(f"If the browser does not open, visit the following URL:\n\n{auth_url}\n")
    webbrowser.open(auth_url)
    server.serve_forever()  # blocks until shutdown() is called from CallbackHandler

    if server.error:
        raise RuntimeError(f"Authentication error: {server.error}")

    print("Exchanging tokens...")
    # grant_type, client_id, and redirect_uri are added automatically by authlib;
    # code_verifier must be passed explicitly for PKCE verification.
    token = oauth.fetch_token(TOKEN_URL, code=server.auth_code, code_verifier=code_verifier)

    # AuthManager computes expiry, extracts account_id, and builds self.data internally.
    am = AuthManager(tokens=token)
    am._save()

    expires_dt = datetime.datetime.fromtimestamp(am.data["expires"] / 1000).strftime("%Y-%m-%d %H:%M:%S")

    print("\n=== Authentication Info ===")
    print(f"CODEX_ACCESS_TOKEN : {(am.data['access'] or '')[:40]}...")
    print(f"CODEX_ACCOUNT_ID   : {am.data.get('accountId')}")
    print(f"Expiry             : {expires_dt}")
    print("\nSaved to auth.json")


def cmd_test(auth, model):
    """WHAM Responses API requirements (differ from Chat Completions API):
      - content type must be "input_text" (not "text")
      - store=False is required (omitting it causes an error)
      - instructions (system prompt) is required

    WHAM is stateless: full conversation history must be included in the input array.
    For multi-turn sessions, pass prompt_cache_key=<uuid7> and
    extra_headers={"session_id": <uuid7>} to enable server-side caching and reduce TTFT.

    Reasoning summary (CoT) requires both effort and summary in reasoning={}.
    Streaming events for reasoning summary:
      - response.reasoning_summary_text.delta: incremental reasoning summary
      - response.reasoning_summary_text.done:  reasoning summary complete
    Note: reasoning.encrypted_content in include is for multi-turn context
    continuity, not for CoT display.
    """
    client = auth.make_client()
    in_reasoning = False
    with client.responses.create(
        model=model,
        instructions="You are a helpful coding assistant.",
        input=[{
            "role": "user",
            "content": [{"type": "input_text", "text": "What is your name?"}],
        }],
        store=False,
        reasoning={"effort": "medium", "summary": "auto"},
        include=[],
        tools=[],
        tool_choice="auto",
        parallel_tool_calls=True,
        stream=True,
    ) as stream:
        in_answer = False
        for event in stream:
            if event.type == "response.reasoning_summary_text.delta":
                if not in_reasoning:
                    print("[Thinking]", flush=True)
                    in_reasoning = True
                print(event.delta, end="", flush=True)
            elif event.type == "response.reasoning_summary_text.done":
                print()
                in_reasoning = False
            elif event.type == "response.output_text.delta":
                if not in_answer:
                    print("[Answer]", flush=True)
                    in_answer = True
                print(event.delta, end="", flush=True)
    print()


def cmd_list(auth):
    """WHAM /models differs from the standard API:
      response key is "models" (not "data"), model identifier is "slug" (not "id"),
      and client_version query parameter is required.

    Use with_raw_response because the non-standard schema cannot be parsed by the
    standard response object.
    """
    client = auth.make_client()
    response = client.models.with_raw_response.list(extra_query={"client_version": CLIENT_VER})
    data = json.loads(response.text)

    with open("models.json", "w") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)
    print("Saved to models.json")

    for model in data["models"]:
        print(model["slug"])


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)
    subparsers.add_parser("login")
    test_parser = subparsers.add_parser("test")
    test_parser.add_argument("-m", "--model", default="gpt-5.1-codex-mini")
    subparsers.add_parser("list")
    args = parser.parse_args(argv)

    if args.command == "login":
        cmd_login()
    else:
        auth = AuthManager()
        if args.command == "test":
            auth.auto_refresh(cmd_test, args.model)
        elif args.command == "list":
            auth.auto_refresh(cmd_list)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
