// Version 1.0.0

library (
   base: "driver",
   author: "RMoRobert",
   category: "Convenience",
   description: "For internal CoCoHue use only. Not intended for external use. Contains flash-related code shared by many CoCoHue drivers.",
   name: "CoCoHue_Flash_Lib",
   namespace: "RMoRobert"
)

void flash() {
   if (enableDebug == true) log.debug "flash()"
   if (settings.enableDesc == true) log.info("${device.displayName} started 15-cycle flash")
   Map<String,String> cmd = ["alert": "lselect"]
   sendBridgeCommand(cmd, false) 
}

void flashOnce() {
   if (enableDebug == true) log.debug "flashOnce()"
   if (settings.enableDesc == true) log.info("${device.displayName} started 1-cycle flash")
   Map<String,String> cmd = ["alert": "select"]
   sendBridgeCommand(cmd, false) 
}

void flashOff() {
   if (enableDebug == true) log.debug "flashOff()"
   if (settings.enableDesc == true) log.info("${device.displayName} was sent command to stop flash")
   Map<String,String> cmd = ["alert": "none"]
   sendBridgeCommand(cmd, false) 
}