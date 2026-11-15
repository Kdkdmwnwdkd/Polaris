#!/usr/bin/env python3
"""把两套本地合成引擎需要的东西准备好：

  1. `app/src/main/jniLibs/{arm64-v8a,x86_64}/` —— 原生推理运行时（sherpa-onnx 官方 android 包）
  2. `app/src/main/assets/tts/matcha/` —— matcha-icefall-zh-baker（内置音色，约 125MB 含声码器）
  3. `app/src/main/assets/tts/zipvoice/` —— ZipVoice distill int8（零样本克隆，约 155MB 含声码器）

后两套是**两个独立的模型**，不能互相替代：matcha 出一个固定的女声，ZipVoice 必须
带着用户的参考音频才能出声。

## 为什么是脚本而不是把二进制提交进仓库

72MB 模型 + 34MB 运行时放进 git，会让每次 clone 都拖一份二进制、每次 diff 都可能是
「这个 onnx 变了」。而它们都是**可复现的第三方产物**：版本号写在这里，谁都能重下。
仓库只存「怎么拿到」，不存「拿到的东西」。

## 为什么自己拼 AAR 而不是直接用官方 tar

官方 `sherpa-onnx-vX.Y.Z-android.tar.bz2` 里是 4 个 ABI 的 .so + 示例工程 + 文档。
装到手机上只需要 arm64-v8a（x86_64 留给模拟器），armeabi-v7a 会让 APK 白胖 3MB，
其余是噪音。所以这里解包后重新打包成最小 AAR。

## 幂等

已存在且大小正确的文件一律跳过；下载走 8 路 Range 分段，断了重跑会续上。
"""
import bz2
import os
import shutil
import sys
import tarfile
import threading
import time

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CACHE = os.path.join(ROOT, ".cache", "tts")
ASSET_DIR = os.path.join(ROOT, "app", "src", "main", "assets", "tts", "matcha")

# 1.13.8 起 Kotlin API 才有 ZipVoice 与 GenerationConfig.referenceAudio。
# 原生 .so 必须与 Kotlin 源码同版本：跨版本混用会在 build() 时报
# 「Invalid OfflineTtsConfig」，而看不出少的是哪个字段。
SHERPA_VERSION = "1.13.8"
ANDROID_TAR = f"https://github.com/k2-fsa/sherpa-onnx/releases/download/v{SHERPA_VERSION}/sherpa-onnx-v{SHERPA_VERSION}-android.tar.bz2"
MODEL_TAR = "https://api.github.com/repos/k2-fsa/sherpa-onnx/releases/assets/738441325"
# 声码器不在模型包里：matcha 只给声学模型，波形要 vocos 从梅尔谱还原。
# 少了它，引擎会在初始化时报「vocoder 文件不存在」，而那时代码看起来一切正常。
VOCODER_URL = "https://api.github.com/repos/k2-fsa/sherpa-onnx/releases/assets/238058578"
VOCODER_NAME = "vocos-22khz-univ.onnx"
VOCODER_SIZE = 53_884_024
MODEL_NAME = "matcha-icefall-zh-baker"

# 克隆模型的声道与声码器：ZipVoice 原生 24kHz，声码器必须配套，
# 换成 22kHz 那个（matcha 用的）会在初始化时报 vocoder 输出维度不符。
ZIPVOICE_TAR = "https://api.github.com/repos/k2-fsa/sherpa-onnx/releases/assets/327320447"
ZIPVOICE_DIR = os.path.join(ROOT, "app", "src", "main", "assets", "tts", "zipvoice")
ZIPVOICE_VOCODER_URL = "https://api.github.com/repos/k2-fsa/sherpa-onnx/releases/assets/284752173"
ZIPVOICE_VOCODER_NAME = "vocos_24khz.onnx"
ZIPVOICE_VOCODER_SIZE = 54_157_409

PARTS = 8
ABIS = ("arm64-v8a", "x86_64")


def log(*a):
    print(*a, flush=True)


