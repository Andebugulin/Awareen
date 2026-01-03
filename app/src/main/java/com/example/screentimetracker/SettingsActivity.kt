package com.example.screentimetracker

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.ContextCompat
import java.util.Calendar
import android.app.AlertDialog
import android.widget.ImageButton
import android.os.Handler
import android.os.Looper
import androidx.appcompat.widget.SwitchCompat
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText

import com.example.screentimetracker.AppSettings

class SettingsActivity : AppCompatActivity() {

    companion object {
        const val MIN_FONT_SIZE_SP = 18f
        const val MAX_FONT_SIZE_SP = 60f
        const val MIN_LEVEL_1_TIME_MINUTES = 5
        const val MAX_LEVEL_1_TIME_MINUTES = 60
        const val MIN_LEVEL_2_DURATION_MINUTES = 15
        const val MAX_LEVEL_2_DURATION_MINUTES = 60

        const val LEVEL_3_BLINKING_ENABLED = "level3_blinking_enabled"
        const val DEFAULT_LEVEL_3_BLINKING_ENABLED = true
    }

    private lateinit var prefs: SharedPreferences

    // UI Elements for Preview
    private lateinit var previewLayout: ConstraintLayout
    private lateinit var previewTimeText: TextView
    private lateinit var previewLevel1Header: TextView
    private lateinit var previewLevel2Header: TextView
    private lateinit var previewLevel3Header: TextView

    // UI Elements for Level 1 Settings
    private lateinit var level1ColorButton: Button
    private lateinit var level1PositionSpinner: Spinner
    private lateinit var level1FontSizeSeekBar: SeekBar
    private lateinit var level1FontSizeValue: TextView
    private lateinit var level1TimeSeekBar: SeekBar
    private lateinit var level1TimeValue: TextView
    private lateinit var level1BlinkingSwitch: SwitchCompat

    // UI Elements for Level 2 Settings
    private lateinit var level2ColorButton: Button
    private lateinit var level2PositionSpinner: Spinner
    private lateinit var level2FontSizeSeekBar: SeekBar
    private lateinit var level2FontSizeValue: TextView
    private lateinit var level2TimeSeekBar: SeekBar
    private lateinit var level2TimeValue: TextView
    private lateinit var level2BlinkingSwitch: SwitchCompat

    // UI Elements for Level 3 Settings
    private lateinit var level3ColorButton: Button
    private lateinit var level3PositionSpinner: Spinner
    private lateinit var level3FontSizeSeekBar: SeekBar
    private lateinit var level3FontSizeValue: TextView
    private lateinit var level3BlinkingSwitch: SwitchCompat

    // UI Elements for Reset Time Settings
    private lateinit var resetHourSpinner: Spinner
    private lateinit var resetMinuteSpinner: Spinner

    // Timer Display Settings
    private lateinit var timerDisplayModeSpinner: Spinner
    private lateinit var timerDisplayIntervalSeekBar: SeekBar
    private lateinit var timerDisplayIntervalValue: TextView
    private lateinit var timerDisplayDurationSeekBar: SeekBar
    private lateinit var timerDisplayDurationValue: TextView

    private var currentDisplayIntervalMinutes = 0
    private var currentDisplayDurationSeconds = 0

    private var currentPreviewLevel = 1

    private val positionOptions = arrayOf(
        "Top Left", "Top Center", "Top Right",
        "Middle Left", "Middle Center", "Middle Right",
        "Bottom Left", "Bottom Center", "Bottom Right"
    )

    private val positionOptionsWithCustom = arrayOf(
        "Custom",
        "Top Left", "Top Center", "Top Right",
        "Middle Left", "Middle Center", "Middle Right",
        "Bottom Left", "Bottom Center", "Bottom Right"
    )

    private var currentLevel1MaxTimeMinutes = 0
    private var currentLevel2DurationMinutes = 0

