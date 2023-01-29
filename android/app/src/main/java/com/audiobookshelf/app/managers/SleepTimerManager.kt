package com.audiobookshelf.app.managers

import android.content.Context
import android.os.*
import android.util.Log
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.player.PlayerNotificationService
import com.audiobookshelf.app.player.SLEEP_TIMER_WAKE_UP_EXPIRATION
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.schedule
import kotlin.math.roundToInt

class SleepTimerManager constructor(private val playerNotificationService: PlayerNotificationService) {
  private val tag = "SleepTimerManager"

  private var sleepTimerTask:TimerTask? = null
  private var sleepTimerRunning:Boolean = false
  private var sleepTimerEndTime:Long = 0L
  private var sleepTimerLength:Long = 0L
  private var sleepTimerElapsed:Long = 0L
  private var sleepTimerFinishedAt:Long = 0L

  private fun getCurrentTime():Long {
    return playerNotificationService.getCurrentTime()
  }

  private fun getDuration():Long {
    return playerNotificationService.getDuration()
  }

  private fun getIsPlaying():Boolean {
    return playerNotificationService.currentPlayer.isPlaying
  }

  private fun setVolume(volume:Float) {
    playerNotificationService.currentPlayer.volume = volume
  }

  private fun pause() {
    playerNotificationService.currentPlayer.pause()
  }

  private fun play() {
    playerNotificationService.currentPlayer.play()
  }

  private fun getSleepTimerTimeRemainingSeconds():Int {
    if (sleepTimerEndTime == 0L && sleepTimerLength > 0) { // For regular timer
      return ((sleepTimerLength - sleepTimerElapsed) / 1000).toDouble().roundToInt()
    }
    // For chapter end timer
    if (sleepTimerEndTime <= 0) return 0
    return (((sleepTimerEndTime - getCurrentTime()) / 1000).toDouble()).roundToInt()
  }

  fun setSleepTimer(time: Long, isChapterTime: Boolean) : Boolean {
    Log.d(tag, "Setting Sleep Timer for $time is chapter time $isChapterTime")
    sleepTimerTask?.cancel()
    sleepTimerRunning = true
    sleepTimerFinishedAt = 0L
    sleepTimerElapsed = 0L

    // Register shake sensor
    playerNotificationService.registerSensor()

    val currentTime = getCurrentTime()
    if (isChapterTime) {
      if (currentTime > time) {
        Log.d(tag, "Invalid sleep timer - current time is already passed chapter time $time")
        return false
      }
      sleepTimerEndTime = time
      sleepTimerLength = 0

      if (sleepTimerEndTime > getDuration()) {
        sleepTimerEndTime = getDuration()
      }
    } else {
      sleepTimerLength = time
      sleepTimerEndTime = 0L

      if (sleepTimerLength + getCurrentTime() > getDuration()) {
        sleepTimerLength = getDuration() - getCurrentTime()
      }
    }

    playerNotificationService.clientEventEmitter?.onSleepTimerSet(getSleepTimerTimeRemainingSeconds())

    sleepTimerTask = Timer("SleepTimer", false).schedule(0L, 1000L) {
      Handler(Looper.getMainLooper()).post {
        if (getIsPlaying()) {
          sleepTimerElapsed += 1000L

          val sleepTimeSecondsRemaining = getSleepTimerTimeRemainingSeconds()
          Log.d(tag, "Timer Elapsed $sleepTimerElapsed | Sleep TIMER time remaining $sleepTimeSecondsRemaining s")

          if (sleepTimeSecondsRemaining > 0) {
            playerNotificationService.clientEventEmitter?.onSleepTimerSet(sleepTimeSecondsRemaining)
          }

          if (sleepTimeSecondsRemaining <= 0) {
            Log.d(tag, "Sleep Timer Pausing Player on Chapter")
            pause()

            playerNotificationService.clientEventEmitter?.onSleepTimerEnded(getCurrentTime())
            clearSleepTimer()
            sleepTimerFinishedAt = System.currentTimeMillis()
          } else if (sleepTimeSecondsRemaining <= 60) {
            // Start fading out audio
            val volume = sleepTimeSecondsRemaining / 60F
            Log.d(tag, "SLEEP VOLUME FADE $volume | ${sleepTimeSecondsRemaining}s remaining")
            setVolume(volume)
          }
        }
      }
    }
    return true
  }

  private fun clearSleepTimer() {
    sleepTimerTask?.cancel()
    sleepTimerTask = null
    sleepTimerEndTime = 0
    sleepTimerRunning = false
    playerNotificationService.unregisterSensor()
  }

  fun getSleepTimerTime():Long {
    return sleepTimerEndTime
  }

  fun cancelSleepTimer() {
    Log.d(tag, "Canceling Sleep Timer")
    clearSleepTimer()
    playerNotificationService.clientEventEmitter?.onSleepTimerSet(0)
  }

