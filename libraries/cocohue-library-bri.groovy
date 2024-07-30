// Version 1.0.4

// 1.0.4  - accept String for setLevel() level also 
// 1.0.3  - levelhandling tweaks

library (
   base: "driver",
   author: "RMoRobert",
   category: "Convenience",
   description: "For internal CoCoHue use only. Not intended for external use. Contains brightness/level-related code shared by many CoCoHue drivers.",
   name: "CoCoHue_Bri_Lib",
   namespace: "RMoRobert"
)

// "SwitchLevel" commands:

void startLevelChange(String direction) {
   if (logEnable == true) log.debug "startLevelChange($direction)..."
   Map cmd = ["bri": (direction == "up" ? 254 : 1),
            "transitiontime": ((settings["levelChangeRate"] == "fast" || !settings["levelChangeRate"]) ?
                                 30 : (settings["levelChangeRate"] == "slow" ? 60 : 45))]
   sendBridgeCommand(cmd, false) 
}

void stopLevelChange() {
   if (logEnable == true) log.debug "stopLevelChange()..."
   Map cmd = ["bri_inc": 0]
   sendBridgeCommand(cmd, false) 
}

void setLevel(value) {
   if (logEnable == true) log.debug "setLevel($value)"
   setLevel(value, ((transitionTime != null ? transitionTime.toFloat() : defaultLevelTransitionTime.toFloat())) / 1000)
}

void setLevel(Number value, Number rate) {
   if (logEnable == true) log.debug "setLevel($value, $rate)"
   // For backwards compatibility; will be removed in future version:
   if (levelStaging) {
      log.warn "Level prestaging preference enabled and setLevel() called. This is deprecated and may be removed in the future. Please move to new, standard presetLevel() command."
      if (device.currentValue("switch") != "on") {
         presetLevel(value)
         return
      }
   }
   if (value < 0) value = 1
   else if (value > 100) value = 100
   else if (value == 0) {
      off(rate)
      return
   }
   Integer newLevel = scaleBriToBridge(value)
   Integer scaledRate = (rate * 10).toInteger()
   Map bridgeCmd = [
      "on": true,
      "bri": newLevel,
      "transitiontime": scaledRate
   ]
   Map prestagedCmds = getPrestagedCommands()
   if (prestagedCmds) {
      bridgeCmd = prestagedCmds + bridgeCmd
   }
   sendBridgeCommand(bridgeCmd)
}

void setLevel(value, rate) {
   if (logEnable == true) log.debug "setLevel(Object $value, Object $rate)"
   Float floatLevel = Float.parseFloat(value.toString())
   Integer intLevel = Math.round(floatLevel)
   Float floatRate = Float.parseFloat(rate.toString())
   setLevel(intLevel, floatRate)
}

void presetLevel(Number level) {
   if (logEnable == true) log.debug "presetLevel($level)"
   if (level < 0) level = 1
   else if (level > 100) level = 100
   Integer newLevel = scaleBriToBridge(level)
   Integer scaledRate = ((transitionTime != null ? transitionTime.toBigDecimal() : 1000) / 1000).toInteger()
   Boolean isOn = device.currentValue("switch") == "on"
   doSendEvent("levelPreset", level)
   if (isOn) {
      setLevel(level)
   } else {
      state.presetLevel = true
   }
}

/**
 * Reads device preference for on() transition time, or provides default if not available; device
 * can use input(name: onTransitionTime, ...) to provide this
 */
Integer getScaledOnTransitionTime() {
   Integer scaledRate = null
   if (settings.onTransitionTime == null || settings.onTransitionTime == "-2" || settings.onTransitionTime == -2) {
      // keep null; will result in not specifiying with command
   }
   else {
      scaledRate = Math.round(settings.onTransitionTime.toFloat() / 100)
   }
   return scaledRate
}


/**
 * Reads device preference for off() transition time, or provides default if not available; device
 * can use input(name: onTransitionTime, ...) to provide this
 */
Integer getScaledOffTransitionTime() {
   Integer scaledRate = null
   if (settings.offTransitionTime == null || settings.offTransitionTime == "-2" || settings.offTransitionTime == -2) {
      // keep null; will result in not specifiying with command
   }
   else if (settings.offTransitionTime == "-1" || settings.offTransitionTime == -1) {
      scaledRate = getScaledOnTransitionTime()
   }
   else {
      scaledRate = Math.round(settings.offTransitionTime.toFloat() / 100)
   }
   return scaledRate
}

// Internal methods for scaling


/**
 * Scales Hubitat's 1-100 brightness levels to Hue Bridge's 1-254 (or 0-100)
 * @param apiVersion: Use "1" (default) for classic, 1-254 API values; use "2" for v2/SSE 0.0-100.0 values (note: 0.0 is on)
 */
Number scaleBriToBridge(Number hubitatLevel, String apiVersion="1") {
   if (apiVersion != "2") {
      Integer scaledLevel
      scaledLevel = Math.round(hubitatLevel == 1 ? 1 : hubitatLevel.toBigDecimal() / 100 * 254)
      return Math.round(scaledLevel) as Integer
   }
   else {
      BigDecimal scaledLevel
      // for now, a quick cheat to make 1% the Hue minimum (should scale other values proportionally in future)
      scaledLevel = hubitatLevel == 1 ? 0.0 : hubitatLevel.toBigDecimal().setScale(2, java.math.RoundingMode.HALF_UP)
      return scaledLevel
   }
}

/**
 * Scales Hue Bridge's 1-254 brightness levels to Hubitat's 1-100 (or 0-100)
 * @param apiVersion: Use "1" (default) for classic, 1-254 API values; use "2" for v2/SSE 0.0-100.0 values (note: 0.0 is on)
 */
Integer scaleBriFromBridge(Number bridgeLevel, String apiVersion="1") {
   Integer scaledLevel
   if (apiVersion != "2") {
      scaledLevel = Math.round(bridgeLevel.toBigDecimal() / 254 * 100)
      if (scaledLevel < 1) scaledLevel = 1
   }
   else {
      // for now, a quick cheat to make 1% the Hue minimum (should scale other values proportionally in future)
      scaledLevel = Math.round(bridgeLevel <= 1.49 && bridgeLevel > 0.001 ? 1 : bridgeLevel)
   }
   return scaledLevel
}