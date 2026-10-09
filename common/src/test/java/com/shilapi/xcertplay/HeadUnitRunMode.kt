package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.orchestration.CarPlayRunMode
import org.robolectric.RuntimeEnvironment

/**
 * TiPlay starts in phone + browser mode ([AirPlayPersistence.loadRunMode]). Tests of head-unit behaviour (BYD
 * outputs, dashboard map, wheel keys, ADB, start with the car) choose the head unit explicitly in their setup,
 * before the activity or service under test reads the mode.
 */
internal fun useHeadUnitMode(context: Context = RuntimeEnvironment.getApplication()) {
    AirPlayPersistence.saveRunMode(context, CarPlayRunMode.HEAD_UNIT)
}
