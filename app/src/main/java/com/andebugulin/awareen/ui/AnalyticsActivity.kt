package com.andebugulin.awareen.ui

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.andebugulin.awareen.R
import com.andebugulin.awareen.data.ScreenTimeRepository
import com.andebugulin.awareen.data.SettingsRepository
import com.andebugulin.awareen.domain.AnalyticsKeys
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.*
import kotlin.collections.ArrayList
import kotlin.math.abs

class AnalyticsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AnalyticsActivity"
    }

    private lateinit var repo: ScreenTimeRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var daySplitChartView: DaySplitChartView
    private lateinit var daySplitLegend: LinearLayout
    private lateinit var daySplitInsightTextView: TextView
    private lateinit var recyclerView: RecyclerView
    private lateinit var averageTextView: TextView
    private lateinit var trendTextView: TextView
    private lateinit var progressTextView: TextView
    private lateinit var lifetimeYearsTextView: TextView
    private lateinit var lifetimeDaysTextView: TextView
    private lateinit var adapter: AnalyticsAdapter

    private lateinit var toggleDailyLogButton: Button
    private lateinit var dailyLogContainer: View

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { exportAnalyticsToUri(it) }
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { importAnalyticsFromUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_analytics)
        window.navigationBarColor = ContextCompat.getColor(this, R.color.app_background)

        repo = ScreenTimeRepository(this)
        settingsRepository = SettingsRepository(this)

        initializeViews()
        loadAnalyticsData()
        updateDaySplit()
    }

    private fun initializeViews() {
        val backButton = findViewById<ImageButton>(R.id.backButton)
        averageTextView = findViewById(R.id.averageTextView)
        trendTextView = findViewById(R.id.trendTextView)
        progressTextView = findViewById(R.id.progressTextView)
        lifetimeYearsTextView = findViewById(R.id.lifetimeYearsTextView)
        lifetimeDaysTextView = findViewById(R.id.lifetimeDaysTextView)
        recyclerView = findViewById(R.id.analyticsRecyclerView)
        toggleDailyLogButton = findViewById(R.id.toggleDailyLogButton)
        dailyLogContainer = findViewById(R.id.dailyLogContainer)

        backButton.setOnClickListener { finish() }

        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = AnalyticsAdapter()
        recyclerView.adapter = adapter

        daySplitChartView = findViewById(R.id.daySplitChartView)
        daySplitLegend = findViewById(R.id.daySplitLegend)
        daySplitInsightTextView = findViewById(R.id.daySplitInsightTextView)
        daySplitChartView.setTextColors(
            ContextCompat.getColor(this, R.color.text_primary),
            ContextCompat.getColor(this, R.color.text_secondary),
        )
        daySplitChartView.setValueTypeface(androidx.core.content.res.ResourcesCompat.getFont(this, R.font.fraunces))
        findViewById<Button>(R.id.editDaySplitButton).setOnClickListener { showDaySplitDialog() }

        toggleDailyLogButton.setOnClickListener {
            val showing = dailyLogContainer.visibility == View.VISIBLE
            dailyLogContainer.visibility = if (showing) View.GONE else View.VISIBLE
            toggleDailyLogButton.text = if (showing) "View Daily Log" else "Hide Daily Log"
        }

        findViewById<Button>(R.id.exportAnalyticsButton).setOnClickListener {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
            exportLauncher.launch("awareen_analytics_$timestamp.json")
        }

        findViewById<Button>(R.id.importAnalyticsButton).setOnClickListener {
            importLauncher.launch(arrayOf("application/json", "*/*"))
        }
    }

    private fun dayValue(date: LocalDate): Int? =
        repo.getDailyScreenTime(AnalyticsKeys.analyticsDateKey(date)).takeIf { it > 0 }

    // =========================================================================
    // YOUR DAY — 24h split into sleep, work, screen time and what's left
    // =========================================================================

    /** Average daily screen time over the last 30 days that have data (today if none). */
    private fun recentAverageScreenSeconds(): Int {
        val today = LocalDate.now()
        val values = (0L until 30L).mapNotNull { dayValue(today.minusDays(it)) }
        return if (values.isEmpty()) repo.getTodayScreenTime() else values.sum() / values.size
    }

    private fun updateDaySplit() {
        val sleep = settingsRepository.getDaySleepMinutes()
        val busy = settingsRepository.getDayBusyMinutes()
        val awakeFree = (24 * 60 - sleep - busy).coerceAtLeast(0)
        val screen = (recentAverageScreenSeconds() / 60).coerceAtMost(awakeFree)
        val free = awakeFree - screen

        val rows = listOf(
            Triple("Sleep", sleep, R.color.day_sleep),
            Triple("Work & duties", busy, R.color.day_busy),
            Triple("Free time", free, R.color.day_free),
            Triple("Screen time", screen, R.color.day_screen),
        )
        daySplitChartView.setData(
            rows.map { DaySegment(it.second, ContextCompat.getColor(this, it.third)) },
            formatMinutes(free),
            "truly free",
        )

        daySplitLegend.removeAllViews()
        rows.forEach { (label, minutes, color) ->
            val row = layoutInflater.inflate(R.layout.item_day_split_legend, daySplitLegend, false)
            row.findViewById<View>(R.id.legendDot).backgroundTintList =
                android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, color))
            row.findViewById<TextView>(R.id.legendLabel).text = label
            row.findViewById<TextView>(R.id.legendValue).text = formatMinutes(minutes)
            daySplitLegend.addView(row)
        }

        daySplitInsightTextView.text = if (awakeFree == 0) {
            "Sleep and work fill your whole day as set. Adjust the hours to see the rest."
        } else {
            val share = screen * 100 / awakeFree
            "After sleep and work you have ${formatMinutes(awakeFree)} a day. " +
                "Screen time takes $share% of it."
        }
    }

    private fun showDaySplitDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_day_split, null)
        val sleepSeekBar = view.findViewById<SeekBar>(R.id.sleepSeekBar)
        val sleepValue = view.findViewById<TextView>(R.id.sleepValue)
        val busySeekBar = view.findViewById<SeekBar>(R.id.busySeekBar)
        val busyValue = view.findViewById<TextView>(R.id.busyValue)

        // Half-hour steps: sleep 4h to 12h, work 0h to 14h.
        val sleepMin = 4 * 60
        sleepSeekBar.max = (12 * 60 - sleepMin) / 30
        sleepSeekBar.progress = (settingsRepository.getDaySleepMinutes() - sleepMin) / 30
        busySeekBar.max = 14 * 60 / 30
        busySeekBar.progress = settingsRepository.getDayBusyMinutes() / 30

        fun refresh() {
            sleepValue.text = formatMinutes(sleepMin + sleepSeekBar.progress * 30)
            busyValue.text = formatMinutes(busySeekBar.progress * 30)
        }
        refresh()
        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = refresh()
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }
        sleepSeekBar.setOnSeekBarChangeListener(listener)
        busySeekBar.setOnSeekBarChangeListener(listener)

        AlertDialog.Builder(this, R.style.CustomAlertDialog)
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                settingsRepository.saveDaySplit(sleepMin + sleepSeekBar.progress * 30, busySeekBar.progress * 30)
                updateDaySplit()
            }
            .setNegativeButton("Cancel", null)
            .create()
            .apply {
                show()
                val accent = ContextCompat.getColor(this@AnalyticsActivity, R.color.accent_primary)
                getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(accent)
                getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(accent)
            }
    }

    private fun formatMinutes(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> "${m}m"
            m == 0 -> "${h}h"
            else -> "${h}h ${m}m"
        }
    }

    private fun loadAnalyticsData() {
        val analyticsData = getAnalyticsData()
        adapter.updateData(analyticsData)

        if (analyticsData.isNotEmpty()) {
            calculateStatistics(analyticsData)
        }
    }

    private fun getAnalyticsData(): List<DayData> {
        val dayDataList = ArrayList<DayData>()
        repo.getAnalyticsDateKeys().forEach { dateKey ->
            val screenTime = repo.getDailyScreenTime(dateKey)
            if (screenTime > 0) {
                dayDataList.add(DayData(parseDateKey(dateKey), screenTime))
            }
        }
        dayDataList.sortByDescending { it.date }
        return dayDataList
    }

    private fun parseDateKey(dateKey: String): Date {
        val parts = dateKey.split("_")
        if (parts.size >= 4) {
            val year = parts[1].toIntOrNull() ?: 2024
            val month = parts[2].toIntOrNull() ?: 0
            val day = parts[3].toIntOrNull() ?: 1

            val calendar = Calendar.getInstance()
            calendar.set(year, month, day)
            return calendar.time
        }
        return Date()
    }

    // =========================================================================
    // EXPORT
    // =========================================================================

    private fun exportAnalyticsToUri(uri: Uri) {
        try {
            val root = JSONObject()
            root.put("version", 1)
            root.put("app", "awareen")
            root.put("type", "analytics")
            root.put("exported_at", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()))

            val daysArray = JSONArray()
            repo.getAnalyticsDateKeys().forEach { dateKey ->
                val screenTime = repo.getDailyScreenTime(dateKey)
                if (screenTime > 0) {
                    val dayObj = JSONObject()
                    dayObj.put("date_key", dateKey)

                    val parts = dateKey.split("_")
                    if (parts.size >= 4) {
                        dayObj.put("year", parts[1].toIntOrNull() ?: 0)
                        dayObj.put("month", parts[2].toIntOrNull() ?: 0)
                        dayObj.put("day", parts[3].toIntOrNull() ?: 0)
                    }

                    dayObj.put("screen_time_seconds", screenTime)

                    val hourly = JSONObject()
                    repo.getHourlyBreakdown(dateKey).forEach { (h, v) ->
                        hourly.put(h.toString(), v)
                    }
                    if (hourly.length() > 0) {
                        dayObj.put("hourly_breakdown", hourly)
                    }

                    daysArray.put(dayObj)
                }
            }

            root.put("analytics", daysArray)

            contentResolver.openOutputStream(uri)?.use { os ->
                os.write(root.toString(2).toByteArray())
            }

            Toast.makeText(this, "Analytics exported (${daysArray.length()} days)", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            Log.e(TAG, "Export failed: ${e.message}", e)
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // =========================================================================
    // IMPORT
    // =========================================================================

    private fun importAnalyticsFromUri(uri: Uri) {
        try {
            val jsonString = contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                ?: throw Exception("Could not read file")

            val root = JSONObject(jsonString)

            if (root.optString("app") != "awareen" || root.optString("type") != "analytics") {
                Toast.makeText(this, "Not a valid Awareen analytics file", Toast.LENGTH_LONG).show()
                return
            }

            val daysArray = root.getJSONArray("analytics")
            var importedCount = 0

            for (i in 0 until daysArray.length()) {
                val dayObj = daysArray.getJSONObject(i)
                val dateKey = dayObj.getString("date_key")
                val screenTime = dayObj.getInt("screen_time_seconds")

                if (screenTime > repo.getDailyScreenTime(dateKey)) {
                    val hourly = mutableMapOf<Int, Int>()
                    if (dayObj.has("hourly_breakdown")) {
                        val hourlyObj = dayObj.getJSONObject("hourly_breakdown")
                        hourlyObj.keys().forEach { hour ->
                            hour.toIntOrNull()?.let { h ->
                                hourly[h] = hourlyObj.getInt(hour)
                            }
                        }
                    }
                    repo.importDay(dateKey, screenTime, hourly)
                    importedCount++
                }
            }

            Toast.makeText(this, "Imported $importedCount days of data", Toast.LENGTH_SHORT).show()
            loadAnalyticsData()
            updateDaySplit()

        } catch (e: Exception) {
            Log.e(TAG, "Import failed: ${e.message}", e)
            Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // =========================================================================
    // STATISTICS
    // =========================================================================

    private fun calculateStatistics(data: List<DayData>) {
        if (data.isEmpty()) return

        val totalSeconds = data.sumOf { it.screenTimeSeconds }
        val averageSeconds = totalSeconds / data.size
        averageTextView.text = "Daily Average: ${formatTime(averageSeconds)}"

        calculateLifetimeComparison(averageSeconds)

        if (data.size >= 2) {
            val recentDays = data.take(minOf(7, data.size))
            val olderDays = data.drop(minOf(7, data.size)).take(minOf(7, data.size - minOf(7, data.size)))

            if (olderDays.isNotEmpty()) {
                val recentAverage = recentDays.sumOf { it.screenTimeSeconds } / recentDays.size
                val olderAverage = olderDays.sumOf { it.screenTimeSeconds } / olderDays.size

                val change = recentAverage - olderAverage
                val changePercent = if (olderAverage > 0) (change * 100.0 / olderAverage) else 0.0

                when {
                    abs(changePercent) < 5 -> {
                        trendTextView.text = "Trend: Stable (${String.format("%.1f", changePercent)}%)"
                        trendTextView.setTextColor(ContextCompat.getColor(this, R.color.status_warning))
                    }
                    changePercent > 0 -> {
                        trendTextView.text = "Trend: Increasing (+${String.format("%.1f", changePercent)}%)"
                        trendTextView.setTextColor(ContextCompat.getColor(this, R.color.status_danger))
                    }
                    else -> {
                        trendTextView.text = "Trend: Improving (${String.format("%.1f", changePercent)}%)"
                        trendTextView.setTextColor(ContextCompat.getColor(this, R.color.status_good))
                    }
                }
            }
        }

        val last7Days = data.take(minOf(7, data.size))
        val daysUnder2Hours = last7Days.count { it.screenTimeSeconds < 2 * 3600 }
        val daysUnder4Hours = last7Days.count { it.screenTimeSeconds < 4 * 3600 }

        when {
            daysUnder2Hours >= 5 -> {
                progressTextView.text = "Great job! You're maintaining healthy screen time!"
                progressTextView.setTextColor(ContextCompat.getColor(this, R.color.status_good))
            }
            daysUnder4Hours >= 5 -> {
                progressTextView.text = "Good progress! Try to reduce screen time further."
                progressTextView.setTextColor(ContextCompat.getColor(this, R.color.status_warning))
            }
            else -> {
                progressTextView.text = "Consider reducing your daily screen time."
                progressTextView.setTextColor(ContextCompat.getColor(this, R.color.status_danger))
            }
        }
    }

    private fun calculateLifetimeComparison(avgDailySeconds: Int) {
        val hoursPerDay = avgDailySeconds / 3600.0

        val yearsOfUsage = 60
        val lifetimeDays = (hoursPerDay * 365 * yearsOfUsage / 24).toInt()
        val lifetimeYears = lifetimeDays / 365.0

        when {
            lifetimeYears >= 5 -> {
                lifetimeYearsTextView.text = String.format("%.1f years", lifetimeYears)
                lifetimeYearsTextView.setTextColor(ContextCompat.getColor(this, R.color.status_danger))
            }
            lifetimeYears >= 2 -> {
                lifetimeYearsTextView.text = String.format("%.1f years", lifetimeYears)
                lifetimeYearsTextView.setTextColor(ContextCompat.getColor(this, R.color.status_high))
            }
            else -> {
                lifetimeYearsTextView.text = String.format("%.1f years", lifetimeYears)
                lifetimeYearsTextView.setTextColor(ContextCompat.getColor(this, R.color.status_good))
            }
        }

        lifetimeDaysTextView.text = "$lifetimeDays days total"
        lifetimeDaysTextView.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
    }

    private fun formatTime(seconds: Int): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return "${hours}h ${minutes}m"
    }
}

data class DayData(
    val date: Date,
    val screenTimeSeconds: Int
)

class AnalyticsAdapter : RecyclerView.Adapter<AnalyticsAdapter.ViewHolder>() {
    private var data: List<DayData> = emptyList()

    fun updateData(newData: List<DayData>) {
        data = newData
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_analytics_day, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(data[position])
    }

    override fun getItemCount() = data.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val dateTextView: TextView = itemView.findViewById(R.id.dateTextView)
        private val timeTextView: TextView = itemView.findViewById(R.id.timeTextView)
        private val statusTextView: TextView = itemView.findViewById(R.id.statusTextView)

        fun bind(dayData: DayData) {
            val calendar = Calendar.getInstance()
            calendar.time = dayData.date

            val today = Calendar.getInstance()
            val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }

            dateTextView.text = when {
                calendar.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) &&
                        calendar.get(Calendar.YEAR) == today.get(Calendar.YEAR) -> "Today"
                calendar.get(Calendar.DAY_OF_YEAR) == yesterday.get(Calendar.DAY_OF_YEAR) &&
                        calendar.get(Calendar.YEAR) == yesterday.get(Calendar.YEAR) -> "Yesterday"
                else -> "${calendar.get(Calendar.DAY_OF_MONTH)}/${calendar.get(Calendar.MONTH) + 1}"
            }

            timeTextView.text = formatTime(dayData.screenTimeSeconds)

            val context = itemView.context
            when {
                dayData.screenTimeSeconds < 2 * 3600 -> {
                    statusTextView.text = "Great!"
                    statusTextView.setTextColor(ContextCompat.getColor(context, R.color.status_good))
                }
                dayData.screenTimeSeconds < 4 * 3600 -> {
                    statusTextView.text = "Good"
                    statusTextView.setTextColor(ContextCompat.getColor(context, R.color.status_warning))
                }
                dayData.screenTimeSeconds < 6 * 3600 -> {
                    statusTextView.text = "High"
                    statusTextView.setTextColor(ContextCompat.getColor(context, R.color.status_high))
                }
                else -> {
                    statusTextView.text = "Too High"
                    statusTextView.setTextColor(ContextCompat.getColor(context, R.color.status_danger))
                }
            }
        }

        private fun formatTime(seconds: Int): String {
            val hours = seconds / 3600
            val minutes = (seconds % 3600) / 60
            return "${hours}h ${minutes}m"
        }
    }
}