package com.example.screentimetracker // Adjust package as needed

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView

// Import your R file if it's not automatically resolved
import com.example.screentimetracker.R

class UnsavedChangesDialog(
    context: Context,
    // Callbacks for when buttons are clicked
    private val onSave: () -> Unit,
    private val onDiscard: () -> Unit,
    private val onCancel: () -> Unit
) : Dialog(context) { // Inherit directly from Dialog, no specific theme parent

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Set the custom layout for the dialog
        setContentView(R.layout.dialog_unsaved_changes)

        // Configure the dialog window to be centered
        window?.apply {
            // Make the default dialog background transparent so our custom layout's background is visible
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

            val layoutParams = WindowManager.LayoutParams().apply {
                copyFrom(attributes)
                // Set dialog width to match parent, or wrap_content if preferred
                width = WindowManager.LayoutParams.MATCH_PARENT
                height = WindowManager.LayoutParams.WRAP_CONTENT
                // Crucially, set gravity to CENTER for both horizontal and vertical centering
                gravity = Gravity.CENTER
            }
            attributes = layoutParams
        }

        // Initialize views and set click listeners for buttons
        findViewById<TextView>(R.id.dialogTitle).text = "Unsaved Changes"
        findViewById<TextView>(R.id.dialogMessage).text = "You have unsaved changes. Do you want to save them before exiting?"

        findViewById<Button>(R.id.buttonSave).setOnClickListener {
            onSave.invoke()
            dismiss() // Dismiss the dialog after action
        }
        findViewById<Button>(R.id.buttonDiscard).setOnClickListener {
            onDiscard.invoke()
            dismiss() // Dismiss the dialog after action
        }
        findViewById<Button>(R.id.buttonCancel).setOnClickListener {
            onCancel.invoke()
            dismiss() // Dismiss the dialog after action
        }
    }
}