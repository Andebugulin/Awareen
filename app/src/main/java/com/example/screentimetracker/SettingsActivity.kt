package com.example.screentimetracker

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
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
import android.widget.GridLayout

import com.example.screentimetracker.AppSettings // Import your AppSettings

/**
 * Activity for users to customize screen time tracker settings.
 * Allows configuration of colors, positions, font sizes, and time thresholds for different levels,
 * as well as the daily reset time.
 */
class SettingsActivity : AppCompatActivity() {

    // Companion object for constants related to settings constraints
    companion object {
        const val MIN_FONT_SIZE_SP = 18f // Minimum font size in SP
        const val MAX_FONT_SIZE_SP = 60f // Maximum font size in SP
        const val MIN_LEVEL_1_TIME_MINUTES = 5  // Minimum time for Level 1 (e.g., 5 minutes)
        const val MAX_LEVEL_1_TIME_MINUTES = 60 // Maximum time for Level 1 (e.g., 60 minutes)
        const val MIN_LEVEL_2_DURATION_MINUTES = 15 // Minimum duration for Level 2 (e.g., 15 minutes)
        const val MAX_LEVEL_2_DURATION_MINUTES = 60 // Maximum duration for Level 2 (e.g., 60 minutes)

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
    private lateinit var level1TimeSeekBar: SeekBar // Controls max time for level 1
    private lateinit var level1TimeValue: TextView  // Displays max time in minutes
    private lateinit var level1BlinkingSwitch: SwitchCompat

    // UI Elements for Level 2 Settings
    private lateinit var level2ColorButton: Button
    private lateinit var level2PositionSpinner: Spinner
    private lateinit var level2FontSizeSeekBar: SeekBar
    private lateinit var level2FontSizeValue: TextView
    private lateinit var level2TimeSeekBar: SeekBar // Controls duration for level 2
    private lateinit var level2TimeValue: TextView  // Displays duration in minutes
    private lateinit var level2BlinkingSwitch: SwitchCompat

    // UI Elements for Level 3 Settings
    private lateinit var level3ColorButton: Button
    private lateinit var level3PositionSpinner: Spinner
    private lateinit var level3FontSizeSeekBar: SeekBar
    private lateinit var level3FontSizeValue: TextView
    private lateinit var level3BlinkingSwitch: SwitchCompat // Corrected declaration


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




    // Tracks which level is currently being previewed
    private var currentPreviewLevel = 1

    // Available positions for the overlay
    private val positionOptions = arrayOf(
        "Top Left", "Top Center", "Top Right",
        "Middle Left", "Middle Center", "Middle Right",
        "Bottom Left", "Bottom Center", "Bottom Right"
    )

    // Store current time values from SeekBars to manage interactions and saving
    private var currentLevel1MaxTimeMinutes = 0
    private var currentLevel2DurationMinutes = 0

    private var currentLevel1Color: Int = AppSettings.DEFAULT_LEVEL_1_COLOR
    private var currentLevel2Color: Int = AppSettings.DEFAULT_LEVEL_2_COLOR
    private var currentLevel3Color: Int = AppSettings.DEFAULT_LEVEL_3_COLOR
    private var currentLevel1BlinkingEnabled: Boolean = AppSettings.DEFAULT_LEVEL_1_BLINKING_ENABLED
    private var currentLevel2BlinkingEnabled: Boolean = AppSettings.DEFAULT_LEVEL_2_BLINKING_ENABLED
    private var currentLevel3BlinkingEnabled: Boolean = DEFAULT_LEVEL_3_BLINKING_ENABLED // New state variable


    private var currentHour = 0
    private var currentMinute = 0

    private var hasChanges = false
    private lateinit var closeButton: ImageButton

    private var isInitialSetup = true
    private var userChangesMade = false

    private fun finishInitialSetup() {
        // Set with a slight delay to ensure all initializations are complete
        Handler(Looper.getMainLooper()).postDelayed({
            isInitialSetup = false
        }, 300)
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings) // Make sure this matches your XML file name

        prefs = getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE)

