package com.airsonic.demo

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.airsonic.demo.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnPairingDemo.setOnClickListener {
            startActivity(Intent(this, PairingDemoActivity::class.java))
        }
        binding.btnCaptureDemo.setOnClickListener {
            startActivity(Intent(this, CaptureDemoActivity::class.java))
        }
        binding.btnFileCastDemo.setOnClickListener {
            startActivity(Intent(this, FileCastDemoActivity::class.java))
        }
    }
}
