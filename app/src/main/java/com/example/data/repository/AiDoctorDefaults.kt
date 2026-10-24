package com.example.data.repository

internal object AiDoctorDefaults {
    const val SERVICE_NAME: String = "Kiyara Router"
    const val ENDPOINT: String = "https://kiyararouter.web.id/v1/chat/completions"
    const val API_KEY: String = "sk-kiyara-4ff382de68682691ffd9a3734988585e8d58dc4963a9d294fa96fe83cc55a66d"
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