def fetch(url, out, expected, token=None):
    """分段并行下载；断点续传；返回是否完整。"""
    if os.path.exists(out) and os.path.getsize(out) == expected:
        log(f"已存在 {os.path.basename(out)}（{expected} 字节），跳过")
        return True
    headers = {"Accept": "application/octet-stream"}
    if token and "api.github.com" in url:
        headers["Authorization"] = f"Bearer {token}"
    step = expected // PARTS
    threads = []

    def worker(idx):
        part = f"{out}.p{idx}"
        start = idx * step
        end = expected - 1 if idx == PARTS - 1 else (idx + 1) * step - 1
        while True:
            have = os.path.getsize(part) if os.path.exists(part) else 0
            if have >= end - start + 1:
                return
            h = dict(headers)
            h["Range"] = f"bytes={start + have}-{end}"
            try:
                import requests
                with requests.get(url, headers=h, stream=True, timeout=45, allow_redirects=True) as r:
                    if r.status_code not in (200, 206):
                        time.sleep(2)
                        continue
                    with open(part, "ab") as f:
                        for chunk in r.iter_content(131072):
                            if chunk:
                                f.write(chunk)
            except Exception as e:
                log(f"  {os.path.basename(part)} 重试：{type(e).__name__}")
                time.sleep(1)

    for i in range(PARTS):
        t = threading.Thread(target=worker, args=(i,), daemon=True)
        t.start()
        threads.append(t)
    last = 0
    while any(t.is_alive() for t in threads):
        time.sleep(10)
        done = sum(os.path.getsize(f"{out}.p{i}") for i in range(PARTS) if os.path.exists(f"{out}.p{i}"))
        if done - last > 2 * 1048576:
            last = done
            log(f"  {os.path.basename(out)} {done // 1048576}/{expected // 1048576}MB")
    with open(out, "wb") as f:
        for i in range(PARTS):
            p = f"{out}.p{i}"
            with open(p, "rb") as src:
                shutil.copyfileobj(src, f)
            os.remove(p)
    ok = os.path.getsize(out) == expected
    log(f"{os.path.basename(out)} 完成：{os.path.getsize(out)} / {expected}")
    return ok


def install_runtime(tarball):
    """把 .so 放进 app/src/main/jniLibs/<abi>/。

    不打包成 AAR：官方只提供 `kotlin-api/*.kt` 源码（`Tts.kt` 已按 Apache-2.0
    收进仓库，见 THIRD_PARTY_NOTICES），原生侧就是一个 jni 库。
    走 jniLibs 直挂，比自造 AAR 少一层「这个 AAR 是谁打的」的疑问。
    """
    dest_root = os.path.join(ROOT, "app", "src", "main", "jniLibs")
    installed = []
    with tarfile.open(tarball, "r:bz2") as tar:
        for m in tar.getmembers():
            if not m.isfile() or not m.name.lower().endswith(".so"):
                continue
            abi = next((a for a in ABIS if f"/{a}/" in m.name), None)
            if abi is None:
                continue
            dest = os.path.join(dest_root, abi, os.path.basename(m.name))
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            with tar.extractfile(m) as src, open(dest, "wb") as dst:
                shutil.copyfileobj(src, dst)
            installed.append(f"{abi}/{os.path.basename(m.name)}")
    if not installed:
        log("!! 包里没找到 jni 的 .so —— 版本号可能写错了")
        return False
    log("运行时安装：" + "、".join(installed))
    return True


