plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

/**
 * 本地构建（无 GITHUB_RUN_NUMBER 环境变量）时的 versionCode 回退值。
 *
 * 它**不**保证能覆盖装机：线上版本号来自 CI run number，已经跑到 500 以上，这里的 14
 * 早就追不上了。真正的兜底在 defaultConfig 里（`-PversionCode` / `polaris.versionCode`）。
 * 保留这个常量只是为了让「完全没配置的本地构建」有一个确定、可复现的版本号，
 * 而不是 0 或环境相关的值。
 */
val LOCAL_FALLBACK_VERSION_CODE = 14

/*
 * ── 签名：私钥不在仓库里 ────────────────────────────────────────────────
 *
 * 修前这里是 `storeFile = file("debug.keystore")` + `storePassword = "android"`，
 * 而那个 keystore 是 git 跟踪的、仓库公开的。要害不是「debug 证书算不算秘密」
 * ——它本来就人人可生成、口令是安卓界公开约定——而是它被当**发布身份**在用：
 * CI 产出的每个包都由它签，于是任何拿到仓库的人都能签出「包名与证书都一致、
 * 被系统当作正常升级」的 APK，且覆盖安装会继承已授予的无障碍与通知监听权限。
 *
 * 现在私钥只存在两处：GitHub Actions Secrets（CI 构建时解到 runner 临时目录）
 * 和用户离线备份。仓库里只留「怎么用 secret 签名」的代码。
 *
 * 取值优先级：环境变量 > local.properties（该文件在 .gitignore 里，永不入库）。
 * 为什么还要承认 local.properties：`export` 的口令会留在 shell 历史里，也会挂在
 * `ps e` 的环境块上；仓库外的属性文件只有本机 gradle 进程读得到，泄露面小得多。
 *
 * 三种状态必须分清——把它们混成「找不到就用 debug 证书」正是「静默发错包」的来源：
 *   ① 一个都没配            → 回退 AGP 默认 debug 证书（本地调试，不报错）
 *   ② 配了一部分 / 路径写错  → 立刻失败，不替用户猜
 *   ③ 四个值齐全且文件可读   → 用发布证书签
 */
val localProps = java.util.Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}

fun signingValue(envKey: String, propKey: String): String? =
    System.getenv(envKey)?.trim()?.takeIf { it.isNotEmpty() }
        ?: localProps.getProperty(propKey)?.trim()?.takeIf { it.isNotEmpty() }

// `~` 不归 Gradle 展开，只有 shell 展开；属性文件里写 ~/x 很常见，这里补上。
val ksPath: String? = signingValue("POLARIS_KEYSTORE", "polaris.storeFile")?.let {
    if (it.startsWith("~/")) System.getProperty("user.home") + it.substring(1) else it
}
val ksStorePass: String? = signingValue("POLARIS_STORE_PASSWORD", "polaris.storePassword")
val ksAlias: String? = signingValue("POLARIS_KEY_ALIAS", "polaris.keyAlias")
val ksKeyPass: String? = signingValue("POLARIS_KEY_PASSWORD", "polaris.keyPassword") ?: ksStorePass
// 相对路径按**仓库根**解析：写 `polaris.storeFile=keys/x.jks` 的人不会去想 app/ 还是根目录。
val releaseKeyFile = ksPath?.let { rootProject.file(it) }
val configuredAny = !ksPath.isNullOrBlank() || !ksStorePass.isNullOrBlank() || !ksAlias.isNullOrBlank()
val hasReleaseKey = !ksPath.isNullOrBlank() && releaseKeyFile?.isFile == true &&
    !ksStorePass.isNullOrBlank() && !ksAlias.isNullOrBlank()

