package com.example.screentimetracker

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import java.util.*
import kotlin.collections.ArrayList
import kotlin.math.abs

class AnalyticsActivity : AppCompatActivity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var recyclerView: RecyclerView
    private lateinit var averageTextView: TextView
    private lateinit var trendTextView: TextView
    private lateinit var progressTextView: TextView
    private lateinit var adapter: AnalyticsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_analytics)

        prefs = getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE)

        initializeViews()
        loadAnalyticsData()
    }

    private fun initializeViews() {
        val backButton = findViewById<ImageButton>(R.id.backButton)
        averageTextView = findViewById(R.id.averageTextView)
        trendTextView = findViewById(R.id.trendTextView)
        progressTextView = findViewById(R.id.progressTextView)
        recyclerView = findViewById(R.id.analyticsRecyclerView)

        backButton.setOnClickListener { finish() }

        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = AnalyticsAdapter()
        recyclerView.adapter = adapter
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

        // Sort by date (newest first)
        dayDataList.sortByDescending { it.date }
        return dayDataList
    }

    private fun parseDateKey(dateKey: String): Date {
        // Format: "analytics_YYYY_MM_DD"
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

    private fun calculateStatistics(data: List<DayData>) {
        if (data.isEmpty()) return

        // Calculate average
        val totalSeconds = data.sumOf { it.screenTimeSeconds }
        val averageSeconds = totalSeconds / data.size
        averageTextView.text = "Daily Average: ${formatTime(averageSeconds)}"

        // Calculate trend (last 7 days vs previous 7 days)
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

        // Progress assessment
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
        val dayData = data[position]
        holder.bind(dayData)
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

            // Status based on screen time
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