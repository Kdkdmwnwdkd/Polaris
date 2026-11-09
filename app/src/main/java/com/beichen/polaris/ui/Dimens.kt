package com.beichen.polaris.ui

import androidx.compose.ui.unit.dp

/**
 * 间距 token（视觉规范 v3.1 §5.1）：RikkaHub 式大留白。
 *
 * space5=20dp 是卡片内边距与页面左右边距的默认值（不做 clamp——少一个变量少一处不一致）；
 * space12=48dp 是触控目标基准；顶栏 56dp / 抽屉项 52dp / 输入栏内容高 68dp 为规范明文尺寸。
 */
internal object AgentSpacing {
    val space1 = 4.dp
    val space2 = 8.dp
    val space3 = 12.dp
    val space4 = 16.dp
    val space5 = 20.dp
    val space6 = 24.dp
    val space8 = 32.dp
    val space10 = 40.dp
    val space12 = 48.dp
}

/** 规范 §5.4 触控与栏高基准：容器一律 heightIn(min) 不用固定 height，防 fontScale 裁切。 */
internal object AgentDimens {
    /**
     * 顶栏内容高（另加 statusBarsPadding）。
     *
     * v5：56 → 50。标题下沉为页面内的衬线大标题后，顶栏只剩「抽屉按钮 + 位置条 +
     * 一个动作」三样，56dp 的空高会让它看起来像一条没填满的横梁。
     */
    val topBarHeight = 50.dp
    /** 抽屉菜单项高（无描述行的单行列表项）。 */
    val drawerItemHeight = 52.dp
    /** 对话输入栏内容高（输入框 48 + 上下 8 + 底 4）。 */
    val inputBarContentHeight = 68.dp
    /** 触控目标基准 48×48dp。 */
    val touchTarget = 48.dp
    /** 顶栏圆形按钮视觉尺寸（触控 48）。 */
    val topBarButtonVisual = 40.dp
}
