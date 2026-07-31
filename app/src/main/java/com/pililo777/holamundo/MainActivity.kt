package com.pililo777.holamundo

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val greeting = TextView(this).apply {
            text = getString(R.string.hello_world)
            textSize = 32f
            gravity = Gravity.CENTER
        }

        setContentView(greeting)
    }
}
