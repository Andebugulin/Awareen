package com.andebugulin.awareen.ui

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ImageButton
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
import com.andebugulin.awareen.domain.AnalyticsKeys
import com.google.android.material.button.MaterialButtonToggleGroup
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.*
import kotlin.collections.ArrayList
import kotlin.math.abs

class AnalyticsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AnalyticsActivity"
    }

    private enum class ChartRange { WEEK, MONTH, YEAR }

    private lateinit var repo: ScreenTimeRepository
    private lateinit var recyclerView: RecyclerView
    private lateinit var averageTextView: TextView
    private lateinit var trendTextView: TextView
    private lateinit var progressTextView: TextView
    private lateinit var lifetimeYearsTextView: TextView
    private lateinit var lifetimeDaysTextView: TextView
    private lateinit var adapter: AnalyticsAdapter

    private lateinit var chartView: ScreenTimeChartView
    private lateinit var chartRangeToggleGroup: MaterialButtonToggleGroup
    private lateinit var chartCaptionTextView: TextView
    private lateinit var toggleDailyLogButton: Button
    private lateinit var dailyLogContainer: View
    private var currentChartRange = ChartRange.MONTH

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

        initializeViews()
        loadAnalyticsData()
        updateChart()
    }

    private fun initializeViews() {
        val backButton = findViewById<ImageButton>(R.id.backButton)
        averageTextView = findViewById(R.id.averageTextView)
        trendTextView = findViewById(R.id.trendTextView)
        progressTextView = findViewById(R.id.progressTextView)
        lifetimeYearsTextView = findViewById(R.id.lifetimeYearsTextView)
        lifetimeDaysTextView = findViewById(R.id.lifetimeDaysTextView)
        recyclerView = findViewById(R.id.analyticsRecyclerView)
        chartView = findViewById(R.id.screenTimeChartView)
        chartRangeToggleGroup = findViewById(R.id.chartRangeToggleGroup)
        chartCaptionTextView = findViewById(R.id.chartCaptionTextView)
        toggleDailyLogButton = findViewById(R.id.toggleDailyLogButton)
        dailyLogContainer = findViewById(R.id.dailyLogContainer)

        backButton.setOnClickListener { finish() }

        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = AnalyticsAdapter()
        recyclerView.adapter = adapter

        chartView.setColors(
            ContextCompat.getColor(this, R.color.accent_primary),
            ContextCompat.getColor(this, R.color.control_track),
            ContextCompat.getColor(this, R.color.text_secondary),
        )

        chartRangeToggleGroup.check(R.id.chartRangeMonth)
        chartRangeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val newRange = when (checkedId) {
                R.id.chartRangeMonth -> ChartRange.MONTH
                R.id.chartRangeYear -> ChartRange.YEAR
                else -> ChartRange.WEEK
            }
            if (newRange == currentChartRange) return@addOnButtonCheckedListener
            currentChartRange = newRange
            updateChart()
        }

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

    // =========================================================================
    // TREND CHART
    // =========================================================================

    /**
     * Week bars are each day's own total; month/year bars are the average
     * daily total across a rolling window, so all three ranges plot the same
     * unit (seconds per day) and stay comparable at a glance.
     */
    private fun updateChart() {
        val today = LocalDate.now()
        val bars = when (currentChartRange) {
            ChartRange.WEEK -> {
                chartCaptionTextView.text = "Daily screen time, last 7 days"
                (6 downTo 0).map { offset ->
                    val date = today.minusDays(offset.toLong())
                    val seconds = repo.getDailyScreenTime(AnalyticsKeys.analyticsDateKey(date))
                    ChartBar(date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()), seconds)
                }
            }
            ChartRange.MONTH -> {
                chartCaptionTextView.text = "Average daily screen time, last 4 weeks"
                (3 downTo 0).map { weekOffset ->
                    val weekEnd = today.minusWeeks(weekOffset.toLong())
                    val weekStart = weekEnd.minusDays(6)
                    var total = 0
                    var day = weekStart
                    while (!day.isAfter(weekEnd)) {
                        total += repo.getDailyScreenTime(AnalyticsKeys.analyticsDateKey(day))
                        day = day.plusDays(1)
                    }
                    ChartBar(weekEnd.format(DateTimeFormatter.ofPattern("MMM d")), total / 7)
                }
            }
            ChartRange.YEAR -> {
                chartCaptionTextView.text = "Average daily screen time, last 12 months"
                (11 downTo 0).map { monthOffset ->
                    val monthDate = today.minusMonths(monthOffset.toLong())
                    val yearMonth = YearMonth.from(monthDate)
                    val lastDayCounted = if (yearMonth == YearMonth.from(today)) today.dayOfMonth else yearMonth.lengthOfMonth()
                    var total = 0
                    for (day in 1..lastDayCounted) {
                        total += repo.getDailyScreenTime(AnalyticsKeys.analyticsDateKey(yearMonth.atDay(day)))
                    }
                    val label = monthDate.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                    ChartBar(label, total / lastDayCounted)
                }
            }
        }
        chartView.setBars(bars)
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