    private var currentLevel1Color: Int = AppSettings.DEFAULT_LEVEL_1_COLOR
    private var currentLevel2Color: Int = AppSettings.DEFAULT_LEVEL_2_COLOR
    private var currentLevel3Color: Int = AppSettings.DEFAULT_LEVEL_3_COLOR
    private var currentLevel1BlinkingEnabled: Boolean = AppSettings.DEFAULT_LEVEL_1_BLINKING_ENABLED
    private var currentLevel2BlinkingEnabled: Boolean = AppSettings.DEFAULT_LEVEL_2_BLINKING_ENABLED
    private var currentLevel3BlinkingEnabled: Boolean = DEFAULT_LEVEL_3_BLINKING_ENABLED

    private var currentHour = 0
    private var currentMinute = 0

    private var hasChanges = false
    private lateinit var closeButton: ImageButton

    private var isInitialSetup = true
    private var userChangesMade = false

    private fun finishInitialSetup() {
        Handler(Looper.getMainLooper()).postDelayed({
            isInitialSetup = false
        }, 300)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Settings"

        initializeViews()
        setupCloseButton()

        setupClickablePreviewHeaders()
        loadAndSetupControls()

        val saveButton = findViewById<Button>(R.id.saveButton)
        saveButton.setOnClickListener {
            if (validateSettings()) {
                saveSettings()

                val intent = Intent(AppSettings.ACTION_SETTINGS_UPDATED)
                sendBroadcast(intent)

                Toast.makeText(this, "Settings saved!", Toast.LENGTH_SHORT).show()

                val serviceIntent = Intent(this, ScreenTimeService::class.java)
                stopService(serviceIntent)
                Log.d("SettingsActivity", "Stopping ScreenTimeService...")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                    Log.d("SettingsActivity", "Starting ScreenTimeService as foreground service...")
                } else {
                    startService(serviceIntent)
                    Log.d("SettingsActivity", "Starting ScreenTimeService...")
                }

                finish()
            }
        }
        updatePreview()
    }

    private fun initializeViews() {
        // Preview section
        previewLayout = findViewById(R.id.previewLayout)
        previewTimeText = findViewById(R.id.previewTimeText)
        previewLevel1Header = findViewById(R.id.previewLevel1Header)
        previewLevel2Header = findViewById(R.id.previewLevel2Header)
        previewLevel3Header = findViewById(R.id.previewLevel3Header)

        // Level 1 controls
        level1ColorButton = findViewById(R.id.level1ColorButton)
        level1PositionSpinner = findViewById(R.id.level1PositionSpinner)
        level1FontSizeSeekBar = findViewById(R.id.level1FontSizeSeekBar)
        level1FontSizeValue = findViewById(R.id.level1FontSizeValue)
        level1TimeSeekBar = findViewById(R.id.level1TimeSeekBar)
        level1TimeValue = findViewById(R.id.level1TimeValue)
        level1BlinkingSwitch = findViewById(R.id.level1BlinkingSwitch)

        // Level 2 controls
        level2ColorButton = findViewById(R.id.level2ColorButton)
        level2PositionSpinner = findViewById(R.id.level2PositionSpinner)
        level2FontSizeSeekBar = findViewById(R.id.level2FontSizeSeekBar)
        level2FontSizeValue = findViewById(R.id.level2FontSizeValue)
        level2TimeSeekBar = findViewById(R.id.level2TimeSeekBar)
        level2TimeValue = findViewById(R.id.level2TimeValue)
        level2BlinkingSwitch = findViewById(R.id.level2BlinkingSwitch)

        // Level 3 controls
        level3ColorButton = findViewById(R.id.level3ColorButton)
        level3PositionSpinner = findViewById(R.id.level3PositionSpinner)
        level3FontSizeSeekBar = findViewById(R.id.level3FontSizeSeekBar)
        level3FontSizeValue = findViewById(R.id.level3FontSizeValue)
        level3BlinkingSwitch = findViewById(R.id.level3BlinkingSwitch)

        // Reset time controls
        resetHourSpinner = findViewById(R.id.resetHourSpinner)
        resetMinuteSpinner = findViewById(R.id.resetMinuteSpinner)

        // Timer Display controls
        timerDisplayModeSpinner = findViewById(R.id.timerDisplayModeSpinner)
        timerDisplayIntervalSeekBar = findViewById(R.id.timerDisplayIntervalSeekBar)
        timerDisplayIntervalValue = findViewById(R.id.timerDisplayIntervalValue)
        timerDisplayDurationSeekBar = findViewById(R.id.timerDisplayDurationSeekBar)
        timerDisplayDurationValue = findViewById(R.id.timerDisplayDurationValue)
    }

    private fun setupCloseButton() {
        closeButton = findViewById(R.id.closeButton)
        closeButton.setOnClickListener {
            handleClose()
        }
    }

    private fun handleClose() {
        if (hasChanges) {
            showUnsavedChangesDialog()
        } else {
            finish()
        }
    }

    private fun showUnsavedChangesDialog() {
        val customDialog = UnsavedChangesDialog(
            this,
            onSave = {
                if (validateSettings()) {
                    saveSettings()
                    val intent = Intent(AppSettings.ACTION_SETTINGS_UPDATED)
                    sendBroadcast(intent)

                    val serviceIntent = Intent(this, ScreenTimeService::class.java)
                    stopService(serviceIntent)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent)
                    } else {
                        startService(serviceIntent)
                    }
                    finish()
                }
            },
            onDiscard = {
                finish()
            },
            onCancel = {
            }
        )
        customDialog.show()
    }

    private fun markChanged() {
        if (!isInitialSetup) {
            hasChanges = true
            userChangesMade = true
        }
    }

    private fun setupClickablePreviewHeaders() {
        previewLevel1Header.setOnClickListener { currentPreviewLevel = 1; updatePreview() }
        previewLevel2Header.setOnClickListener { currentPreviewLevel = 2; updatePreview() }
        previewLevel3Header.setOnClickListener { currentPreviewLevel = 3; updatePreview() }
    }

    private fun loadAndSetupControls() {
        // Level 1
        setupColorButtonControl(level1ColorButton, AppSettings.LEVEL_1_COLOR, AppSettings.DEFAULT_LEVEL_1_COLOR) { color -> currentLevel1Color = color }
        setupPositionSpinnerControl(level1PositionSpinner, AppSettings.LEVEL_1_POSITION, AppSettings.DEFAULT_LEVEL_1_POSITION, 1)
        setupFontSizeSeekBarControl(level1FontSizeSeekBar, level1FontSizeValue, AppSettings.LEVEL_1_FONT_SIZE, AppSettings.DEFAULT_LEVEL_1_FONT_SIZE)
        setupLevelBlinkingSwitchControl(level1BlinkingSwitch, AppSettings.LEVEL_1_BLINKING_ENABLED, AppSettings.DEFAULT_LEVEL_1_BLINKING_ENABLED) { enabled -> currentLevel1BlinkingEnabled = enabled }
        setupLevel1TimeSeekBarControl()

        // Level 2
        setupColorButtonControl(level2ColorButton, AppSettings.LEVEL_2_COLOR, AppSettings.DEFAULT_LEVEL_2_COLOR) { color -> currentLevel2Color = color }
        setupPositionSpinnerControl(level2PositionSpinner, AppSettings.LEVEL_2_POSITION, AppSettings.DEFAULT_LEVEL_2_POSITION, 2)
        setupFontSizeSeekBarControl(level2FontSizeSeekBar, level2FontSizeValue, AppSettings.LEVEL_2_FONT_SIZE, AppSettings.DEFAULT_LEVEL_2_FONT_SIZE)
        setupLevelBlinkingSwitchControl(level2BlinkingSwitch, AppSettings.LEVEL_2_BLINKING_ENABLED, AppSettings.DEFAULT_LEVEL_2_BLINKING_ENABLED) { enabled -> currentLevel2BlinkingEnabled = enabled }
        setupLevel2TimeSeekBarControl()

        // Level 3
        setupColorButtonControl(level3ColorButton, AppSettings.LEVEL_3_COLOR, AppSettings.DEFAULT_LEVEL_3_COLOR) { color -> currentLevel3Color = color }
        setupPositionSpinnerControl(level3PositionSpinner, AppSettings.LEVEL_3_POSITION, AppSettings.DEFAULT_LEVEL_3_POSITION, 3)
        setupFontSizeSeekBarControl(level3FontSizeSeekBar, level3FontSizeValue, AppSettings.LEVEL_3_FONT_SIZE, AppSettings.DEFAULT_LEVEL_3_FONT_SIZE)
        setupLevelBlinkingSwitchControl(level3BlinkingSwitch, AppSettings.LEVEL_3_BLINKING_ENABLED, AppSettings.DEFAULT_LEVEL_3_BLINKING_ENABLED) { enabled -> currentLevel3BlinkingEnabled = enabled}

        // Reset Time
        setupResetTimeControls()

        // Timer Display Settings
        setupTimerDisplayControls()

        finishInitialSetup()
    }

    private fun setupTimerDisplayControls() {
        val displayModes = arrayOf("Always", "Interval")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, displayModes)
        timerDisplayModeSpinner.adapter = adapter

        val currentMode = prefs.getString(AppSettings.TIMER_DISPLAY_MODE, AppSettings.DEFAULT_TIMER_DISPLAY_MODE) ?: AppSettings.DEFAULT_TIMER_DISPLAY_MODE
        timerDisplayModeSpinner.setSelection(if (currentMode == "always") 0 else 1)

        timerDisplayModeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val isIntervalMode = position == 1
                timerDisplayIntervalSeekBar.isEnabled = isIntervalMode
                timerDisplayDurationSeekBar.isEnabled = isIntervalMode
                markChanged()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        currentDisplayIntervalMinutes = prefs.getInt(AppSettings.TIMER_DISPLAY_INTERVAL_MINUTES, AppSettings.DEFAULT_TIMER_DISPLAY_INTERVAL_MINUTES)
        timerDisplayIntervalSeekBar.max = AppSettings.MAX_DISPLAY_INTERVAL_MINUTES - AppSettings.MIN_DISPLAY_INTERVAL_MINUTES
        timerDisplayIntervalSeekBar.progress = (currentDisplayIntervalMinutes - AppSettings.MIN_DISPLAY_INTERVAL_MINUTES).coerceIn(0, timerDisplayIntervalSeekBar.max)
        timerDisplayIntervalValue.text = "$currentDisplayIntervalMinutes min"

        timerDisplayIntervalSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentDisplayIntervalMinutes = AppSettings.MIN_DISPLAY_INTERVAL_MINUTES + progress
                timerDisplayIntervalValue.text = "$currentDisplayIntervalMinutes min"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { markChanged() }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        currentDisplayDurationSeconds = prefs.getInt(AppSettings.TIMER_DISPLAY_DURATION_SECONDS, AppSettings.DEFAULT_TIMER_DISPLAY_DURATION_SECONDS)
        timerDisplayDurationSeekBar.max = AppSettings.MAX_DISPLAY_DURATION_SECONDS - AppSettings.MIN_DISPLAY_DURATION_SECONDS
        timerDisplayDurationSeekBar.progress = (currentDisplayDurationSeconds - AppSettings.MIN_DISPLAY_DURATION_SECONDS).coerceIn(0, timerDisplayDurationSeekBar.max)
        timerDisplayDurationValue.text = "$currentDisplayDurationSeconds sec"

        timerDisplayDurationSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentDisplayDurationSeconds = AppSettings.MIN_DISPLAY_DURATION_SECONDS + progress
                timerDisplayDurationValue.text = "$currentDisplayDurationSeconds sec"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { markChanged() }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val isIntervalMode = timerDisplayModeSpinner.selectedItemPosition == 1
        timerDisplayIntervalSeekBar.isEnabled = isIntervalMode
        timerDisplayDurationSeekBar.isEnabled = isIntervalMode
    }

    private fun setupColorButtonControl(button: Button, prefKey: String, defaultColor: Int, onColorSelected: (Int) -> Unit) {
        var loadedColor = prefs.getInt(prefKey, defaultColor)
        button.setBackgroundColor(loadedColor)
        onColorSelected(loadedColor)

        val availableColors = listOf(
            Color.GREEN, Color.YELLOW, Color.RED, Color.CYAN, Color.MAGENTA, Color.BLUE,
            Color.parseColor("#FFA500"), Color.parseColor("#FF4081"),
            Color.WHITE, Color.LTGRAY
        )

        button.setOnClickListener {
            showColorPickerDialog(loadedColor, availableColors) { selectedColor ->
                loadedColor = selectedColor
                button.setBackgroundColor(loadedColor)
                onColorSelected(loadedColor)
                updatePreview()
                markChanged()
            }
        }
    }

    private fun showColorPickerDialog(currentColor: Int, presetColors: List<Int>, onColorSelected: (Int) -> Unit) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_color_picker, null)
        val hexInput = dialogView.findViewById<EditText>(R.id.hexInput)
        val previewColor = dialogView.findViewById<View>(R.id.previewColor)
        val randomButton = dialogView.findViewById<Button>(R.id.randomColorButton)

        hexInput.setText(String.format("#%06X", 0xFFFFFF and currentColor))
        previewColor.setBackgroundColor(currentColor)

        randomButton.setOnClickListener {
            val randomColor = Color.rgb(
                (0..255).random(),
                (0..255).random(),
                (0..255).random()
            )
            hexInput.setText(String.format("#%06X", 0xFFFFFF and randomColor))
            previewColor.setBackgroundColor(randomColor)
        }

        hexInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                try {
                    val color = Color.parseColor(s.toString())
                    previewColor.setBackgroundColor(color)
                } catch (e: Exception) {
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        AlertDialog.Builder(this, R.style.CustomAlertDialog)
            .setTitle("Choose Color")
            .setView(dialogView)
            .setPositiveButton("Select") { _, _ ->
                try {
                    val color = Color.parseColor(hexInput.text.toString())
                    onColorSelected(color)
                } catch (e: Exception) {
                    Toast.makeText(this, "Invalid color format", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
            .apply {
                show()
                getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Color.parseColor("#FFA500"))
                getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(Color.parseColor("#FFA500"))
            }
    }

    private fun setupPositionSpinnerControl(spinner: Spinner, prefKey: String, defaultPosition: String, level: Int) {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, positionOptionsWithCustom)
        spinner.adapter = adapter

        val (useCustomKey, xKey, yKey) = when (level) {
            1 -> Triple(ScreenTimeService.LEVEL_1_USE_CUSTOM, ScreenTimeService.LEVEL_1_CUSTOM_X, ScreenTimeService.LEVEL_1_CUSTOM_Y)
            2 -> Triple(ScreenTimeService.LEVEL_2_USE_CUSTOM, ScreenTimeService.LEVEL_2_CUSTOM_X, ScreenTimeService.LEVEL_2_CUSTOM_Y)
            else -> Triple(ScreenTimeService.LEVEL_3_USE_CUSTOM, ScreenTimeService.LEVEL_3_CUSTOM_X, ScreenTimeService.LEVEL_3_CUSTOM_Y)
        }

        val hasCustomPosition = prefs.getBoolean(useCustomKey, false)

        if (hasCustomPosition) {
            spinner.setSelection(0) // "Custom" is at index 0
        } else {
            val currentPosition = prefs.getString(prefKey, defaultPosition) ?: defaultPosition
            val index = positionOptionsWithCustom.indexOf(currentPosition)
            spinner.setSelection(if (index >= 0) index else 1)
        }

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!isInitialSetup && position > 0) {
                    // User selected a non-custom position, clear custom position
                    prefs.edit()
                        .putBoolean(useCustomKey, false)
                        .remove(xKey)
                        .remove(yKey)
                        .apply()
                    Toast.makeText(this@SettingsActivity, "Custom position cleared for Level $level", Toast.LENGTH_SHORT).show()
                }
                updatePreview()
                markChanged()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupFontSizeSeekBarControl(seekBar: SeekBar, valueTextView: TextView, prefKey: String, defaultSizeSp: Int) {
        val currentSizeSp = prefs.getInt(prefKey, defaultSizeSp).toFloat()
        seekBar.max = (MAX_FONT_SIZE_SP - MIN_FONT_SIZE_SP).toInt()
        seekBar.progress = (currentSizeSp - MIN_FONT_SIZE_SP).toInt().coerceIn(0, seekBar.max)
        valueTextView.text = "${currentSizeSp.toInt()}sp"

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val newSize = MIN_FONT_SIZE_SP + progress
                valueTextView.text = "${newSize.toInt()}sp"
                updatePreview()
                markChanged()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {markChanged()}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun setupLevel1TimeSeekBarControl() {
        currentLevel1MaxTimeMinutes = prefs.getInt(AppSettings.LEVEL_1_MAX_TIME_SECONDS, AppSettings.DEFAULT_LEVEL_1_MAX_TIME_SECONDS) / 60
        level1TimeSeekBar.max = MAX_LEVEL_1_TIME_MINUTES - MIN_LEVEL_1_TIME_MINUTES
        level1TimeSeekBar.progress = (currentLevel1MaxTimeMinutes - MIN_LEVEL_1_TIME_MINUTES).coerceIn(0, level1TimeSeekBar.max)
        level1TimeValue.text = "$currentLevel1MaxTimeMinutes min"

        level1TimeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentLevel1MaxTimeMinutes = MIN_LEVEL_1_TIME_MINUTES + progress
                level1TimeValue.text = "$currentLevel1MaxTimeMinutes min"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {markChanged()}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun setupLevel2TimeSeekBarControl() {
        currentLevel2DurationMinutes = prefs.getInt(AppSettings.LEVEL_2_DURATION_SECONDS, AppSettings.DEFAULT_LEVEL_2_DURATION_SECONDS) / 60
        level2TimeSeekBar.max = MAX_LEVEL_2_DURATION_MINUTES - MIN_LEVEL_2_DURATION_MINUTES
        level2TimeSeekBar.progress = (currentLevel2DurationMinutes - MIN_LEVEL_2_DURATION_MINUTES).coerceIn(0, level2TimeSeekBar.max)
        level2TimeValue.text = "$currentLevel2DurationMinutes min duration"

        level2TimeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentLevel2DurationMinutes = MIN_LEVEL_2_DURATION_MINUTES + progress
                level2TimeValue.text = "$currentLevel2DurationMinutes min duration"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                markChanged()
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun setupLevelBlinkingSwitchControl(switch: SwitchCompat, prefKey: String, default: Boolean, onToggle: (Boolean) -> Unit) {
        val enabled = prefs.getBoolean(prefKey, default)
        switch.isChecked = enabled
        onToggle(enabled)

        switch.setOnCheckedChangeListener { _, isChecked ->
            onToggle(isChecked)
            markChanged()
        }
    }

    private fun setupResetTimeControls() {
        val hours = Array(24) { i -> String.format("%02d", i) }
        val minutes = Array(60) { i -> String.format("%02d", i) }

        resetHourSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, hours)
        resetMinuteSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, minutes)

        val savedHour = prefs.getInt(AppSettings.RESET_HOUR, AppSettings.DEFAULT_RESET_HOUR)
        val savedMinute = prefs.getInt(AppSettings.RESET_MINUTE, AppSettings.DEFAULT_RESET_MINUTE)

        resetHourSpinner.setSelection(savedHour)
        resetMinuteSpinner.setSelection(savedMinute)

        resetHourSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                markChanged()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        resetMinuteSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                markChanged()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun updatePreview() {
        val color: Int
        val position: String
        val fontSizeSp: Float

        when (currentPreviewLevel) {
            1 -> {
                color = currentLevel1Color
                val selectedItem = level1PositionSpinner.selectedItem?.toString()
                position = if (selectedItem == "Custom") AppSettings.DEFAULT_LEVEL_1_POSITION else selectedItem ?: AppSettings.DEFAULT_LEVEL_1_POSITION
                fontSizeSp = MIN_FONT_SIZE_SP + level1FontSizeSeekBar.progress
                highlightPreviewHeader(previewLevel1Header)
            }
            2 -> {
                color = currentLevel2Color
                val selectedItem = level2PositionSpinner.selectedItem?.toString()
                position = if (selectedItem == "Custom") AppSettings.DEFAULT_LEVEL_2_POSITION else selectedItem ?: AppSettings.DEFAULT_LEVEL_2_POSITION
                fontSizeSp = MIN_FONT_SIZE_SP + level2FontSizeSeekBar.progress
                highlightPreviewHeader(previewLevel2Header)
            }
            else -> {
                color = currentLevel3Color
                val selectedItem = level3PositionSpinner.selectedItem?.toString()
                position = if (selectedItem == "Custom") AppSettings.DEFAULT_LEVEL_3_POSITION else selectedItem ?: AppSettings.DEFAULT_LEVEL_3_POSITION
                fontSizeSp = MIN_FONT_SIZE_SP + level3FontSizeSeekBar.progress
                highlightPreviewHeader(previewLevel3Header)
            }
        }

        previewTimeText.text = "00:12:34"
        previewTimeText.setTextColor(color)
        previewTimeText.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp)

        val constraintSet = ConstraintSet()
        constraintSet.clone(previewLayout)
        constraintSet.clear(previewTimeText.id, ConstraintSet.START)
        constraintSet.clear(previewTimeText.id, ConstraintSet.END)
        constraintSet.clear(previewTimeText.id, ConstraintSet.TOP)
        constraintSet.clear(previewTimeText.id, ConstraintSet.BOTTOM)

        when (position) {
            "Top Left" -> {
                constraintSet.connect(previewTimeText.id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, 16)
                constraintSet.connect(previewTimeText.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, 16)
            }
            "Top Center" -> {
                constraintSet.connect(previewTimeText.id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, 16)
                constraintSet.connect(previewTimeText.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
                constraintSet.connect(previewTimeText.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
            }
            "Top Right" -> {
                constraintSet.connect(previewTimeText.id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, 16)
                constraintSet.connect(previewTimeText.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, 16)
            }
            "Middle Left" -> {
                constraintSet.connect(previewTimeText.id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
                constraintSet.connect(previewTimeText.id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
                constraintSet.connect(previewTimeText.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, 16)
            }
            "Middle Center" -> {
                constraintSet.connect(previewTimeText.id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
                constraintSet.connect(previewTimeText.id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
                constraintSet.connect(previewTimeText.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
                constraintSet.connect(previewTimeText.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
            }
            "Middle Right" -> {
                constraintSet.connect(previewTimeText.id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
                constraintSet.connect(previewTimeText.id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
                constraintSet.connect(previewTimeText.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, 16)
            }
            "Bottom Left" -> {
                constraintSet.connect(previewTimeText.id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM, 16)
                constraintSet.connect(previewTimeText.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, 16)
            }
            "Bottom Center" -> {
                constraintSet.connect(previewTimeText.id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM, 16)
                constraintSet.connect(previewTimeText.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
                constraintSet.connect(previewTimeText.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
            }
            "Bottom Right" -> {
                constraintSet.connect(previewTimeText.id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM, 16)
                constraintSet.connect(previewTimeText.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, 16)
            }
        }
        constraintSet.applyTo(previewLayout)
    }

    private fun highlightPreviewHeader(activeHeader: TextView) {
        val activeColor = ContextCompat.getColor(this, R.color.preview_header_active)
        val inactiveColor = ContextCompat.getColor(this, R.color.preview_header_not_active)

        previewLevel1Header.setTextColor(if (activeHeader == previewLevel1Header) activeColor else inactiveColor)
        previewLevel2Header.setTextColor(if (activeHeader == previewLevel2Header) activeColor else inactiveColor)
        previewLevel3Header.setTextColor(if (activeHeader == previewLevel3Header) activeColor else inactiveColor)
    }

    private fun validateSettings(): Boolean {
        if (currentLevel1MaxTimeMinutes <= 0) {
            Toast.makeText(this, "Level 1 time must be positive.", Toast.LENGTH_SHORT).show()
            return false
        }
        if (currentLevel2DurationMinutes <= 0) {
            Toast.makeText(this, "Level 2 duration must be positive.", Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    private fun saveSettings() {
        with(prefs.edit()) {
            putInt(AppSettings.LEVEL_1_COLOR, currentLevel1Color)
            val level1Pos = level1PositionSpinner.selectedItem.toString()
            if (level1Pos != "Custom") {
                putString(AppSettings.LEVEL_1_POSITION, level1Pos)
            }
            putInt(AppSettings.LEVEL_1_FONT_SIZE, (MIN_FONT_SIZE_SP + level1FontSizeSeekBar.progress).toInt())
            putInt(AppSettings.LEVEL_1_MAX_TIME_SECONDS, currentLevel1MaxTimeMinutes * 60)
            putBoolean(AppSettings.LEVEL_1_BLINKING_ENABLED, currentLevel1BlinkingEnabled)

            putInt(AppSettings.LEVEL_2_COLOR, currentLevel2Color)
            val level2Pos = level2PositionSpinner.selectedItem.toString()
            if (level2Pos != "Custom") {
                putString(AppSettings.LEVEL_2_POSITION, level2Pos)
            }
            putInt(AppSettings.LEVEL_2_FONT_SIZE, (MIN_FONT_SIZE_SP + level2FontSizeSeekBar.progress).toInt())
            putInt(AppSettings.LEVEL_2_DURATION_SECONDS, currentLevel2DurationMinutes * 60)
            putBoolean(AppSettings.LEVEL_2_BLINKING_ENABLED, currentLevel2BlinkingEnabled)

            putInt(AppSettings.LEVEL_3_COLOR, currentLevel3Color)
            val level3Pos = level3PositionSpinner.selectedItem.toString()
            if (level3Pos != "Custom") {
                putString(AppSettings.LEVEL_3_POSITION, level3Pos)
            }
            putInt(AppSettings.LEVEL_3_FONT_SIZE, (MIN_FONT_SIZE_SP + level3FontSizeSeekBar.progress).toInt())
            putBoolean(AppSettings.LEVEL_3_BLINKING_ENABLED, level3BlinkingSwitch.isChecked)

            putInt(AppSettings.RESET_HOUR, resetHourSpinner.selectedItemPosition)
            putInt(AppSettings.RESET_MINUTE, resetMinuteSpinner.selectedItemPosition)

            val selectedMode = if (timerDisplayModeSpinner.selectedItemPosition == 0) "always" else "interval"
            putString(AppSettings.TIMER_DISPLAY_MODE, selectedMode)
            putInt(AppSettings.TIMER_DISPLAY_INTERVAL_MINUTES, currentDisplayIntervalMinutes)
            putInt(AppSettings.TIMER_DISPLAY_DURATION_SECONDS, currentDisplayDurationSeconds)

            apply()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        super.onBackPressed()
        handleClose()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            handleClose()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}