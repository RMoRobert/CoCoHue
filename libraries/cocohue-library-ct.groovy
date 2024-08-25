// Version 1.0.1

library (
   base: "driver",
   author: "RMoRobert",
   category: "Convenience",
   description: "For internal CoCoHue use only. Not intended for external use. Contains CT-related code shared by many CoCoHue drivers.",
    name: "CoCoHue_CT_Lib",
   namespace: "RMoRobert"
)

void setColorTemperature(Number colorTemperature, Number level = null, Number transitionTime = null) {
   if (logEnable == true) log.debug "setColorTemperature($colorTemperature, $level, $transitionTime)"
   state.lastKnownColorMode = "CT"
   // For backwards compatibility; will be removed in future version:
   if (colorStaging) {
      log.warn "Color prestaging preference enabled and setColorTemperature() called. This is deprecated and may be removed in the future. Please move to new presetColorTemperature() command."
      if (device.currentValue("switch") != "on") {
         presetColorTemperature(colorTemperature)
         return
      }
   }
   Integer newCT = scaleCTToBridge(colorTemperature)
   Integer scaledRate = defaultLevelTransitionTime/100
   if (transitionTime != null) {
      scaledRate = (transitionTime * 10) as Integer
   }
   else if (settings["transitionTime"] != null) {
      scaledRate = ((settings["transitionTime"] as Integer) / 100) as Integer
   }
   Map bridgeCmd = ["on": true, "ct": newCT, "transitiontime": scaledRate]
   if (level) {
      bridgeCmd << ["bri": scaleBriToBridge(level)]
   }
   Map prestagedCmds = getPrestagedCommands()
   if (prestagedCmds) {
      bridgeCmd = prestagedCmds + bridgeCmd
   }
   sendBridgeCommandV1(bridgeCmd)
}

// Not a standard command (yet?), but I hope it will get implemented as such soon in
// the same manner as this. Otherwise, subject to change if/when that happens....
void presetColorTemperature(Number colorTemperature) {
   if (logEnable == true) log.debug "presetColorTemperature($colorTemperature)"
   Boolean isOn = device.currentValue("switch") == "on"
   doSendEvent("colorTemperaturePreset", colorTemperature)
   if (isOn) {
      setColorTemperature(colorTemperature)
   } else {
      state.remove("presetCT")
      state.presetColorTemperature = true
      state.presetHue = false
      state.presetSaturation = false
   }
}

/**
 * Scales CT from Kelvin (Hubitat units) to mireds (Hue units)
 */
private Integer scaleCTToBridge(Number kelvinCT, Boolean checkIfInRange=true) {
   Integer mireds = Math.round(1000000/kelvinCT) as Integer
   if (checkIfInRange == true) {
      if (mireds < minMireds) mireds = minMireds
      else if (mireds > maxMireds) mireds = maxMireds
   }
   return mireds
}

/**
 * Scales CT from mireds (Hue units) to Kelvin (Hubitat units)
 */
private Integer scaleCTFromBridge(Number mireds) {
   Integer kelvin = Math.round(1000000/mireds) as Integer
   return kelvin
}

/**
 * Reads device preference for CT transition time, or provides default if not available; device
 * can use input(name: ctTransitionTime, ...) to provide this
 */
Integer getScaledCTTransitionTime() {
   Integer scaledRate = null
   if (settings.ctTransitionTime == null || settings.ctTransitionTime == "-2" || settings.ctTransitionTime == -2) {
      // keep null; will result in not specifiying with command
   }
   else if (settings.ctTransitionTime == "-1" || settings.ctTransitionTime == -1) {
      scaledRate = (settings.transitionTime != null) ? Math.round(settings.transitionTime.toFloat() / 100) : (defaultTransitionTime != null ? defaultTransitionTime : 250)
   }
   else {
      scaledRate = Math.round(settings.ctTransitionTime.toFloat() / 100)
   }
   return scaledRate
}


// Hubitat-provided ct/name mappings
void setGenericTempName(temp) {
   if (!temp) return
   String genericName
   Integer value = temp.toInteger()
   if (value <= 2000) genericName = "Sodium"
   else if (value <= 2100) genericName = "Starlight"
   else if (value < 2400) genericName = "Sunrise"
   else if (value < 2800) genericName = "Incandescent"
   else if (value < 3300) genericName = "Soft White"
   else if (value < 3500) genericName = "Warm White"
   else if (value < 4150) genericName = "Moonlight"
   else if (value <= 5000) genericName = "Horizon"
   else if (value < 5500) genericName = "Daylight"
   else if (value < 6000) genericName = "Electronic"
   else if (value <= 6500) genericName = "Skylight"
   else if (value < 20000) genericName = "Polar"
   else genericName = "undefined" // shouldn't happen, but just in case
   if (device.currentValue("colorName") != genericName) doSendEvent("colorName", genericName)
}
