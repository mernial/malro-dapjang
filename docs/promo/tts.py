import asyncio, edge_tts, json, sys, os, ssl
import edge_tts.communicate as ec
ec._SSL_CTX = ssl.create_default_context(cafile="/root/.ccr/ca-bundle.crt")
TEXT = __import__("os").environ.get("TTS_TEXT")
async def main(voice, out):
    c = edge_tts.Communicate(TEXT, voice, rate="+0%", boundary="WordBoundary", proxy=os.environ.get("HTTPS_PROXY") or os.environ.get("https_proxy"))
    words = []
    with open(out, "wb") as f:
        async for ch in c.stream():
            if ch["type"] == "audio": f.write(ch["data"])
            elif ch["type"] == "WordBoundary": words.append((ch["offset"]/1e7, ch["duration"]/1e7, ch["text"]))
    json.dump(words, open(out + ".json", "w"), ensure_ascii=False)
    print(voice, words[-1][0] + words[-1][1])
for v in ["ko-KR-InJoonNeural"]:
    asyncio.run(main(v, __import__("os").environ["TTS_OUT"]))
