package com.beichen.polaris.core.lifecycle

import android.content.Context
import android.content.Intent

/**
 * 「点通知回到 App」的启动意图（3.2）。
 *
 * 之前 AgentRunService / SurvivalCheckService / JobNotifier 各自
 * `Intent(this, MainActivity::class.java)`，只为这一个动作就把宿主 Activity 拖进 core：
 * core 从此离不开 app 壳，「换入口 Activity」也变成一次跨层改动。
 *
 * 改用包管理器的 launcher 意图，语义反而更准——它跟 manifest 里声明的入口天然一致，
 * 以后换 Activity 不需要回来改这三处。拿不到时（理论上不会，launcher 一定存在）
 * 退回 ACTION_MAIN + CATEGORY_LAUNCHER 显式指定包名，仍能解析到入口。
 */
internal fun launchAppIntent(context: Context): Intent =
    context.packageManager.getLaunchIntentForPackage(context.packageName)
        ?: Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setPackage(context.packageName)
        }

/** 通知点击用：叠上 NewTask + ClearTop，与原来 MainActivity 版本的行为一致。 */
internal fun launchAppIntentForNotification(context: Context): Intent =
    launchAppIntent(context).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
