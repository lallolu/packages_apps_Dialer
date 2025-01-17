/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.incallui;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManager.DisplayListener;
import android.os.PowerManager;
import android.os.Trace;
import android.support.annotation.NonNull;
import android.telecom.CallAudioState;
import android.view.Display;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.provider.Settings;
import com.android.incallui.call.TelecomAdapter;
import com.android.dialer.common.LogUtil;
import com.android.incallui.InCallPresenter.InCallState;
import com.android.incallui.InCallPresenter.InCallStateListener;
import com.android.incallui.audiomode.AudioModeProvider;
import com.android.incallui.audiomode.AudioModeProvider.AudioModeListener;
import com.android.incallui.call.CallList;
import com.android.incallui.call.DialerCall;
import android.preference.PreferenceManager;
import android.telecom.TelecomManager;

/**
 * Class manages the proximity sensor for the in-call UI. We enable the proximity sensor while the
 * user in a phone call. The Proximity sensor turns off the touchscreen and display when the user is
 * close to the screen to prevent user's cheek from causing touch events. The class requires special
 * knowledge of the activity and device state to know when the proximity sensor should be enabled
 * and disabled. Most of that state is fed into this class through public methods.
 */
public class ProximitySensor
    implements AccelerometerListener.ChangeListener, InCallStateListener, AudioModeListener, SensorEventListener {

  private static final String TAG = ProximitySensor.class.getSimpleName();
  private static final String PREF_KEY_DISABLE_PROXI_SENSOR = "disable_proximity_sensor_key";

  private final PowerManager powerManager;
  private final PowerManager.WakeLock proximityWakeLock;
  private SensorManager sensor;
  private Sensor proxSensor;
  private final AudioModeProvider audioModeProvider;
  private final AccelerometerListener accelerometerListener;
  private final ProximityDisplayListener displayListener;
  private int orientation = AccelerometerListener.ORIENTATION_UNKNOWN;
  private boolean uiShowing = false;
  private boolean hasIncomingCall = false;
  private boolean hasOngoingCall = false;
  private boolean isPhoneOutgoing = false;
  private boolean isPhoneRinging = false;
  private boolean proximitySpeaker = false;
  private boolean isProxSensorNear = false;
  private boolean isProxSensorFar = true;
  private int proxSpeakerDelay = 3000;
  private boolean isPhoneOffhook = false;
  private boolean dialpadVisible;
  private boolean isAttemptingVideoCall;
  private boolean isVideoCall;
  private boolean isRttCall;
  private SharedPreferences mPrefs;
  private Context mContext;
  private final TelecomManager telecomManager;

  private static final int SENSOR_SENSITIVITY = 4;

   private final Handler handler = new Handler();
   private final Runnable activateSpeaker = new Runnable() {
    @Override
    public void run() {
      TelecomAdapter.getInstance().setAudioRoute(CallAudioState.ROUTE_SPEAKER);
    }
   };

  public ProximitySensor(
      @NonNull Context context,
      @NonNull AudioModeProvider audioModeProvider,
      @NonNull AccelerometerListener accelerometerListener) {
    mContext = context;
    Trace.beginSection("ProximitySensor.Constructor");

    mPrefs = PreferenceManager.getDefaultSharedPreferences(context);
    final boolean mIsProximitySensorDisabled = mPrefs.getBoolean(PREF_KEY_DISABLE_PROXI_SENSOR, false);

    powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
    telecomManager = (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
    if (powerManager.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)
          && !mIsProximitySensorDisabled) {
      proximityWakeLock =
          powerManager.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, TAG);
    } else if (mIsProximitySensorDisabled) {
      turnOffProximitySensor(true); // Ensure the wakelock is released before destroying it.
      proximityWakeLock = null;
    } else {
      proximityWakeLock = null;
    }
    if (powerManager.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
      sensor = (SensorManager) mContext.getSystemService(Context.SENSOR_SERVICE);
      proxSensor = sensor.getDefaultSensor(Sensor.TYPE_PROXIMITY);
    } else {
      proxSensor = null;
      sensor = null;
    }
    this.accelerometerListener = accelerometerListener;
    this.accelerometerListener.setListener(this);

    displayListener =
        new ProximityDisplayListener(
            (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE));
    displayListener.register();

    this.audioModeProvider = audioModeProvider;
    this.audioModeProvider.addListener(this);
    Trace.endSection();
  }

  public void tearDown() {
    audioModeProvider.removeListener(this);

    accelerometerListener.enable(false);
    displayListener.unregister();

    turnOffProximitySensor(true);
    if (sensor != null) {
      sensor.unregisterListener(this);
    }
     // remove any pending audio changes scheduled
    handler.removeCallbacks(activateSpeaker);
  }

  /** Called to identify when the device is laid down flat. */
  @Override
  public void onOrientationChanged(int orientation) {
    this.orientation = orientation;
    updateProximitySensorMode();
  }

  @Override
  public void onDeviceFlipped(boolean faceDown) {
      // ignored
  }

  /** Called to keep track of the overall UI state. */
  @Override
  public void onStateChange(InCallState oldState, InCallState newState, CallList callList) {
    // We ignore incoming state because we do not want to enable proximity
    // sensor during incoming call screen. We check hasLiveCall() because a disconnected call
    // can also put the in-call screen in the INCALL state.
    hasOngoingCall = InCallState.INCALL == newState && callList.hasLiveCall();
    boolean isOffhook =
        InCallState.PENDING_OUTGOING == newState
            || InCallState.OUTGOING == newState
            || hasOngoingCall;

    DialerCall activeCall = callList.getActiveCall();
    boolean isVideoCall = activeCall != null && activeCall.isVideoCall();
    boolean isRttCall = activeCall != null && activeCall.isActiveRttCall();
    hasIncomingCall = (InCallState.INCOMING == newState);
    isPhoneOutgoing = (InCallState.OUTGOING == newState);

    if (isOffhook != isPhoneOffhook
        || this.isVideoCall != isVideoCall
        || this.isRttCall != isRttCall) {
      isPhoneOffhook = isOffhook;
      this.isVideoCall = isVideoCall;
      this.isRttCall = isRttCall;

      orientation = AccelerometerListener.ORIENTATION_UNKNOWN;
      accelerometerListener.enable(isPhoneOffhook);

      updateProxSpeaker();
      updateProximitySensorMode();
    }
    if (hasOngoingCall && InCallState.OUTGOING == oldState) {
      setProxSpeaker(isProxSensorFar);
      updateProximitySensorMode();
    }
     if (hasIncomingCall) {
      updateProxRing();
      answerProx(isProxSensorNear);
      updateProximitySensorMode();
    }
  }

  @Override
  public void onAudioStateChanged(CallAudioState audioState) {
    updateProximitySensorMode();
  }

   /**
   * Proximity state changed
   */
  @Override
  public void onSensorChanged(SensorEvent event) {
    if (event.values[0] != proxSensor.getMaximumRange()) {
      isProxSensorFar = false;
    } else {
      isProxSensorFar = true;
      isProxSensorNear = false;
    }
    if (event.values[0] <= SENSOR_SENSITIVITY ) {
            isProxSensorNear = true;
        }
     Log.i(this, "Proximity sensor changed");
     setProxSpeaker(isProxSensorFar);
     answerProx(isProxSensorNear);
  }
   @Override
  public void onAccuracyChanged(Sensor sensor, int accuracy) {
  }

  public void onDialpadVisible(boolean visible) {
    dialpadVisible = visible;
    updateProximitySensorMode();
  }

  public void setIsAttemptingVideoCall(boolean isAttemptingVideoCall) {
    LogUtil.i(
        "ProximitySensor.setIsAttemptingVideoCall",
        "isAttemptingVideoCall: %b",
        isAttemptingVideoCall);
    this.isAttemptingVideoCall = isAttemptingVideoCall;
    updateProximitySensorMode();
  }
  /** Used to save when the UI goes in and out of the foreground. */
  public void onInCallShowing(boolean showing) {
    if (showing) {
      uiShowing = true;

      // We only consider the UI not showing for instances where another app took the foreground.
      // If we stopped showing because the screen is off, we still consider that showing.
    } else if (powerManager.isScreenOn()) {
      uiShowing = false;
    }
    updateProximitySensorMode();
  }

  void onDisplayStateChanged(boolean isDisplayOn) {
    LogUtil.i("ProximitySensor.onDisplayStateChanged", "isDisplayOn: %b", isDisplayOn);
    accelerometerListener.enable(isDisplayOn);
  }

  /**
   * TODO: There is no way to determine if a screen is off due to proximity or if it is legitimately
   * off, but if ever we can do that in the future, it would be useful here. Until then, this
   * function will simply return true of the screen is off. TODO: Investigate whether this can be
   * replaced with the ProximityDisplayListener.
   */
  public boolean isScreenReallyOff() {
    return !powerManager.isScreenOn();
  }

  private void turnOnProximitySensor() {
    if (proximityWakeLock != null) {
      if (!proximityWakeLock.isHeld()) {
        LogUtil.i("ProximitySensor.turnOnProximitySensor", "acquiring wake lock");
        proximityWakeLock.acquire();
      } else {
        LogUtil.i("ProximitySensor.turnOnProximitySensor", "wake lock already acquired");
      }
    }
  }

  private void turnOffProximitySensor(boolean screenOnImmediately) {
    if (proximityWakeLock != null) {
      if (proximityWakeLock.isHeld()) {
        LogUtil.i("ProximitySensor.turnOffProximitySensor", "releasing wake lock");
        int flags = (screenOnImmediately ? 0 : PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY);
        proximityWakeLock.release(flags);
      } else {
        LogUtil.i("ProximitySensor.turnOffProximitySensor", "wake lock already released");
      }
    }
  }

  /**
   * Updates the wake lock used to control proximity sensor behavior, based on the current state of
   * the phone.
   *
   * <p>On devices that have a proximity sensor, to avoid false touches during a call, we hold a
   * PROXIMITY_SCREEN_OFF_WAKE_LOCK wake lock whenever the phone is off hook. (When held, that wake
   * lock causes the screen to turn off automatically when the sensor detects an object close to the
   * screen.)
   *
   * <p>This method is a no-op for devices that don't have a proximity sensor.
   *
   * <p>Proximity wake lock will be released if any of the following conditions are true: the audio
   * is routed through bluetooth, a wired headset, or the speaker; the user requested, received a
   * request for, or is in a video call; or the phone is horizontal while in a call.
   */
  private synchronized void updateProximitySensorMode() {
    Trace.beginSection("ProximitySensor.updateProximitySensorMode");
    final int audioRoute = audioModeProvider.getAudioState().getRoute();

    final boolean mIsProximitySensorDisabled = mPrefs.getBoolean(PREF_KEY_DISABLE_PROXI_SENSOR, false);

    if (mIsProximitySensorDisabled) {
        return;
    }

    boolean screenOnImmediately =
        (CallAudioState.ROUTE_WIRED_HEADSET == audioRoute
            || CallAudioState.ROUTE_SPEAKER == audioRoute
            || CallAudioState.ROUTE_BLUETOOTH == audioRoute
            || isAttemptingVideoCall
            || isVideoCall
            || isRttCall);

    // We do not keep the screen off when the user is outside in-call screen and we are
    // horizontal, but we do not force it on when we become horizontal until the
    // proximity sensor goes negative.
    final boolean horizontal = (orientation == AccelerometerListener.ORIENTATION_HORIZONTAL);
    screenOnImmediately |= !uiShowing && horizontal;

    // We do not keep the screen off when dialpad is visible, we are horizontal, and
    // the in-call screen is being shown.
    // At that moment we're pretty sure users want to use it, instead of letting the
    // proximity sensor turn off the screen by their hands.
    screenOnImmediately |= dialpadVisible && horizontal;

    LogUtil.i(
        "ProximitySensor.updateProximitySensorMode",
        "screenOnImmediately: %b, dialPadVisible: %b, "
            + "offHook: %b, horizontal: %b, uiShowing: %b, audioRoute: %s",
        screenOnImmediately,
        dialpadVisible,
        isPhoneOffhook,
        orientation == AccelerometerListener.ORIENTATION_HORIZONTAL,
        uiShowing,
        CallAudioState.audioRouteToString(audioRoute));

    if ((isPhoneOffhook || (hasIncomingCall && proxSpeakerIncallOnly())) && !screenOnImmediately) {
      LogUtil.v("ProximitySensor.updateProximitySensorMode", "turning on proximity sensor");
      // Phone is in use!  Arrange for the screen to turn off
      // automatically when the sensor detects a close object.
      turnOnProximitySensor();
    } else {
      LogUtil.v("ProximitySensor.updateProximitySensorMode", "turning off proximity sensor");
      // Phone is either idle, or ringing.  We don't want any special proximity sensor
      // behavior in either case.
      turnOffProximitySensor(screenOnImmediately);
    }
    Trace.endSection();
  }

 private void updateProxSpeaker() {
    if (sensor != null && proxSensor != null) {
      if (isPhoneOffhook) {
        sensor.registerListener(this, proxSensor,
            SensorManager.SENSOR_DELAY_NORMAL);
      } else {
        sensor.unregisterListener(this);
      }
    }
  }

 private void updateProxRing() {
        if (sensor != null && proxSensor != null) {
            if (hasIncomingCall) {
                sensor.registerListener(this, proxSensor,
                        SensorManager.SENSOR_DELAY_NORMAL);
            } else {
                sensor.unregisterListener(this);
            }
        }
    }

 private void answerProx(boolean isNear) {
    final boolean proxIncallAnswPref =
                (Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.PROXIMITY_AUTO_ANSWER_INCALL_ONLY, 0) == 1);
    if (isNear && telecomManager != null && !isScreenReallyOff() && !hasOngoingCall && proxIncallAnswPref) {
    telecomManager.acceptRingingCall();
    }
 }

   private boolean proxSpeakerIncallOnly() {
    return  Settings.System.getInt(mContext.getContentResolver(),
        Settings.System.PROXIMITY_AUTO_SPEAKER_INCALL_ONLY, 0) == 1;
   }

   private void setProxSpeaker(final boolean speaker) {
    // remove any pending audio changes scheduled
    handler.removeCallbacks(activateSpeaker);
     final int audioState = audioModeProvider.getAudioState().getRoute();
    proxSpeakerDelay = Settings.System.getInt(mContext.getContentResolver(),
        Settings.System.PROXIMITY_AUTO_SPEAKER_DELAY, 3000);
     // if phone off hook (call in session), and prox speaker feature is on
    if (isPhoneOffhook && Settings.System.getInt(mContext.getContentResolver(),
        Settings.System.PROXIMITY_AUTO_SPEAKER, 0) == 1
        // as long as AudioState isn't currently wired headset or bluetooth
        && audioState != CallAudioState.ROUTE_WIRED_HEADSET
        && audioState != CallAudioState.ROUTE_BLUETOOTH) {
       // okay, we're good to start switching audio mode on proximity
       // if proximity sensor determines audio mode should be speaker,
      // but it currently isn't
      if (speaker && audioState != CallAudioState.ROUTE_SPEAKER) {
         // if prox incall only is off, we set to speaker as long as phone
        // is off hook, ignoring whether or not the call state is outgoing
        if (!proxSpeakerIncallOnly()
            // or if prox incall only is on, we have to check the call
            // state to decide if AudioState should be speaker
            || (proxSpeakerIncallOnly() && !isPhoneOutgoing)) {
          handler.postDelayed(activateSpeaker, proxSpeakerDelay);
        }
      } else if (!speaker) {
        TelecomAdapter.getInstance().setAudioRoute(CallAudioState.ROUTE_EARPIECE);
      }
    }
  }

  /**
   * Implementation of a {@link DisplayListener} that maintains a binary state: Screen on vs screen
   * off. Used by the proximity sensor manager to decide whether or not it needs to listen to
   * accelerometer events.
   */
  public class ProximityDisplayListener implements DisplayListener {

    private DisplayManager displayManager;
    private boolean isDisplayOn = true;

    ProximityDisplayListener(DisplayManager displayManager) {
      this.displayManager = displayManager;
    }

    void register() {
      displayManager.registerDisplayListener(this, null);
    }

    void unregister() {
      displayManager.unregisterDisplayListener(this);
    }

    @Override
    public void onDisplayRemoved(int displayId) {}

    @Override
    public void onDisplayChanged(int displayId) {
      if (displayId == Display.DEFAULT_DISPLAY) {
        final Display display = displayManager.getDisplay(displayId);

        final boolean isDisplayOn = display.getState() != Display.STATE_OFF;
        // For call purposes, we assume that as long as the screen is not truly off, it is
        // considered on, even if it is in an unknown or low power idle state.
        if (isDisplayOn != this.isDisplayOn) {
          this.isDisplayOn = isDisplayOn;
          onDisplayStateChanged(this.isDisplayOn);
        }
      }
    }

    @Override
    public void onDisplayAdded(int displayId) {}
  }
}
