package com.beichen.polaris.core

/**
 * 模型能力档位。**这是工具暴露策略的唯一输入**，不按 provider 名硬编码白名单。
 *
 * 为什么按「能力」而不是按「provider 字符串」：provider 名会新增（openai / claude /
 * gemini / ollama / 本地 llama…），每加一个就要回来改白名单。档位是稳定的三档，
 * 新 provider 只需声明自己属于哪档，暴露策略不用动。
 *
 * 迁移说明：本枚举原先在 `core/kernel/KernelTypes.kt` 里。那个包是一套未进主链路的
 * 内核实验（995 行），但**这个枚举和 [ExposureMode] 被主链路用着**——所以「把 747 行
 * 整个删掉」这个说法是错的，照删会编译不过。清理时先把这两个搬出来。
 */
enum class Strength {
    /** 端侧小模型：上下文小、指令遵循弱，工具表要压到接近零。 */
    TINY,

    /** 端侧较强或小参数云端模型：能读分组索引，按需激活。 */
    SMALL,

    /** 云端大模型：全量工具也不至于选错。 */
    LARGE
}

/**
 * 工具暴露的三种形态。
 *
 * - [FULL]：全量发给模型（开发者开关，或明确知道模型扛得住）。
 * - [GROUPED]：只发已激活组 + 元工具，模型可自己 `__use_tools` 开组。
 * - [META]：只给元工具，业务工具一个都不直接发（端侧最小面）。
 *
 * 迁移说明：同 [Strength]，原在 `core/kernel/Exposure.kt`。
 */
enum class ExposureMode { FULL, GROUPED, META }
