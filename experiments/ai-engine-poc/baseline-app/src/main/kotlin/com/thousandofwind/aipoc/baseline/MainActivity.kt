package com.thousandofwind.aipoc.baseline
import android.app.Activity
import android.os.Bundle
import android.widget.TextView
class MainActivity : Activity() {
    override fun onCreate(state: Bundle?) { super.onCreate(state); setContentView(TextView(this).apply { text = "AI PoC baseline: no SDK" }) }
}
