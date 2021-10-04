// Version 1.0.1

library (
   base: "driver",
   author: "RMoRobert",
   category: "Convenience",
   description: "For internal CoCoHue use only. Not intended for external use. Contains hue/saturation-related code shared by many CoCoHue drivers.",
   name: "CoCoHue_HueSat_Lib",
   namespace: "RMoRobert"
)

void setColor(Map value) {
   if (enableDebug == true) log.debug "setColor($value)"
   // For backwards compatibility; will be removed in future version:
   if (colorStaging) {
      log.warn "Color prestaging preference enabled and setColor() called. This is deprecated and may be removed in the future. Please move to new presetColor() command."
      if (device.currentValue("switch") != "on") {
         presetColor(value)
         return
      }
   }
   if (value.hue == null || value.hue == "NaN" || value.saturation == null || value.saturation == "NaN") {
      if (enableDebug == true) log.debug "Exiting setColor because no hue and/or saturation set"
      return
   }
   Map bridgeCmd 
   Integer newHue = scaleHueToBridge(value.hue)
   Integer newSat = scaleSatToBridge(value.saturation)
   Integer newBri = (value.level != null && value.level != "NaN") ? scaleBriToBridge(value.level) : null
   Integer scaledRate = value.rate != null ? Math.round(value.rate * 10).toInteger() : getScaledRGBTransitionTime()
   if (scaledRate == null) {
      bridgeCmd = ["on": true, "hue": newHue, "sat": newSat]
   }
   else {
      bridgeCmd = ["on": true, "hue": newHue, "sat": newSat, "transitiontime": scaledRate]
   }
   if (newBri) bridgeCmd << ["bri": newBri]
   Map prestagedCmds = getPrestagedCommands()
   if (prestagedCmds) {
      bridgeCmd = prestagedCmds + bridgeCmd
   }
   sendBridgeCommand(bridgeCmd)
}

// Really a hack to get this usable from the admin UI since you can only have one COLOR_MAP input, which
// is already implicitly taken by setColor(). Accepts JSON object like {"hue": 10, "saturation": 100, "level": 50}
// and will convert to Groovy map for use with other implenentation of this command (which I hope will be standardized
// some day..)
void presetColor(String jsonValue) {
   if (enableDebug == true) log.debug "presetColor(String $jsonValue)"
   Map value = new groovy.json.JsonSlurper().parseText(jsonValue)
   presetColor(value)
}

// Not currently a standard Hubitat command, so implementation subject to change if it becomes one;
// for now, assuming it may be done by taking a color map like setColor() (but see also JSON variant above)
// May also need presetHue() and presetSaturation(), but not including for now...
void presetColor(Map value) {
   if (enableDebug == true) log.debug "presetColor(Map $value)"
   if (value.hue != null) {
      doSendEvent("huePreset", value.hue)
   }
   if (value.saturation != null) {
      doSendEvent("saturationPreset", value.saturation)
   }
   if (value.level != null) {
      doSendEvent("levelPreset", value.level)
   }
   Boolean isOn = device.currentValue("switch") == "on"
   if (isOn) {
      setColor(value)
   } else {
      state.presetHue = (value.hue != null)
      state.presetSaturation = (value.saturation != null)
      state.presetLevel = (value.level != null)
      state.presetColorTemperature = false
   }
}

void setHue(value) {
   if (enableDebug == true) log.debug "setHue($value)"
   // For backwards compatibility; will be removed in future version:
   if (colorStaging) {
      log.warn "Color prestaging preference enabled and setHue() called. This is deprecated and may be removed in the future. Please move to new presetColor() command."
      if (device.currentValue("switch") != "on") {
         presetColor([hue: value])
         return
      }
   }
   Integer newHue = scaleHueToBridge(value)
   Integer scaledRate = ((transitionTime != null ? transitionTime.toBigDecimal() : defaultLevelTransitionTime) / 100).toInteger()
   Map bridgeCmd = ["on": true, "hue": newHue, "transitiontime": scaledRate]
   Map prestagedCmds = getPrestagedCommands()
   if (prestagedCmds) {
      bridgeCmd = prestagedCmds + bridgeCmd
   }
   sendBridgeCommand(bridgeCmd)
}