// ② 配了却不可用 → 红。静默回退会产出一个「能装、但装上去就再也升不了级」的包，
// 这种包只有到用户下次点更新时才会暴露，代价是卸载重装 + 权限全丢。
if (configuredAny && !hasReleaseKey) {
    val missing = buildList {
        if (ksPath.isNullOrBlank()) add("POLARIS_KEYSTORE / polaris.storeFile")
        else if (releaseKeyFile?.isFile != true) add("keystore 文件不存在：${releaseKeyFile?.absolutePath}")
        if (ksStorePass.isNullOrBlank()) add("POLARIS_STORE_PASSWORD / polaris.storePassword")
        if (ksAlias.isNullOrBlank()) add("POLARIS_KEY_ALIAS / polaris.keyAlias")
    }
    throw GradleException(
        "签名配置不完整，拒绝静默回退到 debug 证书：${missing.joinToString("；")}。\n" +
            "要么四项都配（环境变量或仓库根 local.properties 的 polaris.* 键），" +
            "要么四项都不配（本地调试包，不能覆盖升级已发布的包）。"
    )
}

if (!hasReleaseKey && System.getenv("POLARIS_REQUIRE_RELEASE_KEY") == "1") {
    throw GradleException(
        "未找到发布签名：POLARIS_KEYSTORE=${ksPath ?: "（未设置）"}，" +
            "storePassword=${if (ksStorePass.isNullOrBlank()) "空" else "已设置"}，" +
            "alias=${ksAlias ?: "（未设置）"}。" +
            "CI 上出现这个错误代表 Actions Secrets 丢了（POLARIS_KEYSTORE_B64 / " +
            "POLARIS_STORE_PASSWORD / POLARIS_KEY_ALIAS / POLARIS_KEY_PASSWORD）；" +
            "本地构建不要设置 POLARIS_REQUIRE_RELEASE_KEY。"
    )
}

