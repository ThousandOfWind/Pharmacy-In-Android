package com.thousandofwind.aipoc.koog

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import com.thousandofwind.aipoc.Probe
import kotlinx.coroutines.*

/** Dedicated package, scripted responses only, no network permission and no pharmacy access. */
class MainActivity : Activity() {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Main)
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val output = TextView(this).apply { text = "AI PoC koog — synthetic fixtures\n"; setPadding(24, 24, 24, 24) }
        setContentView(output)
        scope.launch {
            for (scenario in Probe.scenarios) {
                val result = try { withContext(Dispatchers.Default) { Probe.run(scenario, ::KoogEngine) } }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { "FAIL $scenario: ${e.javaClass.simpleName}" }
                output.append("$result\n")
            }
            output.append("PoC completed (not live-model verification)")
        }
    }
    override fun onDestroy() { job.cancel(); super.onDestroy() }
}
