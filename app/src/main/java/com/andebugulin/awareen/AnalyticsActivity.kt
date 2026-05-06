package com.andebugulin.awareen

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
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
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import kotlin.collections.ArrayList
import kotlin.math.abs

class AnalyticsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AnalyticsActivity"
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var recyclerView: RecyclerView
    private lateinit var averageTextView: TextView
    private lateinit var trendTextView: TextView
    private lateinit var progressTextView: TextView
    private lateinit var lifetimeYearsTextView: TextView
    private lateinit var lifetimeDaysTextView: TextView
    private lateinit var adapter: AnalyticsAdapter

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
        window.navigationBarColor = android.graphics.Color.parseColor("#121212")

        prefs = getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE)

        initializeViews()
        loadAnalyticsData()
    }

    private fun initializeViews() {
        val backButton = findViewById<ImageButton>(R.id.backButton)
        averageTextView = findViewById(R.id.averageTextView)
        trendTextView = findViewById(R.id.trendTextView)
        progressTextView = findViewById(R.id.progressTextView)
        lifetimeYearsTextView = findViewById(R.id.lifetimeYearsTextView)
        lifetimeDaysTextView = findViewById(R.id.lifetimeDaysTextView)
        recyclerView = findViewById(R.id.analyticsRecyclerView)

        backButton.setOnClickListener { finish() }

        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = AnalyticsAdapter()
        recyclerView.adapter = adapter

        findViewById<Button>(R.id.exportAnalyticsButton).setOnClickListener {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
            exportLauncher.launch("awareen_analytics_$timestamp.json")
        }

        findViewById<Button>(R.id.importAnalyticsButton).setOnClickListener {
            importLauncher.launch(arrayOf("application/json", "*/*"))
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
        val analyticsDates = prefs.getStringSet("analytics_dates", mutableSetOf()) ?: mutableSetOf()
        val dayDataList = ArrayList<DayData>()

        analyticsDates.forEach { dateKey ->
            val screenTime = prefs.getInt(dateKey, 0)
            if (screenTime > 0) {
                val date = parseDateKey(dateKey)
                dayDataList.add(DayData(date, screenTime))
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

            val analyticsDates = prefs.getStringSet("analytics_dates", mutableSetOf()) ?: mutableSetOf()
            val daysArray = JSONArray()

            analyticsDates.forEach { dateKey ->
                val screenTime = prefs.getInt(dateKey, 0)
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
                    for (h in 0..23) {
                        val hourKey = "${dateKey}_hour_$h"
                        val hourVal = prefs.getInt(hourKey, 0)
                        if (hourVal > 0) {
                            hourly.put(h.toString(), hourVal)
                        }
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
            val existingDates = prefs.getStringSet("analytics_dates", mutableSetOf())?.toMutableSet() ?: mutableSetOf()

            val editor = prefs.edit()
            var importedCount = 0

            for (i in 0 until daysArray.length()) {
                val dayObj = daysArray.getJSONObject(i)
                val dateKey = dayObj.getString("date_key")
                val screenTime = dayObj.getInt("screen_time_seconds")

                val existing = prefs.getInt(dateKey, 0)
                if (screenTime > existing) {
                    editor.putInt(dateKey, screenTime)
                    existingDates.add(dateKey)

                    if (dayObj.has("hourly_breakdown")) {
                        val hourly = dayObj.getJSONObject("hourly_breakdown")
                        hourly.keys().forEach { hour ->
                            editor.putInt("${dateKey}_hour_$hour", hourly.getInt(hour))
                        }
                    }

                    importedCount++
                }
            }

            editor.putStringSet("analytics_dates", existingDates)
            editor.apply()

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
                        trendTextView.setTextColor(Color.YELLOW)
                    }
                    changePercent > 0 -> {
                        trendTextView.text = "Trend: Increasing (+${String.format("%.1f", changePercent)}%)"
                        trendTextView.setTextColor(Color.RED)
                    }
                    else -> {
                        trendTextView.text = "Trend: Improving (${String.format("%.1f", changePercent)}%)"
                        trendTextView.setTextColor(Color.GREEN)
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
                progressTextView.setTextColor(Color.GREEN)
            }
            daysUnder4Hours >= 5 -> {
                progressTextView.text = "Good progress! Try to reduce screen time further."
                progressTextView.setTextColor(Color.YELLOW)
            }
            else -> {
                progressTextView.text = "Consider reducing your daily screen time."
                progressTextView.setTextColor(Color.RED)
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
                lifetimeYearsTextView.setTextColor(Color.RED)
            }
            lifetimeYears >= 2 -> {
                lifetimeYearsTextView.text = String.format("%.1f years", lifetimeYears)
                lifetimeYearsTextView.setTextColor(Color.parseColor("#FFA500"))
            }
            else -> {
                lifetimeYearsTextView.text = String.format("%.1f years", lifetimeYears)
                lifetimeYearsTextView.setTextColor(Color.GREEN)
            }
        }

        lifetimeDaysTextView.text = "$lifetimeDays days total"
        lifetimeDaysTextView.setTextColor(Color.parseColor("#757575"))
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

            when {
                dayData.screenTimeSeconds < 2 * 3600 -> {
                    statusTextView.text = "Great!"
                    statusTextView.setTextColor(Color.GREEN)
                }
                dayData.screenTimeSeconds < 4 * 3600 -> {
                    statusTextView.text = "Good"
                    statusTextView.setTextColor(Color.YELLOW)
                }
                dayData.screenTimeSeconds < 6 * 3600 -> {
                    statusTextView.text = "High"
                    statusTextView.setTextColor(Color.parseColor("#FFA500"))
                }
                else -> {
                    statusTextView.text = "Too High"
                    statusTextView.setTextColor(Color.RED)
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