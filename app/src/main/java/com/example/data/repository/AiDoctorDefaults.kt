package com.example.data.repository

import com.example.BuildConfig

internal object AiDoctorDefaults {
    const val SERVICE_NAME: String = "Kiyara Router"
    const val ENDPOINT: String = "https://kiyararouter.web.id/v1/chat/completions"
    // Never commit provider credentials. Supplied at build time through the
    // KIYARA_API_KEY environment variable / CI secret; empty means the user
    // must enter their own key in AI Doctor.
    val API_KEY: String = BuildConfig.KIYARA_KEY
    const val MODEL: String = "deepseek-ai/deepseek-v4.1-flash"

    data class ModelPreset(val id: String, val label: String)

    val modelPresets: List<ModelPreset> = listOf(
        ModelPreset("deepseek-ai/deepseek-v4.1-flash", "DeepSeek V4.1 Flash"),
        ModelPreset("deepseek-ai/deepseek-v4-pro-0813", "DeepSeek V4 Pro"),
        ModelPreset("z-ai/glm-5.3-flash", "GLM 5.3 Flash"),
        ModelPreset("qwen/qwen3.8-flash", "Qwen3.8 Flash"),
        ModelPreset("openai/gpt-6-luna", "GPT-6 Luna"),
        ModelPreset("anthropic/claude-sonnet-5.5", "Claude Sonnet 5.5"),
        ModelPreset("nvidia/nemotron-3-ultra-550b-a55b:free", "Nemotron Ultra (Free)")
    )
}
