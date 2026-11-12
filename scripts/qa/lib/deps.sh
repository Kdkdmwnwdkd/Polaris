#!/usr/bin/env bash
# scripts/qa/lib/deps.sh —— harness 依赖构件解析（W-40）
#
# 背景：早批次脚本把 Gradle 缓存路径**连哈希一起写死**（例如
# `.../kotlin-compiler-embeddable/1.9.25/6e698f23ca.../xxx.jar`）。Gradle 版本一升、
# 缓存一清，整片脚本就报「找不到构件」，看起来像代码坏了，实际只是路径过期。
#
# 本文件只做一件事：按「组织路径 + 构件名通配」去找 jar，找不到就回落到本机 kotlinc 发行版。
# 用法（脚本里 source 之后直接调用 qa_find_jar）：
#
#   source "$ROOT/scripts/qa/lib/deps.sh"
#   KOTLINC=$(qa_find_jar "$CACHE/org.jetbrains.kotlin/kotlin-compiler-embeddable" "kotlin-compiler-embeddable-*.jar")
#   [ -n "$KOTLINC" ] || KOTLINC="$KC_HOME/lib/kotlin-compiler.jar"
#
# 约定：解析不到时返回空串，由调用方决定回落还是跳过——**绝不返回一个假路径假装存在**。

# Gradle 缓存根：优先 GRADLE_USER_HOME（CI 上是 /home/runner/.gradle），
# 其次 ~/.gradle，最后才回落到 /root/.gradle（沙箱）。
# 之前写死 /root/.gradle，导致 CI 里所有构件都解析不到、整片假红。
if [ -z "${QA_GRADLE_CACHE:-}" ]; then
    QA_GRADLE_CACHE="${AGENTKIT_GRADLE_CACHE:-}"
fi
if [ -z "${QA_GRADLE_CACHE:-}" ]; then
    _cand="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"
    if [ -d "$_cand" ]; then
        QA_GRADLE_CACHE="$_cand"
    elif [ -d /root/.gradle/caches/modules-2/files-2.1 ]; then
        QA_GRADLE_CACHE=/root/.gradle/caches/modules-2/files-2.1
    else
        QA_GRADLE_CACHE="$_cand"
    fi
fi
export QA_GRADLE_CACHE
KC_HOME=${KOTLINC_HOME:-/tmp/kotlinc}

# qa_find_jar <组织路径目录> <构件通配>
# 输出：首个匹配的 jar 绝对路径；找不到输出空串（调用方可用 [ -n ] 判断）
qa_find_jar() {
    local dir="$1" pattern="$2"
    [ -d "$dir" ] || return 0
    # W-46：按**版本序**取最新（sort -V），不能用字典序 head -1。
    # 缓存里同时存在 1.5.0 与 1.8.1 时，字典序会把 1.5.0 排在前面，harness 于是用旧
    # coroutines 编译/运行——`CoroutineDispatcher.limitedParallelism` 在 1.6 才引入，
    # 结果整片报 NoSuchMethodError、看起来像产品回归（本次实测 9/9 全红，实际一行代码没坏）。
    find "$dir" -type f -name "$pattern" 2>/dev/null | grep -v -- '-sources' | sort -V | tail -1
}

# qa_find_jar_or_local <组织路径目录> <构件通配> <KC_HOME/lib 下的文件名>
#
# 与 qa_find_jar 的区别：**保证给出一条存在的路径**（或空串）。很多工件（trove4j、
# annotations）在本机 kotlinc 发行版里就有同份拷贝，缓存没命中时没必要 SKIP。
#
# 为什么需要这个函数：`qa_find_jar` 的约定是「找不到返回空，由调用方决定」，
# 而调用方常常**忘了判断**。2026-11-05 就踩了一次——沙箱里刚建了 Gradle 缓存目录
# （只为放一个 coroutines jar），于是 journal harness 从「回落 kotlinc」变成「拿到空串」，
# 编译器以 `ClassNotFoundException: gnu.trove.THashMap` 崩掉，看起来像代码坏了。
# 这种「缓存部分就绪」比「缓存完全为空」更常见，必须有统一出口。
qa_find_jar_or_local() {
    local dir="$1" pattern="$2" local_name="$3" hit
    hit=$(qa_find_jar "$dir" "$pattern")
    if [ -n "$hit" ]; then
        printf '%s\n' "$hit"
    elif [ -f "$KC_HOME/lib/$local_name" ]; then
        printf '%s\n' "$KC_HOME/lib/$local_name"
    fi
}

