package app.recall.model

/**
 * Models Recall offers. All are ungated on Hugging Face (no account, no license click) and
 * pinned by SHA-256, so a corrupted or swapped file is rejected.
 */
data class ModelSpec(
    val id: String,
    val name: String,
    val repo: String,
    val file: String,
    val bytes: Long,
    val sha256: String,
    val minRamGb: Int,
    val note: String,
    val license: String,
) {
    val url get() = "https://huggingface.co/$repo/resolve/main/$file?download=true"
}

object ModelCatalog {
    val all = listOf(
        ModelSpec(
            id = "qwen3-4b-2507",
            name = "Qwen3 4B Instruct",
            repo = "unsloth/Qwen3-4B-Instruct-2507-GGUF",
            file = "Qwen3-4B-Instruct-2507-Q4_K_M.gguf",
            bytes = 2_497_281_120,
            sha256 = "3605803b982cb64aead44f6c1b2ae36e3acdb41d8e46c8a94c6533bc4c67e597",
            minRamGb = 12,
            note = "Best summaries and chat",
            license = "Apache 2.0",
        ),
        ModelSpec(
            id = "llama-3.2-3b",
            name = "Llama 3.2 3B Instruct",
            repo = "bartowski/Llama-3.2-3B-Instruct-GGUF",
            file = "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            bytes = 2_019_377_696,
            sha256 = "6c1a2b41161032677be168d354123594c0e6e67d2b9227c84f296ad037c728ff",
            minRamGb = 8,
            note = "For 8 GB phones",
            license = "Llama 3.2 Community",
        ),
        ModelSpec(
            id = "qwen3-1.7b",
            name = "Qwen3 1.7B",
            repo = "unsloth/Qwen3-1.7B-GGUF",
            file = "Qwen3-1.7B-Q4_K_M.gguf",
            bytes = 1_107_409_472,
            sha256 = "b139949c5bd74937ad8ed8c8cf3d9ffb1e99c866c823204dc42c0d91fa181897",
            minRamGb = 6,
            note = "For 6 GB phones · shorter summaries",
            license = "Apache 2.0",
        ),
        ModelSpec(
            id = "qwen3-0.6b",
            name = "Qwen3 0.6B",
            repo = "unsloth/Qwen3-0.6B-GGUF",
            file = "Qwen3-0.6B-Q4_K_M.gguf",
            bytes = 396_705_472,
            sha256 = "ac2d97712095a558e31573f62f466a3f9d93990898b0ec79d7c974c1780d524a",
            minRamGb = 4,
            note = "Tiny · basic summaries",
            license = "Apache 2.0",
        ),
    )

    fun byFile(file: String) = all.firstOrNull { it.file == file }
    fun bySha(sha: String) = all.firstOrNull { it.sha256.equals(sha, ignoreCase = true) }

    /** Picks the largest model the phone's RAM handles comfortably. */
    fun recommend(ramGb: Double): ModelSpec? = all.firstOrNull { ramGb >= it.minRamGb - 0.8 }
}
