package com.thousandofwind.aipoc.koog

import com.thousandofwind.aipoc.Probe
import kotlinx.coroutines.runBlocking
import org.junit.Test

class EngineProbeTest {
    @Test fun streamingToolAndSession() = runBlocking { Probe.run("chat", ::KoogEngine); Unit }
    @Test fun rejectsUnsafeToolRequests() = runBlocking {
        Probe.run("unknown", ::KoogEngine); Probe.run("invalid", ::KoogEngine); Unit
    }
    @Test fun errorsCancellationAndBudget() = runBlocking {
        listOf("unauthorized", "broken", "slow", "loop").forEach { Probe.run(it, ::KoogEngine) }; Unit
    }
}