# qa_require_project <仓库根>：确认关键源目录存在（脚本从任意 cwd 调用都不会跑错地方）
qa_require_project() {
    local root="$1"
    [ -d "$root/app/src/main/java" ] || {
        echo "FATAL: $root 不像 AgentKit 仓库根目录（缺 app/src/main/java）" >&2
        exit 2
    }
}

# qa_classpath <仓库根>：拼接所有产出 Kotlin class 的模块目录（冒号分隔，跳过不存在的）。
#
# 拆模块后（:app + :core），只用 app 的 classes 会让 harness 找不到 core 里的类，
# 表现是整片 `unresolved reference: AgentLogger` —— 看起来像代码坏了，实际是 classpath 少一段。
#
# 与 `-Xfriend-paths` 的分隔符不同：**classpath 用冒号，friend-paths 用逗号**（Kotlin CLI 约定）。
# 拆模块后 CLASSES 变成 "app:core"，直接塞给 -Xfriend-paths 会被当成一个不存在的路径，
# 表现为「internal 符号全部不可见」（连 app 自己的 internal 也报）——本轮踩过一次。
qa_classpath() {
    local root="$1" v m d out
    # 变体优先 debug（本地开发的默认产物），整组缺失才回落到 release。
    #
    # 为什么需要回落：CI 从 2026-11-12 起只产 release 包（发布包不能是 debuggable，
    # 见 app/build.gradle.kts）。若 harness 只认 debug，一次 release 构建会让
    # 四十多个 runner 全部 SKIP——门禁不报红，但验证面凭空消失，比报红更危险。
    #
    # 为什么「整组」而不是逐模块补：两个变体同时存在时逐模块补会把同名类
    # 在 classpath 上放两遍（app:debug + app:release），编译虽容忍，但
    # qa_classes_stale 的「最新 class」判定会跨变体串味，误报产物陈旧。
    for v in debug release; do
        out=""
        for m in app core; do
            d="$root/$m/build/tmp/kotlin-classes/$v"
            [ -d "$d" ] && out="${out:+$out:}$d"
        done
        if [ -n "$out" ]; then printf '%s' "$out"; return; fi
    done
    printf ''
}

# qa_classes_stale <仓库根> [class 目录]
#
# 输出：陈旧时打印**那个比所有 class 都新的源文件**；新鲜、或产物根本不存在时输出空串。
#
# 背景：harness 拿 kotlin-classes 当 classpath。源码改了没重编时，它们会报
# `unresolved reference: Xxx`——看着像代码写错，实际是产物过期（2026-11-04 误诊过一次）。
#
# 判据：逐模块取最新 .class 的时间戳，找该模块 src/main 里有没有 .kt 比它新。
# 拆模块后必须**分模块比较**：拿 core 的 class 去比 app 的源码，会把正常状态误判成陈旧。
qa_classes_stale() {
    local root="$1" classes="${2:-}"
    local -a pairs
    if [ -n "$classes" ]; then
        pairs=("$classes:$root/app/src/main")
    else
        # 与 qa_classpath 同一套变体回落：只有 release 产物时也照常判陈旧，
        # 否则「产物在哪」这一层就会静默失效（函数返回空 = 一切正常）。
        local v=debug
        [ -d "$root/app/build/tmp/kotlin-classes/debug" ] || v=release
        pairs=("$root/app/build/tmp/kotlin-classes/$v:$root/app/src/main"
               "$root/core/build/tmp/kotlin-classes/$v:$root/core/src/main")
    fi
    local pair d src newest
    for pair in "${pairs[@]}"; do
        d=${pair%%:*}
        src=${pair#*:}
        [ -d "$d" ] || continue
        newest=$(find "$d" -name '*.class' -printf '%T@ %p\n' 2>/dev/null | sort -rn | head -1 | cut -d' ' -f2-)
        [ -n "$newest" ] && [ -e "$newest" ] || continue
        find "$src" -name '*.kt' -newer "$newest" -print -quit 2>/dev/null
    done
}
