package com.thousandofwind.aipoc.openai

import com.thousandofwind.aipoc.Probe
import kotlinx.coroutines.runBlocking
import org.junit.Test

class EngineProbeTest {
    @Test fun streamingToolAndSession() = runBlocking { Probe.run("chat", ::OpenAiEngine); Unit }
    @Test fun rejectsUnsafeToolRequests() = runBlocking {
        Probe.run("unknown", ::OpenAiEngine); Probe.run("invalid", ::OpenAiEngine); Unit
    }
    @Test fun errorsCancellationAndBudget() = runBlocking {
        listOf("unauthorized", "broken", "slow", "loop").forEach { Probe.run(it, ::OpenAiEngine) }; Unit
    }
}
