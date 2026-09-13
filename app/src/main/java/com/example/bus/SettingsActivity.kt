package com.example.bus

import android.os.Bundle
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import android.widget.TextView

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("stops", MODE_PRIVATE)
        val saved = prefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        AppCompatDelegate.setDefaultNightMode(saved)

        setContentView(R.layout.activity_settings)

        val versionText = findViewById<TextView>(R.id.versionText)
        versionText.text = "Версия ${BuildConfig.VERSION_NAME}"

        val group = findViewById<RadioGroup>(R.id.themeGroup)
        val rbSystem = findViewById<RadioButton>(R.id.themeSystem)
        val rbLight = findViewById<RadioButton>(R.id.themeLight)
        val rbDark = findViewById<RadioButton>(R.id.themeDark)
        val checkBtn = findViewById<Button>(R.id.checkUpdates)

        when (saved) {
            AppCompatDelegate.MODE_NIGHT_NO -> rbLight.isChecked = true
            AppCompatDelegate.MODE_NIGHT_YES -> rbDark.isChecked = true
            else -> rbSystem.isChecked = true
        }

        group.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.themeLight -> AppCompatDelegate.MODE_NIGHT_NO
                R.id.themeDark -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            prefs.edit().putInt("theme_mode", mode).apply()
            AppCompatDelegate.setDefaultNightMode(mode)
        }

        checkBtn.setOnClickListener {
            Toast.makeText(this, "Проверка обновлений…", Toast.LENGTH_SHORT).show()
            UpdateChecker.check(this) { status, version ->
                prefs.edit().putLong("last_update_check", System.currentTimeMillis()).apply()
                when (status) {
                    UpdateChecker.Status.UP_TO_DATE ->
                        Toast.makeText(this, "У вас последняя версия ($version)", Toast.LENGTH_LONG).show()
                    UpdateChecker.Status.ERROR ->
                        Toast.makeText(this, "Не удалось проверить обновления", Toast.LENGTH_LONG).show()
                    UpdateChecker.Status.UPDATE -> { /* AppUpdater сам покажет диалог */ }
                }
            }
        }
    }
}