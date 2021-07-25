library (
   base: "driver",
   author: "RMoRobert",
   category: "Convenience",
   description: "For internal CoCoHue use only. Not intended for external use. Contains brightness/level-related code shared by many CoCoHue drivers.",
   name: "CoCoHue_Bri_Lib",
   namespace: "RMoRobert"
)

// "SwitchLevel" commands:

void startLevelChange(direction) {
   if (enableDebug == true) log.debug "startLevelChange($direction)..."
   Map cmd = ["bri": (direction == "up" ? 254 : 1),
            "transitiontime": ((settings["levelChangeRate"] == "fast" || !settings["levelChangeRate"]) ?
                                 30 : (settings["levelChangeRate"] == "slow" ? 60 : 45))]
   sendBridgeCommand(cmd, false) 
}

void stopLevelChange() {
   if (enableDebug == true) log.debug "stopLevelChange()..."
   Map cmd = ["bri_inc": 0]
   sendBridgeCommand(cmd, false) 
}

void setLevel(value) {
   if (enableDebug == true) log.debug "setLevel($value)"
   setLevel(value, ((transitionTime != null ? transitionTime.toFloat() : defaultLevelTransitionTime.toFloat())) / 1000)
}

void setLevel(Number value, Number rate) {
   if (enableDebug == true) log.debug "setLevel($value, $rate)"
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

void presetLevel(Number level) {
   if (enableDebug == true) log.debug "presetLevel($level)"
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

// Internal methods for scaling

/**
 * Scales Hubitat's 1-100 brightness levels to Hue Bridge's 1-254
 */
Integer scaleBriToBridge(hubitatLevel) {
   Integer scaledLevel =  Math.round(hubitatLevel == 1 ? 1 : hubitatLevel.toBigDecimal() / 100 * 254)
   return Math.round(scaledLevel)
}

/**
 * Scales Hue Bridge's 1-254 brightness levels to Hubitat's 1-100
 */
Integer scaleBriFromBridge(bridgeLevel) {
   Integer scaledLevel = Math.round(bridgeLevel.toBigDecimal() / 254 * 100)
   if (scaledLevel < 1) scaledLevel = 1
   return Math.round(scaledLevel)
}