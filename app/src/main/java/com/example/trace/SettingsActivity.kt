package com.example.trace

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import com.example.trace.databinding.ActivitySettingsBinding

/**
 * TRACE — Settings.
 *
 * Currently holds only the appearance switch, moved here from the Timeline
 * overflow menu now that the dashboard has a real app bar to reach it from.
 * Capture defaults, AI keys, retention and security settings land here in the
 * later stages; the screen exists now so the dashboard's Settings action has a
 * destination.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = "Settings"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.switchWallpaperPalette.isChecked = TraceAppearance.useWallpaperPalette(this)
        binding.switchWallpaperPalette.setOnCheckedChangeListener { _, checked ->
            TraceAppearance.setUseWallpaperPalette(this, checked)
            // Dynamic colour is applied as each activity is created, so a palette
            // change only becomes visible after a re-create.
            recreate()
        }

        binding.rowCloudAi.setOnClickListener {
            startActivity(Intent(this, CloudAiSettingsActivity::class.java))
        }
        binding.rowLocalAi.setOnClickListener {
            startActivity(Intent(this, LocalAiSettingsActivity::class.java))
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