void setSaturation(value) {
   if (enableDebug == true) log.debug "setSaturation($value)"
   // For backwards compatibility; will be removed in future version:
   if (colorStaging) {
      log.warn "Color prestaging preference enabled and setSaturation() called. This is deprecated and may be removed in the future. Please move to new presetColor() command."
      if (device.currentValue("switch") != "on") {
         presetColor([saturation: value])
         return
      }
   }
   Integer newSat = scaleSatToBridge(value)
   Integer scaledRate = ((transitionTime != null ? transitionTime.toBigDecimal() : 1000) / 100).toInteger()
   Map bridgeCmd = ["on": true, "sat": newSat, "transitiontime": scaledRate]
   Map prestagedCmds = getPrestagedCommands()
   if (prestagedCmds) {
      bridgeCmd = prestagedCmds + bridgeCmd
   }
   sendBridgeCommand(bridgeCmd)
}

Integer scaleHueToBridge(hubitatLevel) {
   Integer scaledLevel = Math.round(hubitatLevel.toBigDecimal() / (hiRezHue ? 360 : 100) * 65535)
   if (scaledLevel < 0) scaledLevel = 0
   else if (scaledLevel > 65535) scaledLevel = 65535
   return scaledLevel
}

Integer scaleHueFromBridge(bridgeLevel) {
   Integer scaledLevel = Math.round(bridgeLevel.toBigDecimal() / 65535 * (hiRezHue ? 360 : 100))
   if (scaledLevel < 0) scaledLevel = 0
   else if (scaledLevel > 360) scaledLevel = 360
   else if (scaledLevel > 100 && !hiRezHue) scaledLevel = 100
   return scaledLevel
}

Integer scaleSatToBridge(hubitatLevel) {
   Integer scaledLevel = Math.round(hubitatLevel.toBigDecimal() / 100 * 254)
   if (scaledLevel < 0) scaledLevel = 0
   else if (scaledLevel > 254) scaledLevel = 254
   return scaledLevel
   return scaleHueFromBridge()
}

Integer scaleSatFromBridge(bridgeLevel) {
   Integer scaledLevel = Math.round(bridgeLevel.toBigDecimal() / 254 * 100)
   if (scaledLevel < 0) scaledLevel = 0
   else if (scaledLevel > 100) scaledLevel = 100
   return scaledLevel
}


/**
 * Reads device preference for setColor/RGB transition time, or provides default if not available; device
 * can use input(name: rgbTransitionTime, ...) to provide this
 */
Integer getScaledRGBTransitionTime() {
   Integer scaledRate = null
   if (settings.rgbTransitionTime == null || settings.rgbTransitionTime == "-2" || settings.rgbTransitionTime == -2) {
      // keep null; will result in not specifying with command
   }
   else if (settings.rgbTransitionTime == "-1" || settings.rgbTransitionTime == -1) {
      scaledRate = (settings.transitionTime != null) ? Math.round(settings.transitionTime.toFloat() / 100) : defaultTransitionTime
   }
   else {
      scaledRate = Math.round(settings.rgbTransitionTime.toFloat() / 100)
   }
}

// Hubiat-provided color/name mappings
void setGenericName(hue) {
   String colorName
   hue = hue.toInteger()
   if (!hiRezHue) hue = (hue * 3.6)
   switch (hue.toInteger()) {
      case 0..15: colorName = "Red"
         break
      case 16..45: colorName = "Orange"
         break
      case 46..75: colorName = "Yellow"
         break
      case 76..105: colorName = "Chartreuse"
         break
      case 106..135: colorName = "Green"
         break
      case 136..165: colorName = "Spring"
         break
      case 166..195: colorName = "Cyan"
         break
      case 196..225: colorName = "Azure"
         break
      case 226..255: colorName = "Blue"
         break
      case 256..285: colorName = "Violet"
         break
      case 286..315: colorName = "Magenta"
         break
      case 316..345: colorName = "Rose"
         break
      case 346..360: colorName = "Red"
         break
      default: colorName = "undefined" // shouldn't happen, but just in case
         break            
   }
   if (device.currentValue("saturation") < 1) colorName = "White"
   if (device.currentValue("colorName") != colorName) doSendEvent("colorName", colorName)
}