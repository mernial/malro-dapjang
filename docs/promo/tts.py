import asyncio, edge_tts, json, sys, os, ssl
import edge_tts.communicate as ec
ec._SSL_CTX = ssl.create_default_context(cafile="/root/.ccr/ca-bundle.crt")
TEXT = "어, 그 내일 모임 말이야, 허리가 좀 아파가지고 못 간다고 해 줘, 다음엔 꼭 간다고 하고. 보내 줘."
async def main(voice, out):
    c = edge_tts.Communicate(TEXT, voice, rate="+0%", boundary="WordBoundary", proxy=os.environ.get("HTTPS_PROXY") or os.environ.get("https_proxy"))
    words = []
    with open(out, "wb") as f:
        async for ch in c.stream():
            if ch["type"] == "audio": f.write(ch["data"])
            elif ch["type"] == "WordBoundary": words.append((ch["offset"]/1e7, ch["duration"]/1e7, ch["text"]))
    json.dump(words, open(out + ".json", "w"), ensure_ascii=False)
    print(voice, words[-1][0] + words[-1][1])
for v in ["ko-KR-InJoonNeural", "ko-KR-HyunsuMultilingualNeural", "ko-KR-SunHiNeural"]:
    asyncio.run(main(v, f"v_{v.split('-')[2]}.mp3"))
