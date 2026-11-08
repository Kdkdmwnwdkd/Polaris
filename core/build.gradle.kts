plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

/**
 * `:core` —— 与界面无关的全部内核：Agent 循环、工具协议、会话存储、容器/终端、
 * 模型客户端与本地推理、技能执行。
 *
 * 为什么是这三个包（core / skill / terminal）而不是只拆 core：
 *   core → skill（ModelConfig / CapabilityRegistry）、core → terminal（TerminalSession）
 *   与 skill/terminal → core（AgentLogger / ToolRegistry）是三向双向引用，
 *   拆开任何一边都是循环依赖。它们本来就是一个整体，一起搬才成立。
 *
 * 为什么值得拆：arch guard 已经证明 core 不 import ui/tools（那条规则从 3.1 起
 * 一直在守），把它变成模块边界后，这条约束从「文档约定 + 文本检查」变成编译器强制。
 * 顺带把 174 个文件从 app 的编译单元里移出去，改 UI 不再触发内核重编。
 *
 * 依赖用 api 而不是 implementation：core 的公共签名里出现了 okhttp Response、
 * kotlinx.serialization 的 @Serializable 类型，app 侧调用时要能解析这些类型。
 */
android {
    namespace = "com.beichen.polaris.core"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }

    // 本模块没有资源、没有 BuildConfig 消费方（core/build/BuildModels.kt 只是名字像）
    buildFeatures {
        buildConfig = false
        compose = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // 五个共用依赖的版本号在 gradle/libs.versions.toml，与 :app 同源。
    // NotificationCompat / NotificationManagerCompat / FileProvider / getSystemService
    api(libs.androidx.core.ktx)
    api(libs.kotlinx.coroutines.android)
    api(libs.kotlinx.serialization.json)
    // 模型客户端（OpenAI 兼容 / GitHub 构建服务）全部走 OkHttp
    api(libs.okhttp)
    // 技能脚本执行引擎（skill/SkillExecutor：ClassShutter + 指令计数熔断）。
    // 纯 Java，无需 NDK；换 QuickJS 时只替换 JsEngine 实现。
    api(libs.rhino.runtime)
}
