package com.example.screentimetracker // Use your actual package name

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar // For the top bar

class InfoActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_info) // We'll create this layout next

        // Setup Toolbar
        val toolbar: Toolbar = findViewById(R.id.toolbar_info_screen)
        setSupportActionBar(toolbar) // Sets the Toolbar as the new ActionBar

        // Enable the Up button (back arrow)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "About Awareen" // Set a title for the screen
    }

    // Handle the Up button press (back arrow)
    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed() // Default behavior for back button
        return true
    }
}