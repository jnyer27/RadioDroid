package com.radiodroid.app

import com.chaquo.python.android.PyApplication

/**
 * Custom Application class that extends [PyApplication].
 *
 * Chaquopy requires the Python runtime to be started before any call to
 * [com.chaquo.python.Python.getInstance()].  Extending [PyApplication] is the
 * standard way to do this: it calls Python.start(AndroidPlatform(this)) in
 * Application.onCreate(), guaranteeing the runtime is ready before any
 * Activity or Service starts.
 *
 * Registered in AndroidManifest.xml via android:name=".RadioDroidApp".
 */
class RadioDroidApp : PyApplication()
