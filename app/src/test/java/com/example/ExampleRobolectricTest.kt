package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.repository.MikroTikRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  private fun newRepository(context: Context): MikroTikRepository {
    val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    return MikroTikRepository(context, db.activityLogDao(), db.cacheDao())
  }

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("NetCraft", appName)
  }

  @Test
  fun `test card renewal in repository`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val repo = newRepository(context)
    repo.enableOfflineProgramMode {}
    val deadline = System.currentTimeMillis() + 10_000
    while (repo.userManagerUsers.value.isEmpty() && System.currentTimeMillis() < deadline) {
      kotlinx.coroutines.delay(100)
    }

    // Wait for initial demo users to be loaded
    val initialUsers = repo.userManagerUsers.value
    assertTrue("Should have initial demo users", initialUsers.isNotEmpty())

    val targetUser = initialUsers.first()
    var successCalled = false

    repo.renewUserManagerUser(
      username = targetUser.username,
      newProfile = "3000RY",
      resetUptime = true,
      onSuccess = { successCalled = true },
      onError = {}
    )

    var updatedUser = repo.userManagerUsers.value.find { it.username == targetUser.username }
    var waitDeadline = System.currentTimeMillis() + 10_000
    while (updatedUser?.profile != "3000RY" && System.currentTimeMillis() < waitDeadline) {
      kotlinx.coroutines.delay(100)
      updatedUser = repo.userManagerUsers.value.find { it.username == targetUser.username }
    }

    // Verify user profile updated to 3000RY and active is true
    assertNotNull(updatedUser)
    assertEquals("3000RY", updatedUser?.profile)
    assertTrue(updatedUser?.active == true)
    assertEquals("0s", updatedUser?.uptimeUsed)
  }

  @Test
  fun `test card bulk toggle active in repository`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val repo = newRepository(context)
    repo.enableOfflineProgramMode {}
    val deadline = System.currentTimeMillis() + 10_000
    while (repo.userManagerUsers.value.isEmpty() && System.currentTimeMillis() < deadline) {
      kotlinx.coroutines.delay(100)
    }

    val users = repo.userManagerUsers.value
    val firstTwo = users.take(2).map { it.username }

    repo.bulkToggleUserManagerUsers(firstTwo, active = false) { count ->
      assertEquals(2, count)
    }

    var updatedUsers = repo.userManagerUsers.value
    var waitDeadline = System.currentTimeMillis() + 10_000
    while (updatedUsers.filter { it.username in firstTwo }.any { it.active } &&
      System.currentTimeMillis() < waitDeadline
    ) {
      kotlinx.coroutines.delay(100)
      updatedUsers = repo.userManagerUsers.value
    }
    val deactivated = updatedUsers.filter { it.username in firstTwo }
    deactivated.forEach { assertFalse(it.active) }
  }
}

