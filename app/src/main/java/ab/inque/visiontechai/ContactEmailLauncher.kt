package ab.inque.visiontechai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

object ContactEmailLauncher {

    fun open(context: Context) {
        val recipient = context.getString(R.string.contact_us_email)
        val subject = context.getString(R.string.contact_us_subject)
        val uri = Uri.parse("mailto:$recipient").buildUpon()
            .appendQueryParameter("subject", subject)
            .build()
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = uri
            putExtra(Intent.EXTRA_EMAIL, arrayOf(recipient))
            putExtra(Intent.EXTRA_SUBJECT, subject)
        }
        try {
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.contact_us_chooser)))
        } catch (_: Exception) {
            Toast.makeText(context, "No email app available", Toast.LENGTH_SHORT).show()
        }
    }
}