android {
    namespace = "com.beichen.polaris"
    compileSdk = 34

    signingConfigs {
        if (hasReleaseKey) {
            create("polaris") {
                storeFile = releaseKeyFile
                storePassword = ksStorePass
                keyAlias = ksAlias
                keyPassword = ksKeyPass
            }
        }
    }

    // 仅本地 UI 验证用：`-PnoNative=true` 跳过 llama.cpp/终端 JNI 的 NDK 编译。
    // 产出的 APK 没有 .so，本地推理与内置终端不可用（两处都是懒加载 + try/catch 降级），
    // 其余界面/流程与正式包一致——用来在 x86_64 模拟器上跑 UI 验证，CI 与正式包不受影响。
    val skipNative = (project.findProperty("noNative") as String?)?.toBoolean() == true

    defaultConfig {
        applicationId = "com.beichen.polaris"
        minSdk = 26
        // 28 而不是 34：Android 10+ 对 targetSdk>=29 的应用禁止从**应用数据目录**
        // 调用 execve（W^X）。容器里的所有程序（busybox、之后装的 node/python）
        // 都落在 filesDir，因此 proot 一旦进 guest 就必然失败——
        // 表现为 error=13 / execve("/bin/sh")，且无法通过改代码绕开。
        // Termux 至今保持 targetSdk 28，正是同一原因。
        // 代价：存储/通知/包可见性回退到旧模式（本项目不依赖 29+ 的新约束）。
        targetSdk = 28
        // 版本号每次构建自增：CI 上用 GITHUB_RUN_NUMBER（每次运行单调递增），
        // 本地构建回退到 LOCAL_FALLBACK_VERSION_CODE 常量。
        // 本地回退值（14）远小于 CI 已经发出去的版本（1.587 → 587），直接装会被系统
        // 以「版本回退」拒绝。所以本地出包要自己抬一次：`-PversionCode=600`，或
        // local.properties 里 `polaris.versionCode=600`。CI 上 run number 单调递增，
        // 这两个入口只在本地生效，不会污染线上版本序列。
        val localVersionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull()
            ?: localProps.getProperty("polaris.versionCode")?.toIntOrNull()
        val ciVersionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: LOCAL_FALLBACK_VERSION_CODE
        // maxOf 兜底，确保 versionCode 永不小于历史值，否则 Android 拒绝覆盖安装。
        versionCode = maxOf(maxOf(ciVersionCode, LOCAL_FALLBACK_VERSION_CODE), localVersionCode ?: 0)
        versionName = "1.$versionCode"
        // 本地 llama.cpp 推理只编 arm64-v8a：先覆盖全部真机，模拟器 x86_64 后续再补
        if (!skipNative) {
            ndk {
                abiFilters += "arm64-v8a"
            }
        }
    }

    buildTypes {
        getByName("debug") {
            // 本项目只产出 debug 变体，所以「发布签名」就挂在它上面：
            // 同一签名是覆盖升级的唯一前提，变体名与是否发布无关。
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("polaris")
            } else {
                // 回退 AGP 默认的 debug 签名（~/.android/debug.keystore，自动生成）。
                // 不要写死到仓库里的某个文件——那正是这次要摆脱的东西。
                signingConfigs.getByName("debug")
            }
        }
        // 不要动 release（本项目不产出 release 包）
    }

    // 仅本地/模拟器调试：`-Px86Rootfs=true` 时额外打进 x86_64 的 Alpine rootfs。
    // 为什么需要它：内置 rootfs 是 aarch64，x86_64 模拟器上 proot 起不来，
    // 容器相关链路（装运行时、跑命令）在服务器上就一直验证不了。
    // 该开关只在本地构建时用，CI 与正式包不带，避免白涨 ~8MB。
    if (project.hasProperty("x86Rootfs")) {
        sourceSets.getByName("debug") {
            assets.srcDir("src/debugX86/assets")
            jniLibs.srcDir("src/debugX86/jniLibs")
        }
    }

    // W-37：Shizuku UserService 走 AIDL 跨进程接口（app/src/main/aidl），显式开启
    buildFeatures {
        compose = true
        aidl = true
    }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.15" }

    // proot 以 libproot.so 的形式打进 jniLibs，运行时从 nativeLibraryDir 执行。
    // 必须开 useLegacyPackaging：默认（minSdk>=23）native 库**不解压**，直接从 APK 里 mmap，
    // 那样就没有可 execve 的文件路径，proot 会报 error=13。
    packaging {
        jniLibs {
            useLegacyPackaging = true
            // `-PnoNative` 还要把 src/main/jniLibs 里预置的 .so（proot / busybox 等，
            // 都是 arm64）一起排掉。
            //
            // 只跳过 CMake 是不够的：那些 so 是仓库里的成品，不经过 externalNativeBuild，
            // 照样会打进 lib/arm64-v8a/，于是 APK 仍声明 arm64 —— 装到 x86_64 模拟器上
            // 直接 INSTALL_FAILED_NO_MATCHING_ABIS。上一轮我是手工「解包 → 删 lib/
            // → zipalign → 重签」才装进去的，这套动作本来就该由构建脚本负责。
            //
            // 排掉之后 APK 没有 lib/ 目录，任何 ABI 都能装，这正是 noNative 的唯一用途。
            if (skipNative) excludes += "**/*.so"
        }
    }

    // 本地 llama.cpp 推理：只出 arm64-v8a（市面手机全覆盖，先做扎实再扩 x86_64 模拟器）
    if (!skipNative) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
                // 注意：这里**不能**写 arguments —— 顶层 android.externalNativeBuild
                // 的 Cmake DSL 在 AGP 8.5 里没有暴露这个属性（编译报 Unresolved reference）。
                // AGP 在 debug 构建里会传 -DCMAKE_BUILD_TYPE=Debug，而 CMake 的 Debug
                // flags 只有 -g（即 -O0），ggml 在 -O0 下 1 个 token 要 24 秒。
                // 这件事统一由 CMakeLists.txt 里三层 FORCE 处理（见那里注释）。
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // 内核模块：Agent 循环 / 工具协议 / 会话存储 / 终端 / 模型客户端
    implementation(project(":core"))
    // W-13 B 方案：Shizuku 提权通道（用户需安装 Shizuku 并授权一次）
    implementation("dev.rikka.shizuku:api:13.1.5")
    // W-17：定时任务调度（WorkManager 周期最长 15 分钟，与我们的最小间隔一致）
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

    // 与 :core 共用的五个依赖走版本目录，避免两处各写一份版本号
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    // Skill 脚本执行引擎（纯 Java，无需 NDK）；将来要换 QuickJS 只需替换 JsEngine 实现
    implementation(libs.rhino.runtime)
    implementation(libs.androidx.core.ktx)
    // W-24：离线 OCR（中文 + 英文混排，模型打进 APK，不依赖 Google Play 服务/GMS）
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
}