def extract_zipvoice(tarball):
    """展开克隆模型到 assets/tts/zipvoice（含 espeak-ng-data 目录）。"""
    marker = os.path.join(ZIPVOICE_DIR, "encoder.int8.onnx")
    if os.path.exists(marker):
        log("克隆模型已就位，跳过")
        return True
    os.makedirs(ZIPVOICE_DIR, exist_ok=True)
    with tarfile.open(tarball, "r:bz2") as tar:
        members = [m for m in tar.getmembers() if m.isfile()]
        tops = {m.name.split("/")[0] for m in members if "/" in m.name}
        prefix = next(iter(tops)) if len(tops) == 1 else None
        for m in members:
            rel = m.name[len(prefix) + 1:] if prefix and m.name.startswith(prefix + "/") else m.name
            if not rel or rel.startswith("."):
                continue
            # 参考样本不进包：test_wavs 是别人（雷军）的声音，随包分发不合适，
            # 而且用户自己会录。
            if rel.startswith("test_wavs/"):
                continue
            target = os.path.join(ZIPVOICE_DIR, rel)
            os.makedirs(os.path.dirname(target), exist_ok=True)
            with tar.extractfile(m) as src, open(target, "wb") as dst:
                shutil.copyfileobj(src, dst)
    files = sum(len(f) for _, _, f in os.walk(ZIPVOICE_DIR))
    size = sum(os.path.getsize(os.path.join(r, f)) for r, _, fs in os.walk(ZIPVOICE_DIR) for f in fs)
    log(f"克隆模型展开：{files} 个文件 / {size // 1048576}MB → {ZIPVOICE_DIR}")
    return True


def extract_model(tarball):
    if os.path.exists(os.path.join(ASSET_DIR, "model-steps-3.onnx")):
        log("模型已就位，跳过")
        return True
    os.makedirs(ASSET_DIR, exist_ok=True)
    with tarfile.open(tarball, "r:bz2") as tar:
        members = [m for m in tar.getmembers() if m.isfile()]
        # 官方包顶层目录名可能带版本前缀，剥掉它，保持 assets 内路径稳定
        prefix = None
        for m in members:
            parts = m.name.split("/")
            if len(parts) > 1 and parts[0].startswith(MODEL_NAME.split("-")[0]):
                prefix = parts[0]
                break
        for m in members:
            rel = m.name[len(prefix) + 1:] if prefix and m.name.startswith(prefix + "/") else m.name
            if not rel or rel.startswith("."):
                continue
            target = os.path.join(ASSET_DIR, rel)
            os.makedirs(os.path.dirname(target), exist_ok=True)
            with tar.extractfile(m) as src, open(target, "wb") as dst:
                shutil.copyfileobj(src, dst)
    files = sum(len(f) for _, _, f in os.walk(ASSET_DIR))
    log(f"模型展开：{files} 个文件 → {ASSET_DIR}")
    return True


def main():
    os.makedirs(CACHE, exist_ok=True)
    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    android_tar = os.path.join(CACHE, "sherpa-android.tar.bz2")
    model_tar = os.path.join(CACHE, "matcha.tar.bz2")

    if not fetch(ANDROID_TAR, android_tar, 46_093_321):
        log("Android 包下载失败")
        return 1
    if not fetch(MODEL_TAR, model_tar, 75_455_267, token):
        log("模型下载失败")
        return 1

    vocoder = os.path.join(CACHE, VOCODER_NAME)
    if not fetch(VOCODER_URL, vocoder, VOCODER_SIZE, token):
        log("声码器下载失败")
        return 1

    zip_tar = os.path.join(CACHE, "zipvoice.tar.bz2")
    if not fetch(ZIPVOICE_TAR, zip_tar, 109_162_785, token):
        log("克隆模型下载失败")
        return 1
    zip_vocoder = os.path.join(CACHE, ZIPVOICE_VOCODER_NAME)
    if not fetch(ZIPVOICE_VOCODER_URL, zip_vocoder, ZIPVOICE_VOCODER_SIZE, token):
        log("克隆声码器下载失败")
        return 1

    ok = install_runtime(android_tar)
    extract_model(model_tar)
    target = os.path.join(ASSET_DIR, VOCODER_NAME)
    shutil.copyfile(vocoder, target)
    log(f"声码器就位：{target}（{os.path.getsize(target) // 1048576}MB）")

    extract_zipvoice(zip_tar)
    zip_target = os.path.join(ZIPVOICE_DIR, ZIPVOICE_VOCODER_NAME)
    shutil.copyfile(zip_vocoder, zip_target)
    log(f"克隆声码器就位：{zip_target}（{os.path.getsize(zip_target) // 1048576}MB）")
    log("完成。" if ok else "运行时未装上，见上。")
    return 0 if ok else 2


if __name__ == "__main__":
    sys.exit(main())
