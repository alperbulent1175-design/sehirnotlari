package com.sehirnotlari

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class RemindersTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before fun reset() {
        context.getSharedPreferences("reminders", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun multipleRemindersSurviveRestoreAndCancelIndependently() {
        val time = System.currentTimeMillis() + 3_600_000
        val first = Reminders.add(context, "Ankara", "Birinci", time).getJSONObject("item").getInt("id")
        val second = Reminders.add(context, "İzmir", "İkinci", time).getJSONObject("item").getInt("id")
        assertTrue(first != second)
        Reminders.restore(context)
        assertEquals(2, JSONArray(Reminders.list(context)).length())

        assertTrue(Reminders.remove(context, first))
        assertFalse(Reminders.remove(context, first))
        assertNull(Reminders.take(context, first))
        assertNotNull(Reminders.take(context, second))
        assertEquals(0, JSONArray(Reminders.list(context)).length())
    }

    @Test fun pastReminderIsRejected() {
        val result = Reminders.add(context, "Van", "Geç", System.currentTimeMillis() - 1)
        assertTrue(result.has("error"))
        assertEquals(0, JSONArray(Reminders.list(context)).length())
    }

    @Test fun overdueReminderIsRetainedUntilDelivery() {
        val item = """[{"id":7,"city":"Van","message":"Gecikmiş","time":${System.currentTimeMillis() - 60_000}}]"""
        context.getSharedPreferences("reminders", Context.MODE_PRIVATE).edit().putString("items", item).commit()

        Reminders.restore(context)
        assertEquals(1, JSONArray(Reminders.list(context)).length())
        assertEquals("Gecikmiş", Reminders.take(context, 7)?.getString("message"))
        assertEquals(0, JSONArray(Reminders.list(context)).length())
    }
}
