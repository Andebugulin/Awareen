package com.andebugulin.awareen.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.DisplayMetrics
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.andebugulin.awareen.R
import com.andebugulin.awareen.data.AppSettings
import com.andebugulin.awareen.data.ScreenTimeRepository
import com.andebugulin.awareen.data.SettingsRepository
import com.andebugulin.awareen.domain.OverlayDecisions
import com.andebugulin.awareen.overlay.LevelSettings
import com.andebugulin.awareen.overlay.OverlayController
import com.andebugulin.awareen.overlay.OverlaySettings
import com.andebugulin.awareen.service.ScreenTimeService
import com.google.android.material.button.MaterialButtonToggleGroup
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings apply live: every control writes through [SettingsRepository] the
 * moment it changes and the service is notified, so there is nothing to save
 * or discard. While the screen is visible a preview [OverlayController] shows
 * the real floating timer for the selected stage (the service's own overlay
 * steps aside meanwhile), so the user can drag it and watch every change.
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        const val MIN_FONT_SIZE_SP = 18f
        const val MAX_FONT_SIZE_SP = 60f
        const val MIN_LEVEL_1_TIME_MINUTES = 5
        const val MAX_LEVEL_1_TIME_MINUTES = 60
        const val MIN_LEVEL_2_DURATION_MINUTES = 15
        const val MAX_LEVEL_2_DURATION_MINUTES = 60

        private const val POSITION_CUSTOM = "Custom (dragged)"
        private val PRESET_POSITIONS = listOf(
            "Top Left", "Top Center", "Top Right",
            "Middle Left", "Middle Center", "Middle Right",
            "Bottom Left", "Bottom Center", "Bottom Right"
        )

        private const val TAG = "SettingsActivity"
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var screenTimeRepository: ScreenTimeRepository

    /** The persisted settings; every edit replaces this and writes it through. */
    private lateinit var settings: OverlaySettings

    /** Unsaved settings shown on the preview while a color dialog is open. */
    private var previewOverride: OverlaySettings? = null

    private var selectedStage = 1

    // True while controls are being filled from [settings], so their
    // listeners don't write the same values straight back.
    private var bindingControls = false

    private var previewController: OverlayController? = null
    private val previewHandler = Handler(Looper.getMainLooper())
    private var previewTick = 0
    private val previewRunnable = object : Runnable {
        override fun run() {
            renderPreview()
            previewTick++
            previewHandler.postDelayed(this, 1000)
        }
    }

    private lateinit var liveHintTextView: TextView
    private lateinit var stageToggleGroup: MaterialButtonToggleGroup
    private lateinit var stageTabs: List<Button>
    private lateinit var stageRangeTextView: TextView
    private lateinit var stageNameInput: EditText
    private lateinit var stageTextColorButton: Button
    private lateinit var stageBackgroundColorButton: Button
    private lateinit var stagePositionSpinner: Spinner
    private lateinit var stageFontSizeSeekBar: SeekBar
    private lateinit var stageFontSizeValue: TextView
    private lateinit var stageBlinkingSwitch: SwitchCompat
    private lateinit var stageThresholdRow: View
    private lateinit var stageThresholdLabel: TextView
    private lateinit var stageThresholdSeekBar: SeekBar
    private lateinit var stageThresholdValue: TextView

    private lateinit var timerDisplayModeToggleGroup: MaterialButtonToggleGroup
    private lateinit var timerIntervalGrid: View
    private lateinit var timerDisplayIntervalSeekBar: SeekBar
    private lateinit var timerDisplayIntervalValue: TextView
    private lateinit var timerDisplayDurationSeekBar: SeekBar
    private lateinit var timerDisplayDurationValue: TextView
    private lateinit var overlayCornerStyleToggleGroup: MaterialButtonToggleGroup
    private lateinit var overlayBorderSwitch: SwitchCompat

    private lateinit var resetHourSpinner: Spinner
    private lateinit var resetMinuteSpinner: Spinner

    // =========================================================================
    // SAF launchers for export/import
    // =========================================================================

    private val exportSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { exportSettingsToUri(it) }
    }

    private val importSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { importSettingsFromUri(it) }
    }

    // =========================================================================
    // LIFECYCLE
    // =========================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        window.navigationBarColor = ContextCompat.getColor(this, R.color.app_background)

        prefs = getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE)
        settingsRepository = SettingsRepository(this, prefs)
        screenTimeRepository = ScreenTimeRepository(prefs)
        settings = settingsRepository.loadOverlaySettings()

        initializeViews()
        setupStageControls()
        setupTimerDisplayControls()
        setupResetTimeControls()
        setupHelpButtons()

        findViewById<ImageButton>(R.id.closeButton).setOnClickListener { finish() }
        findViewById<Button>(R.id.doneButton).setOnClickListener { finish() }
        findViewById<Button>(R.id.exportSettingsButton).setOnClickListener {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
            exportSettingsLauncher.launch("awareen_settings_$timestamp.json")
        }
        findViewById<Button>(R.id.importSettingsButton).setOnClickListener {
            importSettingsLauncher.launch(arrayOf("application/json", "*/*"))
        }

        bindAllControls()
    }

    override fun onResume() {
        super.onResume()
        startPreview()
    }

    override fun onPause() {
        super.onPause()
        stopPreview()
    }

    private fun initializeViews() {
        liveHintTextView = findViewById(R.id.liveHintTextView)
        stageToggleGroup = findViewById(R.id.stageToggleGroup)
        stageTabs = listOf(findViewById(R.id.stageTab1), findViewById(R.id.stageTab2), findViewById(R.id.stageTab3))
        stageRangeTextView = findViewById(R.id.stageRangeTextView)
        stageNameInput = findViewById(R.id.stageNameInput)
        stageTextColorButton = findViewById(R.id.stageTextColorButton)
        stageBackgroundColorButton = findViewById(R.id.stageBackgroundColorButton)
        stagePositionSpinner = findViewById(R.id.stagePositionSpinner)
        stageFontSizeSeekBar = findViewById(R.id.stageFontSizeSeekBar)
        stageFontSizeValue = findViewById(R.id.stageFontSizeValue)
        stageBlinkingSwitch = findViewById(R.id.stageBlinkingSwitch)
        stageThresholdRow = findViewById(R.id.stageThresholdRow)
        stageThresholdLabel = findViewById(R.id.stageThresholdLabel)
        stageThresholdSeekBar = findViewById(R.id.stageThresholdSeekBar)
        stageThresholdValue = findViewById(R.id.stageThresholdValue)

        timerDisplayModeToggleGroup = findViewById(R.id.timerDisplayModeToggleGroup)
        timerIntervalGrid = findViewById(R.id.timerIntervalGrid)
        timerDisplayIntervalSeekBar = findViewById(R.id.timerDisplayIntervalSeekBar)
        timerDisplayIntervalValue = findViewById(R.id.timerDisplayIntervalValue)
        timerDisplayDurationSeekBar = findViewById(R.id.timerDisplayDurationSeekBar)
        timerDisplayDurationValue = findViewById(R.id.timerDisplayDurationValue)
        overlayCornerStyleToggleGroup = findViewById(R.id.overlayCornerStyleToggleGroup)
        overlayBorderSwitch = findViewById(R.id.overlayBorderSwitch)

        resetHourSpinner = findViewById(R.id.resetHourSpinner)
        resetMinuteSpinner = findViewById(R.id.resetMinuteSpinner)
    }

    // =========================================================================
    // LIVE PREVIEW
    // =========================================================================

    private fun startPreview() {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            liveHintTextView.text =
                "Allow Awareen to display over other apps to see the timer live while you edit it."
            return
        }
        if (previewController == null) {
            previewController = OverlayController(this, prefs, isPreview = true) {
                // The user dragged the timer: the spinner should now say so.
                bindPositionSpinner()
            }.also { it.create(previewHandler, settings.level(selectedStage).position) }
        }
        previewTick = 0
        previewHandler.removeCallbacks(previewRunnable)
        previewHandler.post(previewRunnable)
    }

    private fun stopPreview() {
        previewHandler.removeCallbacks(previewRunnable)
        previewController?.destroy()
        previewController = null
    }

    /**
     * Shows the selected stage. Uses today's real total when it already falls
     * in that stage; otherwise counts up from the stage's start, so blinking
     * and the digits look the way they will for real.
     */
    private fun renderPreview() {
        val shown = previewOverride ?: settings
        val stageStart = when (selectedStage) {
            1 -> 0
            2 -> shown.level1MaxTimeSeconds
            else -> shown.level1MaxTimeSeconds + shown.level2DurationSeconds
        }
        val live = screenTimeRepository.getTodayScreenTime()
        val seconds = if (stageOf(live, shown) == selectedStage) live else stageStart + previewTick
        previewController?.render(seconds, shown)
    }

    private fun stageOf(seconds: Int, s: OverlaySettings) =
        OverlayDecisions.levelFor(seconds, s.level1MaxTimeSeconds, s.level2DurationSeconds)

    // =========================================================================
    // WRITE-THROUGH
    // =========================================================================

    private fun commit(newSettings: OverlaySettings) {
        settings = newSettings
        previewOverride = null
        settingsRepository.saveOverlaySettings(newSettings)
        settingsRepository.notifySettingsUpdated()
        renderPreview()
    }

    private fun commitStage(transform: (LevelSettings) -> LevelSettings) {
        commit(settings.withLevel(selectedStage, transform(settings.level(selectedStage))))
    }

    private fun stageName(level: Int): String =
        settings.level(level).name.ifBlank { AppSettings.DEFAULT_LEVEL_NAMES[level - 1] }

    // =========================================================================
    // BINDING — fill controls from [settings]
    // =========================================================================

    private fun bindAllControls() {
        bindingControls = true
        stageTabs.forEachIndexed { i, tab -> tab.text = stageName(i + 1) }
        stageToggleGroup.check(stageTabs[selectedStage - 1].id)

        timerDisplayModeToggleGroup.check(buttonIdForMode(settings.timerDisplayMode))
        timerDisplayIntervalSeekBar.progress =
            settings.timerDisplayIntervalMinutes - AppSettings.MIN_DISPLAY_INTERVAL_MINUTES
        timerDisplayIntervalValue.text = "${settings.timerDisplayIntervalMinutes} min"
        timerDisplayDurationSeekBar.progress =
            settings.timerDisplayDurationSeconds - AppSettings.MIN_DISPLAY_DURATION_SECONDS
        timerDisplayDurationValue.text = "${settings.timerDisplayDurationSeconds} sec"
        timerIntervalGrid.visibility =
            if (settings.timerDisplayMode == AppSettings.MODE_INTERVAL) View.VISIBLE else View.GONE
        overlayCornerStyleToggleGroup.check(
            if (settings.cornerStyle == AppSettings.CORNER_STYLE_ROUNDED) R.id.overlayCornerStyleRounded
            else R.id.overlayCornerStyleSquare
        )
        overlayBorderSwitch.isChecked = settings.borderEnabled

        resetHourSpinner.setSelection(settingsRepository.getResetHour())
        resetMinuteSpinner.setSelection(settingsRepository.getResetMinute())
        bindingControls = false

        bindStageControls()
    }

    private fun bindStageControls() {
        bindingControls = true
        val stage = settings.level(selectedStage)

        if (stageNameInput.text.toString() != stage.name) stageNameInput.setText(stage.name)
        stageNameInput.hint = AppSettings.DEFAULT_LEVEL_NAMES[selectedStage - 1]
        styleColorButton(stageTextColorButton, stage.color)
        styleColorButton(stageBackgroundColorButton, stage.backgroundColor)
        stageFontSizeSeekBar.progress = (stage.fontSize - MIN_FONT_SIZE_SP).toInt()
        stageFontSizeValue.text = "${stage.fontSize.toInt()}sp"
        stageBlinkingSwitch.isChecked = stage.blinkingEnabled

        when (selectedStage) {
            1 -> {
                stageThresholdRow.visibility = View.VISIBLE
                stageThresholdLabel.text = "Ends after:"
                stageThresholdSeekBar.max = MAX_LEVEL_1_TIME_MINUTES - MIN_LEVEL_1_TIME_MINUTES
                stageThresholdSeekBar.progress = settings.level1MaxTimeSeconds / 60 - MIN_LEVEL_1_TIME_MINUTES
            }
            2 -> {
                stageThresholdRow.visibility = View.VISIBLE
                stageThresholdLabel.text = "Lasts:"
                stageThresholdSeekBar.max = MAX_LEVEL_2_DURATION_MINUTES - MIN_LEVEL_2_DURATION_MINUTES
                stageThresholdSeekBar.progress = settings.level2DurationSeconds / 60 - MIN_LEVEL_2_DURATION_MINUTES
            }
            else -> stageThresholdRow.visibility = View.GONE
        }
        bindStageRangeText()
        bindingControls = false

        bindPositionSpinner()
    }

    private fun bindStageRangeText() {
        val l1 = settings.level1MaxTimeSeconds / 60
        val l2 = l1 + settings.level2DurationSeconds / 60
        stageRangeTextView.text = when (selectedStage) {
            1 -> "From 0 to $l1 min of screen time"
            2 -> "From $l1 to $l2 min of screen time"
            else -> "From $l2 min until the daily reset"
        }
        when (selectedStage) {
            1 -> stageThresholdValue.text = "$l1 min"
            2 -> stageThresholdValue.text = "${l2 - l1} min"
        }
    }

    /**
     * "Custom (dragged)" is only offered while the stage actually has a
     * dragged position; otherwise the list is just the presets.
     */
    private fun bindPositionSpinner() {
        val hasCustom = prefs.getBoolean(customKey(selectedStage), false)
        val options = if (hasCustom) listOf(POSITION_CUSTOM) + PRESET_POSITIONS else PRESET_POSITIONS
        stagePositionSpinner.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
        val index = if (hasCustom) 0 else PRESET_POSITIONS.indexOf(settings.level(selectedStage).position)
        stagePositionSpinner.setSelection(index.coerceAtLeast(0))
    }

    private fun customKey(level: Int) = when (level) {
        1 -> OverlayController.LEVEL_1_USE_CUSTOM
        2 -> OverlayController.LEVEL_2_USE_CUSTOM
        else -> OverlayController.LEVEL_3_USE_CUSTOM
    }

    /** A color button is a swatch of its color, labelled with the hex value in a readable ink. */
    private fun styleColorButton(button: Button, color: Int) {
        button.setBackgroundColor(color)
        button.text = if (Color.alpha(color) == 255) String.format("#%06X", 0xFFFFFF and color)
        else String.format("#%06X · %d%%", 0xFFFFFF and color, Color.alpha(color) * 100 / 255)
        // Judge legibility against what the user actually sees: the swatch
        // composited over the card surface.
        val seen = ColorUtils.compositeColors(color, ContextCompat.getColor(this, R.color.surface))
        button.setTextColor(if (ColorUtils.calculateLuminance(seen) > 0.4) Color.BLACK else Color.WHITE)
    }

    // =========================================================================
    // LISTENERS — set up once; they edit the selected stage
    // =========================================================================

    private fun setupStageControls() {
        stageToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || bindingControls) return@addOnButtonCheckedListener
            val stage = stageTabs.indexOfFirst { it.id == checkedId } + 1
            if (stage == 0 || stage == selectedStage) return@addOnButtonCheckedListener
            selectedStage = stage
            previewTick = 0
            bindStageControls()
            renderPreview()
        }

        stageNameInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (bindingControls) return
                val name = s.toString().trim()
                if (name == settings.level(selectedStage).name) return
                commitStage { it.copy(name = name) }
                stageTabs[selectedStage - 1].text = stageName(selectedStage)
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        stageTextColorButton.setOnClickListener {
            val stage = settings.level(selectedStage)
            showColorPickerDialog(
                stage.color,
                allowAlpha = false,
                onPreview = { c -> previewStage(stage.copy(color = c)) },
                onSelected = { c -> commitStage { it.copy(color = c) }; bindStageControls() },
            )
        }

        stageBackgroundColorButton.setOnClickListener {
            val stage = settings.level(selectedStage)
            showColorPickerDialog(
                stage.backgroundColor,
                allowAlpha = true,
                onPreview = { c -> previewStage(stage.copy(backgroundColor = c)) },
                onSelected = { c -> commitStage { it.copy(backgroundColor = c) }; bindStageControls() },
            )
        }

        stagePositionSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = parent?.getItemAtPosition(position)?.toString() ?: return
                if (selected == POSITION_CUSTOM) return
                val hadCustom = prefs.getBoolean(customKey(selectedStage), false)
                if (!hadCustom && selected == settings.level(selectedStage).position) return
                // Picking a preset drops the dragged position for this stage.
                if (hadCustom) settingsRepository.clearCustomPosition(selectedStage)
                commitStage { it.copy(position = selected) }
                if (hadCustom) bindPositionSpinner()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        stageFontSizeSeekBar.max = (MAX_FONT_SIZE_SP - MIN_FONT_SIZE_SP).toInt()
        stageFontSizeSeekBar.setOnSeekBarChangeListener(onUserProgress { progress ->
            val size = MIN_FONT_SIZE_SP + progress
            stageFontSizeValue.text = "${size.toInt()}sp"
            commitStage { it.copy(fontSize = size) }
        })

        stageBlinkingSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (bindingControls) return@setOnCheckedChangeListener
            commitStage { it.copy(blinkingEnabled = isChecked) }
        }

        stageThresholdSeekBar.setOnSeekBarChangeListener(onUserProgress { progress ->
            when (selectedStage) {
                1 -> commit(settings.copy(level1MaxTimeSeconds = (MIN_LEVEL_1_TIME_MINUTES + progress) * 60))
                2 -> commit(settings.copy(level2DurationSeconds = (MIN_LEVEL_2_DURATION_MINUTES + progress) * 60))
            }
            bindStageRangeText()
        })
    }

    /** Preview an unsaved edit of the selected stage without writing it. */
    private fun previewStage(stage: LevelSettings) {
        previewOverride = settings.withLevel(selectedStage, stage)
        renderPreview()
    }

    private fun onUserProgress(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) onChange(progress)
        }
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }

    private fun setupTimerDisplayControls() {
        timerDisplayModeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || bindingControls) return@addOnButtonCheckedListener
            val mode = modeForButtonId(checkedId) ?: return@addOnButtonCheckedListener
            if (mode == settings.timerDisplayMode) return@addOnButtonCheckedListener
            commit(settings.copy(timerDisplayMode = mode))
            timerIntervalGrid.visibility = if (mode == AppSettings.MODE_INTERVAL) View.VISIBLE else View.GONE
        }

        timerDisplayIntervalSeekBar.max = AppSettings.MAX_DISPLAY_INTERVAL_MINUTES - AppSettings.MIN_DISPLAY_INTERVAL_MINUTES
        timerDisplayIntervalSeekBar.setOnSeekBarChangeListener(onUserProgress { progress ->
            val minutes = AppSettings.MIN_DISPLAY_INTERVAL_MINUTES + progress
            timerDisplayIntervalValue.text = "$minutes min"
            commit(settings.copy(timerDisplayIntervalMinutes = minutes))
        })

        timerDisplayDurationSeekBar.max = AppSettings.MAX_DISPLAY_DURATION_SECONDS - AppSettings.MIN_DISPLAY_DURATION_SECONDS
        timerDisplayDurationSeekBar.setOnSeekBarChangeListener(onUserProgress { progress ->
            val seconds = AppSettings.MIN_DISPLAY_DURATION_SECONDS + progress
            timerDisplayDurationValue.text = "$seconds sec"
            commit(settings.copy(timerDisplayDurationSeconds = seconds))
        })

        overlayCornerStyleToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || bindingControls) return@addOnButtonCheckedListener
            val style = if (checkedId == R.id.overlayCornerStyleRounded) AppSettings.CORNER_STYLE_ROUNDED
            else AppSettings.CORNER_STYLE_SQUARE
            if (style != settings.cornerStyle) commit(settings.copy(cornerStyle = style))
        }

        overlayBorderSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (!bindingControls) commit(settings.copy(borderEnabled = isChecked))
        }
    }

    private fun buttonIdForMode(mode: String): Int = when (mode) {
        AppSettings.MODE_ALWAYS -> R.id.timerDisplayModeAlways
        AppSettings.MODE_NEVER -> R.id.timerDisplayModeNever
        else -> R.id.timerDisplayModeInterval
    }

    private fun modeForButtonId(id: Int): String? = when (id) {
        R.id.timerDisplayModeAlways -> AppSettings.MODE_ALWAYS
        R.id.timerDisplayModeInterval -> AppSettings.MODE_INTERVAL
        R.id.timerDisplayModeNever -> AppSettings.MODE_NEVER
        else -> null
    }

    private fun setupResetTimeControls() {
        resetHourSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, Array(24) { String.format("%02d", it) })
        resetMinuteSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, Array(60) { String.format("%02d", it) })

        // Spinners report selections asynchronously (including the ones made
        // while binding), so compare against what's stored instead of
        // relying on [bindingControls].
        val listener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val hour = resetHourSpinner.selectedItemPosition
                val minute = resetMinuteSpinner.selectedItemPosition
                if (hour == settingsRepository.getResetHour() && minute == settingsRepository.getResetMinute()) return
                settingsRepository.saveResetTime(hour, minute)
                settingsRepository.notifySettingsUpdated() // reschedules the reset alarm
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        resetHourSpinner.onItemSelectedListener = listener
        resetMinuteSpinner.onItemSelectedListener = listener
    }

    // =========================================================================
    // HELP
    // =========================================================================

    private fun setupHelpButtons() {
        findViewById<ImageButton>(R.id.helpStages).setOnClickListener {
            showHelpDialog(
                "Stages",
                "Your day is split into three stages, each with its own name, colors, position, size and blinking.\n\n" +
                    "• ${stageName(1)}: from 0 minutes until the time you set.\n" +
                    "• ${stageName(2)}: starts where ${stageName(1)} ends and lasts as long as you set.\n" +
                    "• ${stageName(3)}: everything after that, until the daily reset.\n\n" +
                    "Tap a stage to edit it. The timer on your screen switches to that stage so you can see it, and you can drag it to any spot."
            )
        }
        findViewById<ImageButton>(R.id.helpTimerDisplay).setOnClickListener {
            showHelpDialog(
                "When it shows",
                "Controls when the floating timer appears on top of other apps.\n\n" +
                    "• Always: visible whenever the screen is on.\n" +
                    "• Interval: appears briefly at a fixed interval (e.g. every 1 min for 5 sec).\n" +
                    "• Never: tracking stays on but the timer is hidden. Pick this if you only want the home-screen widget.\n\n" +
                    "While you're on this screen the timer always shows, so you can style it."
            )
        }
        findViewById<ImageButton>(R.id.helpResetTime).setOnClickListener {
            showHelpDialog(
                "Daily reset",
                "The time of day when your screen-time counter resets to zero. Reset is enforced even when the app is asleep or the device is in Doze."
            )
        }
        findViewById<ImageButton>(R.id.helpImportExport).setOnClickListener {
            showHelpDialog(
                "Import & export",
                "Save all your settings (stages, colors, positions, reset time, display mode) to a JSON file, or restore them from one. Useful for backing up your setup or moving to a new device."
            )
        }
    }

    private fun showHelpDialog(title: String, body: String) {
        AlertDialog.Builder(this, R.style.CustomAlertDialog)
            .setTitle(title)
            .setMessage(body)
            .setPositiveButton("Got it", null)
            .create()
            .apply {
                show()
                getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.accent_primary))
            }
    }

    // =========================================================================
    // COLOR PICKER
    // =========================================================================

    /**
     * HSV picker with hex input. [onPreview] fires on every change so the live
     * timer follows the picker; [onSelected] fires on "Select". Cancelling
     * drops the preview and the timer returns to the saved colors.
     * [allowAlpha] adds an opacity slider (used for the badge background).
     */
    private fun showColorPickerDialog(
        currentColor: Int,
        allowAlpha: Boolean,
        onPreview: (Int) -> Unit,
        onSelected: (Int) -> Unit,
    ) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_color_picker, null)
        val colorPickerView = dialogView.findViewById<ColorPickerView>(R.id.colorPickerView)
        val hexInput = dialogView.findViewById<EditText>(R.id.hexInput)
        val previewColor = dialogView.findViewById<View>(R.id.previewColor)
        val randomButton = dialogView.findViewById<Button>(R.id.randomColorButton)
        val opacityRow = dialogView.findViewById<View>(R.id.opacityRow)
        val opacitySeekBar = dialogView.findViewById<SeekBar>(R.id.opacitySeekBar)
        val opacityValue = dialogView.findViewById<TextView>(R.id.opacityValue)

        var alpha = if (allowAlpha) Color.alpha(currentColor) else 255
        var updatingFromPicker = false
        var updatingFromHex = false

        fun result(): Int = ColorUtils.setAlphaComponent(colorPickerView.getColor(), alpha)
        fun refresh() {
            previewColor.setBackgroundColor(result())
            onPreview(result())
        }

        colorPickerView.setColor(currentColor)
        hexInput.setText(String.format("#%06X", 0xFFFFFF and currentColor))
        previewColor.setBackgroundColor(currentColor)

        if (allowAlpha) {
            opacityRow.visibility = View.VISIBLE
            opacitySeekBar.progress = alpha * 100 / 255
            opacityValue.text = "${opacitySeekBar.progress}%"
            opacitySeekBar.setOnSeekBarChangeListener(onUserProgress { progress ->
                alpha = progress * 255 / 100
                opacityValue.text = "$progress%"
                refresh()
            })
        }

        colorPickerView.listener = object : ColorPickerView.OnColorChangedListener {
            override fun onColorChanged(color: Int) {
                if (updatingFromHex) return
                updatingFromPicker = true
                hexInput.setText(String.format("#%06X", 0xFFFFFF and color))
                updatingFromPicker = false
                refresh()
            }
        }

        randomButton.setOnClickListener {
            val randomColor = Color.rgb((0..255).random(), (0..255).random(), (0..255).random())
            colorPickerView.setColor(randomColor)
            updatingFromPicker = true
            hexInput.setText(String.format("#%06X", 0xFFFFFF and randomColor))
            updatingFromPicker = false
            refresh()
        }

        hexInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (updatingFromPicker) return
                try {
                    val color = Color.parseColor(s.toString())
                    updatingFromHex = true
                    colorPickerView.setColor(color)
                    updatingFromHex = false
                    refresh()
                } catch (e: Exception) {
                    // ignore invalid input while typing
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        var selected = false
        AlertDialog.Builder(this, R.style.CustomAlertDialog)
            .setView(dialogView)
            .setPositiveButton("Select") { _, _ ->
                selected = true
                onSelected(result())
            }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener {
                if (!selected) {
                    previewOverride = null
                    renderPreview()
                }
            }
            .create()
            .apply {
                show()
                val accentColor = ContextCompat.getColor(this@SettingsActivity, R.color.accent_primary)
                getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(accentColor)
                getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(accentColor)
            }
    }

    // =========================================================================
    // EXPORT / IMPORT SETTINGS
    // =========================================================================

    private fun currentScreenSize(): Pair<Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            @Suppress("DEPRECATION")
            val display = wm.defaultDisplay
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            metrics.widthPixels to metrics.heightPixels
        }
    }

    private fun hex(color: Int) = String.format("#%06X", 0xFFFFFF and color)
    private fun hexWithAlpha(color: Int) = String.format("#%08X", color)

    private fun buildSettingsJson(): JSONObject {
        val root = JSONObject()
        // v3: custom_positions store fractions (fx/fy) instead of pixels.
        // Import still accepts the v2 absolute-pixel format for backward compat.
        // Stage names and background colors are optional additions to v3.
        root.put("version", 3)
        root.put("app", "awareen")
        root.put("type", "settings")
        root.put("exported_at", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()))

        val s = JSONObject()
        for (level in 1..3) {
            val stage = settings.level(level)
            s.put("level_${level}_name", stage.name)
            s.put("level_${level}_color", hex(stage.color))
            s.put("level_${level}_background_color", hexWithAlpha(stage.backgroundColor))
            s.put("level_${level}_position", stage.position)
            s.put("level_${level}_font_size", stage.fontSize.toInt())
            s.put("level_${level}_blinking_enabled", stage.blinkingEnabled)
        }
        s.put("level_1_max_time_minutes", settings.level1MaxTimeSeconds / 60)
        s.put("level_2_duration_minutes", settings.level2DurationSeconds / 60)

        s.put("reset_hour", settingsRepository.getResetHour())
        s.put("reset_minute", settingsRepository.getResetMinute())

        s.put("timer_display_mode", settings.timerDisplayMode)
        s.put("timer_display_interval_minutes", settings.timerDisplayIntervalMinutes)
        s.put("timer_display_duration_seconds", settings.timerDisplayDurationSeconds)
        s.put("overlay_corner_style", settings.cornerStyle)
        s.put("overlay_border_enabled", settings.borderEnabled)

        // Custom per-level drag positions as fractions of screen [0f, 1f].
        val positions = JSONObject()
        for (level in 1..3) {
            val (useKey, fxKey, fyKey) = when (level) {
                1 -> Triple(OverlayController.LEVEL_1_USE_CUSTOM, OverlayController.LEVEL_1_CUSTOM_FX, OverlayController.LEVEL_1_CUSTOM_FY)
                2 -> Triple(OverlayController.LEVEL_2_USE_CUSTOM, OverlayController.LEVEL_2_CUSTOM_FX, OverlayController.LEVEL_2_CUSTOM_FY)
                else -> Triple(OverlayController.LEVEL_3_USE_CUSTOM, OverlayController.LEVEL_3_CUSTOM_FX, OverlayController.LEVEL_3_CUSTOM_FY)
            }
            if (prefs.getBoolean(useKey, false)) {
                val posObj = JSONObject()
                posObj.put("fx", prefs.getFloat(fxKey, 0f).toDouble())
                posObj.put("fy", prefs.getFloat(fyKey, 0f).toDouble())
                positions.put("level_$level", posObj)
            }
        }
        if (positions.length() > 0) {
            s.put("custom_positions", positions)
        }

        root.put("settings", s)
        return root
    }

    private fun exportSettingsToUri(uri: Uri) {
        try {
            val json = buildSettingsJson().toString(2)
            contentResolver.openOutputStream(uri)?.use { os ->
                os.write(json.toByteArray())
            }
            Toast.makeText(this, "Settings exported", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Export failed: ${e.message}", e)
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** Merge stage [level] from the JSON over [current]; omitted fields are kept. */
    private fun mergeLevel(s: JSONObject, level: Int, current: LevelSettings): LevelSettings {
        fun color(key: String, fallback: Int) =
            s.optString(key).takeIf { it.isNotEmpty() }?.let { Color.parseColor(it) } ?: fallback
        return LevelSettings(
            name = s.optString("level_${level}_name").takeIf { it.isNotEmpty() } ?: current.name,
            color = color("level_${level}_color", current.color),
            backgroundColor = color("level_${level}_background_color", current.backgroundColor),
            position = s.optString("level_${level}_position").takeIf { it in PRESET_POSITIONS } ?: current.position,
            fontSize = if (s.has("level_${level}_font_size")) s.getInt("level_${level}_font_size").toFloat() else current.fontSize,
            blinkingEnabled = if (s.has("level_${level}_blinking_enabled")) s.getBoolean("level_${level}_blinking_enabled") else current.blinkingEnabled,
        )
    }

    private fun importSettingsFromUri(uri: Uri) {
        try {
            val jsonString = contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                ?: throw Exception("Could not read file")

            val root = JSONObject(jsonString)

            if (root.optString("app") != "awareen" || root.optString("type") != "settings") {
                Toast.makeText(this, "Not a valid Awareen settings file", Toast.LENGTH_LONG).show()
                return
            }

            val s = root.getJSONObject("settings")

            // Merge JSON over current values — preserves any field the JSON omits
            // (backward-compat with older exports).
            val current = settingsRepository.loadOverlaySettings()
            val merged = OverlaySettings(
                level1 = mergeLevel(s, 1, current.level1),
                level1MaxTimeSeconds = if (s.has("level_1_max_time_minutes")) s.getInt("level_1_max_time_minutes") * 60 else current.level1MaxTimeSeconds,
                level2 = mergeLevel(s, 2, current.level2),
                level2DurationSeconds = if (s.has("level_2_duration_minutes")) s.getInt("level_2_duration_minutes") * 60 else current.level2DurationSeconds,
                level3 = mergeLevel(s, 3, current.level3),
                timerDisplayMode = s.optString("timer_display_mode").takeIf { it.isNotEmpty() } ?: current.timerDisplayMode,
                timerDisplayIntervalMinutes = if (s.has("timer_display_interval_minutes")) s.getInt("timer_display_interval_minutes") else current.timerDisplayIntervalMinutes,
                timerDisplayDurationSeconds = if (s.has("timer_display_duration_seconds")) s.getInt("timer_display_duration_seconds") else current.timerDisplayDurationSeconds,
                cornerStyle = s.optString("overlay_corner_style").takeIf { it.isNotEmpty() } ?: current.cornerStyle,
                borderEnabled = if (s.has("overlay_border_enabled")) s.getBoolean("overlay_border_enabled") else current.borderEnabled,
            )
            settingsRepository.saveOverlaySettings(merged)

            settingsRepository.saveResetTime(
                if (s.has("reset_hour")) s.getInt("reset_hour") else settingsRepository.getResetHour(),
                if (s.has("reset_minute")) s.getInt("reset_minute") else settingsRepository.getResetMinute(),
            )

            // Custom drag positions. v3 stores fractions (fx/fy); v2 stored
            // absolute pixels (x/y) — convert those using the current screen
            // size so old export files still import meaningfully.
            val positions = s.optJSONObject("custom_positions")
            val (importScreenW, importScreenH) = currentScreenSize()
            for (level in 1..3) {
                val posObj = positions?.optJSONObject("level_$level")
                if (posObj != null) {
                    val fx: Float
                    val fy: Float
                    if (posObj.has("fx") && posObj.has("fy")) {
                        fx = posObj.getDouble("fx").toFloat()
                        fy = posObj.getDouble("fy").toFloat()
                    } else {
                        // Legacy v2 absolute pixels — convert to fractions.
                        val x = posObj.optInt("x", 0)
                        val y = posObj.optInt("y", 0)
                        fx = if (importScreenW > 0) x.toFloat() / importScreenW else 0f
                        fy = if (importScreenH > 0) y.toFloat() / importScreenH else 0f
                    }
                    settingsRepository.setCustomPosition(level, fx, fy)
                } else {
                    settingsRepository.clearCustomPosition(level)
                }
            }

            // Notify the running service to reload settings.
            // Belt-and-suspenders: broadcast + startService (triggers onStartCommand
            // which calls loadSettings on an already-running service, no restart).
            settingsRepository.notifySettingsUpdated()

            // Poke the service directly — onStartCommand reloads settings + ensures overlay
            try {
                val serviceIntent = Intent(this, ScreenTimeService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            } catch (e: Exception) {
                // Service not running — that's fine, don't start it
                Log.d(TAG, "Service not running, skipping poke: ${e.message}")
            }

            Toast.makeText(this, "Settings imported!", Toast.LENGTH_SHORT).show()

            settings = settingsRepository.loadOverlaySettings()
            bindAllControls()
            renderPreview()

        } catch (e: Exception) {
            Log.e(TAG, "Import failed: ${e.message}", e)
            Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
