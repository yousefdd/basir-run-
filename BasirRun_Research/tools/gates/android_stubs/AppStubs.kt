// Stand-ins for app classes outside the files under test (the real ones need Android/ONNX).
@file:Suppress("unused", "UNUSED_PARAMETER")
package com.basir.run
class Prefs(context: android.content.Context) { var vibrateOn = true; var hapticStrength = 1f; var hapticDuration = 1f }
