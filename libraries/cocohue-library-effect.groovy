// Version 1.0.1

library (
   base: "driver",
   author: "RMoRobert",
   category: "Convenience",
   description: "For internal CoCoHue use only. Not intended for external use. Contains effects-related code shared by many CoCoHue drivers.",
   name: "CoCoHue_Effect_Lib",
   namespace: "RMoRobert"
)

void setEffect(String effect) {
   if (logEnable == true) log.debug "setEffect($effect)"
   def id = lightEffects.find { it.value == effect }
   if (id != null) setEffect(id.key)
}

void setEffect(Number id) {
   if (logEnable == true) log.debug "setEffect($id)"
   sendBridgeCommand(["effect": (id == 1 ? "colorloop" : "none"), "on": true])
}

void setNextEffect() {
   if (logEnable == true) log.debug"setNextEffect()"
   Integer currentEffect = state.crntEffectId ?: 0
   currentEffect++
   if (currentEffect > maxEffectNumber) currentEffect = 0
   setEffect(currentEffect)
}

void setPreviousEffect() {
   if (logEnable == true) log.debug "setPreviousEffect()"
   Integer currentEffect = state.crntEffectId ?: 0
   currentEffect--
   if (currentEffect < 0) currentEffect = 1
   setEffect(currentEffect)
}

