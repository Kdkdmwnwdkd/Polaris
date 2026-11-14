package com.beichen.debug.tts;

import android.app.Activity;
import android.os.Bundle;

/**
 * 只为把包从 stopped 状态里捞出来。
 *
 * Android 会拒绝启动处于 stopped 状态的包里的服务（"app is in background uid null"），
 * 而新装的包默认 stopped=true，于是 TextToSpeech 的 bindService 一直失败、
 * 回调直接 ERROR —— 表现为「系统 TTS 未就绪」，且没有任何一条日志说明原因。
 * 用 monkey / am start 拉起一次这个空 Activity，stopped 就清了。
 */
public class DummyActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        finish();
    }
}