  // Vibrate when extending sleep timer by shaking
  private fun vibrate() {
    val context = playerNotificationService.getContext()
    val vibrator:Vibrator
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      val vibratorManager =
        context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
      vibrator = vibratorManager.defaultVibrator
    } else {
      @Suppress("DEPRECATION")
      vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    vibrator.let {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val vibrationEffect = VibrationEffect.createWaveform(longArrayOf(0, 150, 150, 150),-1)
        it.vibrate(vibrationEffect)
      } else {
        @Suppress("DEPRECATION")
        it.vibrate(10)
      }
    }
  }

  fun checkShouldResetSleepTimer() {
    if (!sleepTimerRunning) {
      if (sleepTimerFinishedAt <= 0L) return

      val finishedAtDistance = System.currentTimeMillis() - sleepTimerFinishedAt
      if (finishedAtDistance > SLEEP_TIMER_WAKE_UP_EXPIRATION) // 2 minutes
      {
        Log.d(tag, "Sleep timer finished over 2 mins ago, clearing it")
        sleepTimerFinishedAt = 0L
        return
      }

      // Set sleep timer
      //   When sleepTimerLength is 0 then use end of chapter/track time
      if (sleepTimerLength == 0L) {
        val currentChapterEndTimeMs = playerNotificationService.getEndTimeOfChapterOrTrack()
        if (currentChapterEndTimeMs == null) {
          Log.e(tag, "Checking reset sleep timer to end of chapter/track but there is no current session")
        } else {
          vibrate()
          setSleepTimer(currentChapterEndTimeMs, true)
          play()
        }
      } else {
        vibrate()
        setSleepTimer(sleepTimerLength, false)
        play()
      }
      return
    }

    // Does not apply to chapter sleep timers and timer must be running for at least 3 seconds
    if (sleepTimerLength > 0L && sleepTimerElapsed > 3000L) {
      vibrate()
      setSleepTimer(sleepTimerLength, false)
    }
  }

  fun handleShake() {
    if (sleepTimerRunning || sleepTimerFinishedAt > 0L) {
      if (DeviceManager.deviceData.deviceSettings?.disableShakeToResetSleepTimer == true) {
        Log.d(tag, "Shake to reset sleep timer is disabled")
        return
      }
      checkShouldResetSleepTimer()
    }
  }

  fun increaseSleepTime(time: Long) {
    Log.d(tag, "Increase Sleep time $time")
    if (!sleepTimerRunning) return

    if (sleepTimerEndTime == 0L) {
      sleepTimerLength += time
      if (sleepTimerLength + getCurrentTime() > getDuration()) sleepTimerLength = getDuration() - getCurrentTime()
    } else {
      val newSleepEndTime = sleepTimerEndTime + time
      sleepTimerEndTime = if (newSleepEndTime >= getDuration()) {
        getDuration()
      } else {
        newSleepEndTime
      }
    }

    setVolume(1F)
    playerNotificationService.clientEventEmitter?.onSleepTimerSet(getSleepTimerTimeRemainingSeconds())
  }

  fun decreaseSleepTime(time: Long) {
    Log.d(tag, "Decrease Sleep time $time")
    if (!sleepTimerRunning) return


    if (sleepTimerEndTime == 0L) {
      sleepTimerLength -= time
      if (sleepTimerLength <= 0) sleepTimerLength = 1000L
    } else {
      val newSleepEndTime = sleepTimerEndTime - time
      sleepTimerEndTime = if (newSleepEndTime <= 1000) {
        // End sleep timer in 1 second
        getCurrentTime() + 1000
      } else {
        newSleepEndTime
      }
    }

    setVolume(1F)
    playerNotificationService.clientEventEmitter?.onSleepTimerSet(getSleepTimerTimeRemainingSeconds())
  }

  fun checkAutoSleepTimer() {
    if (sleepTimerRunning) { // Sleep timer already running
      return
    }
    DeviceManager.deviceData.deviceSettings?.let { deviceSettings ->
      if (!deviceSettings.autoSleepTimer) return // Check auto sleep timer is enabled

      val startCalendar = Calendar.getInstance()
      startCalendar.set(Calendar.HOUR_OF_DAY, deviceSettings.autoSleepTimerStartHour)
      startCalendar.set(Calendar.MINUTE, deviceSettings.autoSleepTimerStartMinute)
      val endCalendar = Calendar.getInstance()
      endCalendar.set(Calendar.HOUR_OF_DAY, deviceSettings.autoSleepTimerEndHour)
      endCalendar.set(Calendar.MINUTE, deviceSettings.autoSleepTimerEndMinute)

      // In cases where end time is earlier then start time then we add a day to end time
      //   e.g. start time 22:00 and end time 06:00. End time will be treated as 6am the next day.
      //   e.g. start time 08:00 and end time 22:00. Start and end time will be the same day.
      if (endCalendar.before(startCalendar)) {
        endCalendar.add(Calendar.DAY_OF_MONTH, 1)
      }

      val currentCalendar = Calendar.getInstance()
      val currentHour = SimpleDateFormat("HH:mm", Locale.getDefault()).format(currentCalendar.time)
      if (currentCalendar.after(startCalendar) && currentCalendar.before(endCalendar)) {
        Log.i(tag, "Current hour $currentHour is between ${deviceSettings.autoSleepTimerStartTime} and ${deviceSettings.autoSleepTimerEndTime} - starting sleep timer")

        // Set sleep timer
        //   When sleepTimerLength is 0 then use end of chapter/track time
        if (deviceSettings.sleepTimerLength == 0L) {
          val currentChapterEndTimeMs = playerNotificationService.getEndTimeOfChapterOrTrack()
          if (currentChapterEndTimeMs == null) {
            Log.e(tag, "Setting auto sleep timer to end of chapter/track but there is no current session")
          } else {
            setSleepTimer(currentChapterEndTimeMs, true)
          }
        } else {
          setSleepTimer(deviceSettings.sleepTimerLength, false)
        }
      } else {
        Log.d(tag, "Current hour $currentHour is NOT between ${deviceSettings.autoSleepTimerStartTime} and ${deviceSettings.autoSleepTimerEndTime}")
      }
    }
  }

  fun handleMediaPlayEvent() {
    checkShouldResetSleepTimer()
    checkAutoSleepTimer()
  }
}