// Version 1.0.2

library (
   base: "driver",
   author: "RMoRobert",
   category: "Convenience",
   description: "For internal CoCoHue use only. Not intended for external use. Contains hue/saturation-related code shared by many CoCoHue drivers.",
   name: "CoCoHue_HueSat_Lib",
   namespace: "RMoRobert"
)

void setColor(Map value) {
   if (logEnable == true) log.debug "setColor($value)"
   state.lastKnownColorMode = "RGB"
   // For backwards compatibility; will be removed in future version:
   if (colorStaging) {
      log.warn "Color prestaging preference enabled and setColor() called. This is deprecated and may be removed in the future. Please move to new presetColor() command."
      if (device.currentValue("switch") != "on") {
         presetColor(value)
         return
      }
   }
   if (value.hue == null || value.hue == "NaN" || value.saturation == null || value.saturation == "NaN") {
      if (logEnable == true) log.debug "Exiting setColor because no hue and/or saturation set"
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
   sendBridgeCommandV1(bridgeCmd)
}

// Really a hack to get this usable from the admin UI since you can only have one COLOR_MAP input, which
// is already implicitly taken by setColor(). Accepts JSON object like {"hue": 10, "saturation": 100, "level": 50}
// and will convert to Groovy map for use with other implenentation of this command (which I hope will be standardized
// some day..)
void presetColor(String jsonValue) {
   if (logEnable == true) log.debug "presetColor(String $jsonValue)"
   Map value = new groovy.json.JsonSlurper().parseText(jsonValue)
   presetColor(value)
}

// Not currently a standard Hubitat command, so implementation subject to change if it becomes one;
// for now, assuming it may be done by taking a color map like setColor() (but see also JSON variant above)
// May also need presetHue() and presetSaturation(), but not including for now...
void presetColor(Map value) {
   if (logEnable == true) log.debug "presetColor(Map $value)"
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
   if (logEnable == true) log.debug "setHue($value)"
   state.lastKnownColorMode = "RGB"
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
   sendBridgeCommandV1(bridgeCmd)
}

void setSaturation(value) {
   if (logEnable == true) log.debug "setSaturation($value)"
   state.lastKnownColorMode = "RGB"
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
   sendBridgeCommandV1(bridgeCmd)
}

Integer scaleHueToBridge(hubitatHue) {
   Integer scaledHue = Math.round(hubitatHue.toBigDecimal() / (hiRezHue ? 360 : 100) * 65535)
   if (scaledHue < 0) scaledHue = 0
   else if (scaledHue > 65535) scaledHue = 65535
   return scaledHue
}

Integer scaleHueFromBridge(bridgeLevel) {
   Integer scaledHue = Math.round(bridgeLevel.toBigDecimal() / 65535 * (hiRezHue ? 360 : 100))
   if (scaledHue < 0) scaledHue = 0
   else if (scaledHue > 360) scaledHue = 360
   else if (scaledHue > 100 && !hiRezHue) scaledHue = 100
   return scaledHue
}

Integer scaleSatToBridge(hubitatSat) {
   Integer scaledSat = Math.round(hubitatSat.toBigDecimal() / 100 * 254)
   if (scaledSat < 0) scaledSat = 0
   else if (scaledSat > 254) scaledSat = 254
   return scaledSat
}

Integer scaleSatFromBridge(bridgeSat) {
   Integer scaledSat = Math.round(bridgeSat.toBigDecimal() / 254 * 100)
   if (scaledSat < 0) scaledSat = 0
   else if (scaledSat > 100) scaledSat = 100
   return scaledSat
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
   if (hiRezHue) hue = (hue / 3.6)
   colorName = convertHueToGenericColorName(hue, device.currentSaturation ?: 100)
   if (device.currentValue("colorName") != colorName) doSendEvent("colorName", colorName)
}