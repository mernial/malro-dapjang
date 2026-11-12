"""내부 테스트에 올라간 가장 최근 버전을 비공개 테스트 트랙에도 올림.
하루 한 번(play-closed.yml)만 돌림: 플레이는 앱마다 하루 저장 횟수가 정해져 있어,
합칠 때마다 올리면 하루에 여러 번 합친 날 막혔음 (2026-11-12 'Daily save quota exceeded').
APP_VERSION 이 없으면 내부 테스트의 가장 높은 버전을 씀. 이미 비공개 트랙에 있으면 저장하지 않음.
같은 파일을 다시 올리지 않고, 이미 올라간 버전 번호를 비공개 트랙에 지정만 함.
비공개 트랙 이름: CLOSED_TRACK 환경 변수 → 'alpha'(기본 비공개 트랙) → 내부·공개·프로덕션이 아닌 첫 트랙.
"""
import os, sys, json
from google.oauth2 import service_account
from google.auth.transport.requests import AuthorizedSession

PKG = "kr.yunho.talkhelper"
creds = service_account.Credentials.from_service_account_file(
    os.environ["SA_PATH"], scopes=["https://www.googleapis.com/auth/androidpublisher"])
s = AuthorizedSession(creds)
base = f"https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{PKG}/edits"

def ok(r):
    if r.status_code >= 300:
        # 오류 글에 서비스 계정 주소가 섞이면 GitHub 이 통째로 가리므로 메시지만, 주소는 지워서
        try: msg = r.json().get("error", {}).get("message", "")
        except Exception: msg = r.text[:300]
        print("오류", r.status_code, msg.replace(creds.service_account_email, "(서비스 계정)")); sys.exit(1)
    return r.json() if r.text else {}

edit = ok(s.post(base))["id"]
tracks = ok(s.get(f"{base}/{edit}/tracks")).get("tracks", [])
names = [t["track"] for t in tracks]
for t in tracks:
    print("트랙", t["track"], [(r.get("status"), r.get("versionCodes")) for r in t.get("releases", [])])
want = os.environ.get("CLOSED_TRACK", "").strip()
if not want:
    # 테스터가 받고 있는(버전이 올라가 있는) 비공개 트랙. 없으면 alpha
    closed = [t for t in tracks if t["track"] not in ("internal", "beta", "production") and t.get("releases")]
    want = closed[0]["track"] if closed else "alpha"
version = os.environ.get("APP_VERSION", "").strip()
if not version:
    codes = [int(c) for t in tracks if t["track"] == "internal" for r in t.get("releases", []) for c in r.get("versionCodes", [])]
    if not codes:
        print("내부 테스트에 버전이 없어요"); s.delete(f"{base}/{edit}"); sys.exit(0)
    version = str(max(codes))
have = [c for t in tracks if t["track"] == want for r in t.get("releases", []) for c in r.get("versionCodes", [])]
if version in have or (have and max(int(c) for c in have) >= int(version)):
    # 저장(commit)하지 않고 편집을 버림 → 하루 횟수를 쓰지 않음
    print("비공개 트랙", want, "에 이미", have, "→ 올릴 것 없음"); s.delete(f"{base}/{edit}"); sys.exit(0)
print("비공개 트랙:", want, "· 버전:", version)
body = {"track": want, "releases": [{
    "versionCodes": [version],
    "status": "completed",
    "releaseNotes": [{"language": "ko-KR", "text": "더 좋아진 기능과 고친 점이 들어 있어요."}],
}]}
ok(s.put(f"{base}/{edit}/tracks/{want}", data=json.dumps(body), headers={"content-type": "application/json"}))
ok(s.post(f"{base}/{edit}:commit"))
print("비공개 테스트에 올림 (구글 검토 뒤 테스터에게 자동 업데이트)")
