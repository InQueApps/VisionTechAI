package ab.inque.visiontechai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

object ContactEmailLauncher {

    fun open(context: Context) {
        try {
            context.startActivity(
                Intent.createChooser(createIntent(context), context.getString(R.string.contact_us_chooser))
            )
        } catch (_: Exception) {
            Toast.makeText(context, "No email app available", Toast.LENGTH_SHORT).show()
        }
    }

    internal fun createIntent(context: Context): Intent {
        val recipient = context.getString(R.string.contact_us_email)
        val subject = context.getString(R.string.contact_us_subject)
        return Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:$recipient")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(recipient))
            putExtra(Intent.EXTRA_SUBJECT, subject)
        }
    }
}
