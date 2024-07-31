// Version 1.0.0

library (
   base: "driver",
   author: "RMoRobert",
   category: "Convenience",
   description: "For internal CoCoHue use only. Not intended for external use. Contains prestaging-related code shared by many CoCoHue drivers.",
   name: "CoCoHue_Prestage_Lib",
   namespace: "RMoRobert"
)

// Note: includes internal driver methods only; actual "prestating"/"preset" commands are in driver or other library

/**
 * Returns Map containing any commands that would need to be sent to Bridge if anything is currently prestaged.
 * Otherwise, returns empty Map.
 * @param unsetPrestagingState If set to true (default), clears prestage flag
*/
Map getPrestagedCommands(Boolean unsetPrestagingState=true) {
   if (logEnable == true) log.debug "getPrestagedCommands($unsetPrestagingState)"
   Map cmds = [:]
   if (state.presetLevel == true) {
      cmds << [bri: scaleBriToBridge(device.currentValue("levelPreset"))]
   }
   if (state.presetColorTemperature == true) {
      cmds << [ct: scaleCTToBridge(device.currentValue("colorTemperaturePreset"))]
   }
   if (state.presetHue == true) {
      cmds << [hue: scaleHueToBridge(device.currentValue("huePreset"))]
   }
   if (state.presetSaturation == true) {
      cmds << [sat: scaleSatToBridge(device.currentValue("saturationPreset"))]
   }
   if (unsetPrestagingState == true) {
      clearPrestagedCommands()
   }
   if (logEnable == true) log.debug "Returning: $cmds"
   return cmds
}

void clearPrestagedCommands() {
   state.presetLevel = false
   state.presetColorTemperature = false
   state.presetHue = false
   state.presetSaturation = false
}