        // Enable the Up button in the action bar
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Settings"

        // Initialize SharedPreferences
        prefs = getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE)

        initializeViews()
        setupCloseButton()

        setupClickablePreviewHeaders()
        loadAndSetupControls() // Consolidated function to load and set up all controls

        // Setup the save button
        val saveButton = findViewById<Button>(R.id.saveButton)
        saveButton.setOnClickListener {
            if (validateSettings()) {
                saveSettings() // This saves the settings to SharedPreferences

                // Notify the service that settings have changed (existing code)
                val intent = Intent(AppSettings.ACTION_SETTINGS_UPDATED)
                sendBroadcast(intent)

                Toast.makeText(this, "Settings saved!", Toast.LENGTH_SHORT).show()

                // --- START OF NEW CODE TO FORCE SERVICE RESTART ---
                val serviceIntent = Intent(this, ScreenTimeService::class.java)

                // 1. Stop the currently running service instance
                stopService(serviceIntent)
                Log.d("SettingsActivity", "Stopping ScreenTimeService...")

                // 2. Start the service again. This will cause its onCreate() to be called,
                //    loading the new settings from SharedPreferences.
                //    Use startForegroundService for Android O (API 26) and above.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                    Log.d("SettingsActivity", "Starting ScreenTimeService as foreground service...")
                } else {
                    startService(serviceIntent)
                    Log.d("SettingsActivity", "Starting ScreenTimeService...")
                }
                // --- END OF NEW CODE ---

                finish() // Close the settings activity
            }
        }
        updatePreview() // Show initial preview based on Level 1 settings
    }

    /**
     * Initializes all view variables by finding them in the layout.
     */
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
        level3BlinkingSwitch = findViewById(R.id.level3BlinkingSwitch) // Corrected initialization here

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
        // Create and show the custom dialog instance
        val customDialog = UnsavedChangesDialog(
            this,
            onSave = {
                // Logic for "Save" button
                if (validateSettings()) {
                    saveSettings()
                    // Broadcast settings update
                    val intent = Intent(AppSettings.ACTION_SETTINGS_UPDATED)
                    sendBroadcast(intent)

                    // Restart service with new settings
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
                // Logic for "Discard" button
                finish() // Close the activity
            },
            onCancel = {
                // Logic for "Cancel" button (dialog is just dismissed)
                // No action needed here as dismiss() is called by the button listener
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

    /**
     * Sets up click listeners for the preview headers to switch the previewed level.
     */
    private fun setupClickablePreviewHeaders() {
        previewLevel1Header.setOnClickListener { currentPreviewLevel = 1; updatePreview() }
        previewLevel2Header.setOnClickListener { currentPreviewLevel = 2; updatePreview() }
        previewLevel3Header.setOnClickListener { currentPreviewLevel = 3; updatePreview() }
    }

    /**
     * Loads current settings from SharedPreferences and sets up all UI controls.
     */
    private fun loadAndSetupControls() {
        // Level 1
        setupColorButtonControl(level1ColorButton, AppSettings.LEVEL_1_COLOR, AppSettings.DEFAULT_LEVEL_1_COLOR) { color -> currentLevel1Color = color }
        setupPositionSpinnerControl(level1PositionSpinner, AppSettings.LEVEL_1_POSITION, AppSettings.DEFAULT_LEVEL_1_POSITION)
        setupFontSizeSeekBarControl(level1FontSizeSeekBar, level1FontSizeValue, AppSettings.LEVEL_1_FONT_SIZE, AppSettings.DEFAULT_LEVEL_1_FONT_SIZE)
        setupLevelBlinkingSwitchControl(level1BlinkingSwitch, AppSettings.LEVEL_1_BLINKING_ENABLED, AppSettings.DEFAULT_LEVEL_1_BLINKING_ENABLED) { enabled -> currentLevel1BlinkingEnabled = enabled }
        setupLevel1TimeSeekBarControl()

        // Level 2
        setupColorButtonControl(level2ColorButton, AppSettings.LEVEL_2_COLOR, AppSettings.DEFAULT_LEVEL_2_COLOR) { color -> currentLevel2Color = color }
        setupPositionSpinnerControl(level2PositionSpinner, AppSettings.LEVEL_2_POSITION, AppSettings.DEFAULT_LEVEL_2_POSITION)
        setupFontSizeSeekBarControl(level2FontSizeSeekBar, level2FontSizeValue, AppSettings.LEVEL_2_FONT_SIZE, AppSettings.DEFAULT_LEVEL_2_FONT_SIZE)
        setupLevelBlinkingSwitchControl(level2BlinkingSwitch, AppSettings.LEVEL_2_BLINKING_ENABLED, AppSettings.DEFAULT_LEVEL_2_BLINKING_ENABLED) { enabled -> currentLevel2BlinkingEnabled = enabled }
        setupLevel2TimeSeekBarControl()

        // Level 3
        setupColorButtonControl(level3ColorButton, AppSettings.LEVEL_3_COLOR, AppSettings.DEFAULT_LEVEL_3_COLOR) { color -> currentLevel3Color = color }
        setupPositionSpinnerControl(level3PositionSpinner, AppSettings.LEVEL_3_POSITION, AppSettings.DEFAULT_LEVEL_3_POSITION)
        setupFontSizeSeekBarControl(level3FontSizeSeekBar, level3FontSizeValue, AppSettings.LEVEL_3_FONT_SIZE, AppSettings.DEFAULT_LEVEL_3_FONT_SIZE)
        setupLevelBlinkingSwitchControl(level3BlinkingSwitch, AppSettings.LEVEL_3_BLINKING_ENABLED, AppSettings.DEFAULT_LEVEL_3_BLINKING_ENABLED) { enabled -> currentLevel3BlinkingEnabled = enabled} // Call new function for the switch


        // Reset Time
        setupResetTimeControls()

        // Timer Display Settings
        setupTimerDisplayControls()

        finishInitialSetup()

    }

    // --- Generic Control Setup Functions ---

    private fun setupTimerDisplayControls() {
        // Setup display mode spinner
        val displayModes = arrayOf("Always", "Interval")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, displayModes)
        timerDisplayModeSpinner.adapter = adapter

        val currentMode = prefs.getString(AppSettings.TIMER_DISPLAY_MODE, AppSettings.DEFAULT_TIMER_DISPLAY_MODE) ?: AppSettings.DEFAULT_TIMER_DISPLAY_MODE
        timerDisplayModeSpinner.setSelection(if (currentMode == "always") 0 else 1)

        timerDisplayModeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                // Enable/disable interval and duration controls based on mode
                val isIntervalMode = position == 1
                timerDisplayIntervalSeekBar.isEnabled = isIntervalMode
                timerDisplayDurationSeekBar.isEnabled = isIntervalMode
                markChanged()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Setup interval seekbar
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

        // Setup duration seekbar
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

        // Initial state setup
        val isIntervalMode = timerDisplayModeSpinner.selectedItemPosition == 1
        timerDisplayIntervalSeekBar.isEnabled = isIntervalMode
        timerDisplayDurationSeekBar.isEnabled = isIntervalMode
    }

    /**
     * Sets up a color button.
     * Clicking the button will cycle through a predefined list of colors.
     * A more advanced implementation would use a color picker dialog.
     */
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
                    // Invalid color
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

    /**
     * Sets up a position spinner with the predefined position options.
     */
    private fun setupPositionSpinnerControl(spinner: Spinner, prefKey: String, defaultPosition: String) {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, positionOptions)
        spinner.adapter = adapter
        val currentPosition = prefs.getString(prefKey, defaultPosition) ?: defaultPosition
        spinner.setSelection(positionOptions.indexOf(currentPosition).coerceAtLeast(0))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updatePreview()
                markChanged()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}

        }
    }

    /**
     * Sets up a SeekBar for controlling font size.
     */
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

    // --- Specific Time SeekBar Setups ---

    /**
     * Sets up the SeekBar for Level 1's maximum time.
     */
    private fun setupLevel1TimeSeekBarControl() {
        currentLevel1MaxTimeMinutes = prefs.getInt(AppSettings.LEVEL_1_MAX_TIME_SECONDS, AppSettings.DEFAULT_LEVEL_1_MAX_TIME_SECONDS) / 60
        level1TimeSeekBar.max = MAX_LEVEL_1_TIME_MINUTES - MIN_LEVEL_1_TIME_MINUTES
        level1TimeSeekBar.progress = (currentLevel1MaxTimeMinutes - MIN_LEVEL_1_TIME_MINUTES).coerceIn(0, level1TimeSeekBar.max)
        level1TimeValue.text = "$currentLevel1MaxTimeMinutes min"

        level1TimeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentLevel1MaxTimeMinutes = MIN_LEVEL_1_TIME_MINUTES + progress
                level1TimeValue.text = "$currentLevel1MaxTimeMinutes min"
                // Add validation or interaction with Level 2 if necessary
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {markChanged()}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    /**
     * Sets up the SeekBar for Level 2's duration.
     */
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

    /**
     * Sets up spinners for selecting the daily reset time (hour and minute).
     */
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

    /**
     * Updates the preview display based on the currently selected settings for the active preview level.
     */
    private fun updatePreview() {
        val color: Int
        val position: String
        val fontSizeSp: Float

        when (currentPreviewLevel) {
            1 -> {
                color = currentLevel1Color
                position = level1PositionSpinner.selectedItem?.toString() ?: AppSettings.DEFAULT_LEVEL_1_POSITION
                fontSizeSp = MIN_FONT_SIZE_SP + level1FontSizeSeekBar.progress
                highlightPreviewHeader(previewLevel1Header)
            }
            2 -> {
                color = currentLevel2Color
                position = level2PositionSpinner.selectedItem?.toString() ?: AppSettings.DEFAULT_LEVEL_2_POSITION
                fontSizeSp = MIN_FONT_SIZE_SP + level2FontSizeSeekBar.progress
                highlightPreviewHeader(previewLevel2Header)
            }
            else -> { // Level 3
                color = currentLevel3Color
                position = level3PositionSpinner.selectedItem?.toString() ?: AppSettings.DEFAULT_LEVEL_3_POSITION
                fontSizeSp = MIN_FONT_SIZE_SP + level3FontSizeSeekBar.progress
                highlightPreviewHeader(previewLevel3Header)
            }
        }

        previewTimeText.text = "00:12:34" // Static example time for preview
        previewTimeText.setTextColor(color)
        previewTimeText.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp)

        // Update position of the preview text using ConstraintLayout
        val constraintSet = ConstraintSet()
        constraintSet.clone(previewLayout)
        // Clear previous constraints for the preview text to avoid conflicts
        constraintSet.clear(previewTimeText.id, ConstraintSet.START)
        constraintSet.clear(previewTimeText.id, ConstraintSet.END)
        constraintSet.clear(previewTimeText.id, ConstraintSet.TOP)
        constraintSet.clear(previewTimeText.id, ConstraintSet.BOTTOM)

        // Set new constraints based on the selected position
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

    /**
     * Highlights the header of the currently active preview level.
     */
    private fun highlightPreviewHeader(activeHeader: TextView) {
        val activeColor = ContextCompat.getColor(this, R.color.preview_header_active) // Define in colors.xml
        val inactiveColor = ContextCompat.getColor(this, R.color.preview_header_not_active)

        previewLevel1Header.setTextColor(if (activeHeader == previewLevel1Header) activeColor else inactiveColor)
        previewLevel2Header.setTextColor(if (activeHeader == previewLevel2Header) activeColor else inactiveColor)
        previewLevel3Header.setTextColor(if (activeHeader == previewLevel3Header) activeColor else inactiveColor)
    }

    /**
     * Validates the selected settings.
     * For example, ensures Level 1 time isn't excessively long, etc.
     * This is a basic validation, can be expanded.
     */
    private fun validateSettings(): Boolean {
        // Previous validations
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


    /**
     * Saves all the configured settings to SharedPreferences.
     */
    private fun saveSettings() {
        with(prefs.edit()) {
            // Level 1
            putInt(AppSettings.LEVEL_1_COLOR, currentLevel1Color)
            putString(AppSettings.LEVEL_1_POSITION, level1PositionSpinner.selectedItem.toString())
            putInt(AppSettings.LEVEL_1_FONT_SIZE, (MIN_FONT_SIZE_SP + level1FontSizeSeekBar.progress).toInt())
            putInt(AppSettings.LEVEL_1_MAX_TIME_SECONDS, currentLevel1MaxTimeMinutes * 60)
            putBoolean(AppSettings.LEVEL_1_BLINKING_ENABLED, currentLevel1BlinkingEnabled)

            Log.d("SettingsActivity", "Saving Level 1: Color=$AppSettings.level1Color, Position=$AppSettings.level1Position, FontSize=$AppSettings.level1FontSize, MaxTimeSeconds=$AppSettings.level1MaxTimeSeconds")

            // Level 2
            putInt(AppSettings.LEVEL_2_COLOR, currentLevel2Color)
            putString(AppSettings.LEVEL_2_POSITION, level2PositionSpinner.selectedItem.toString())
            putInt(AppSettings.LEVEL_2_FONT_SIZE, (MIN_FONT_SIZE_SP + level2FontSizeSeekBar.progress).toInt())
            putInt(AppSettings.LEVEL_2_DURATION_SECONDS, currentLevel2DurationMinutes * 60)
            putBoolean(AppSettings.LEVEL_2_BLINKING_ENABLED, currentLevel2BlinkingEnabled)

            Log.d("SettingsActivity", "Saving Level 2: Color=$AppSettings.level2Color, Position=$AppSettings.level2Position, FontSize=$AppSettings.level2FontSize, DurationSeconds=$AppSettings.level2DurationSeconds")

            // Level 3
            putInt(AppSettings.LEVEL_3_COLOR, currentLevel3Color)
            putString(AppSettings.LEVEL_3_POSITION, level3PositionSpinner.selectedItem.toString())
            putInt(AppSettings.LEVEL_3_FONT_SIZE, (MIN_FONT_SIZE_SP + level3FontSizeSeekBar.progress).toInt())
            putBoolean(AppSettings.LEVEL_3_BLINKING_ENABLED, level3BlinkingSwitch.isChecked) // Save the blinking state


            // Reset Time
            putInt(AppSettings.RESET_HOUR, resetHourSpinner.selectedItemPosition)
            putInt(AppSettings.RESET_MINUTE, resetMinuteSpinner.selectedItemPosition)

            // Timer Display Settings
            val selectedMode = if (timerDisplayModeSpinner.selectedItemPosition == 0) "always" else "interval"
            putString(AppSettings.TIMER_DISPLAY_MODE, selectedMode)
            putInt(AppSettings.TIMER_DISPLAY_INTERVAL_MINUTES, currentDisplayIntervalMinutes)
            putInt(AppSettings.TIMER_DISPLAY_DURATION_SECONDS, currentDisplayDurationSeconds)

            apply() // Apply changes asynchronously
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        super.onBackPressed()
        handleClose()
    }

    /**
     * Handles action bar item clicks, specifically the Up button.
     */
    // Also modify the existing onOptionsItemSelected method:
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            handleClose()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
