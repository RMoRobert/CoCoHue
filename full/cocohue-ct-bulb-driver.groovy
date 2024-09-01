/*
 * =============================  CoCoHue CT Bulb (Driver) ===============================
 *
 *  Copyright 2019-2024 Robert Morris
 * 
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 * =======================================================================================

 *  Last modified: 2024-08-31
 *
 *  Changelog:
 *  v5.0    - Use API v2 by default, remove deprecated features
 *  v4.2    - Library updates, prep for more v2 API
 *  v4.1.8  - Fix for division by zero for unexpected colorTemperature values
 *  v4.1.7  - Fix for unexpected Hubitat event creation when v2 API reports level of 0
 *  v4.1.5  - Improved v2 brightness parsing
 *  v4.1.4  - Improved error handling, fix missing battery for motion sensors
 *  v4.0.2  - Fix to avoid unepected "off" transition time
 *  v4.0    - Add SSE support for push 
 *  v3.5.1  - Refactor some code into libraries (code still precompiled before upload; should not have any visible changes)
 *  v3.5    - Add LevelPreset capability (replaces old level prestaging option); added "reachable" attribte
              from Bridge to bulb and group drivers (thanks to @jtp10181 for original implementation)
 *  v3.1.3  - Adjust setLevel(0) to honor rate
 *  v3.1.1  - Fix for setColorTempeature() not turning bulb on in some cases
 *  v3.1    - Improved error handling and debug logging; added optional setColorTemperature parameters
 *  v3.0    - Fix so events no created until Bridge response received (as was done for other drivers in 2.0); improved HTTP error handling
 *  v2.1.1  - Improved rounding for level (brightness) to/from Bridge
 *  v2.1    - More static typing
 *  v2.0    - Added startLevelChange rate option; improved HTTP error handling; attribute events now generated
 *            only after hearing back from Bridge; Bridge online/offline status improvements
 *  v1.9    - Initial release (based on RGBW bulb driver)
 */ 







import groovy.transform.Field
import hubitat.scheduling.AsyncResponse

@Field static final Integer debugAutoDisableMinutes = 30

// Currently works for all Hue bulbs; can adjust if needed:
@Field static final minMireds = 153
@Field static final maxMireds = 500

// Default preference values
@Field static final BigDecimal defaultLevelTransitionTime = 1000

// Default list of command Map keys to ignore if SSE enabled and command is sent from hub (not polled from Bridge), used to
// ignore duplicates that are expected to be processed from SSE momentarily:
// (for CT devices, should cover most things)
@Field static final List<String> listKeysToIgnoreIfSSEEnabledAndNotFromBridge = ["on", "ct", "bri"]

metadata {
   definition(name: DRIVER_NAME_CT_BULB, namespace: NAMESPACE, author: "Robert Morris", importUrl: "https://raw.githubusercontent.com/HubitatCommunity/CoCoHue/master/drivers/cocohue-ct-bulb-driver.groovy") {
      capability "Actuator"
      capability "ColorTemperature"
      capability "Refresh"
      capability "Switch"
      capability "SwitchLevel"
      capability "ChangeLevel"
      capability "Light"

      command "flash"
      command "flashOnce"
      command "flashOff"

      attribute "reachable", "string"
   }

   preferences {
      input name: "transitionTime", type: "enum", description: "", title: "Transition time", options:
         [[0:"ASAP"],[400:"400ms"],[500:"500ms"],[1000:"1s"],[1500:"1.5s"],[2000:"2s"],[5000:"5s"]], defaultValue: 400
      input name: "levelChangeRate", type: "enum", description: "", title: '"Start level change" rate', options:
         [["slow":"Slow"],["medium":"Medium"],["fast":"Fast (default)"]], defaultValue: "fast"      
      input name: "ctTransitionTime", type: "enum", description: "", title: "Color temperature transition time", options:
         [[(-2): "Hue default/do not specify"],[(-1): "Use level transition time (default)"],[0:"ASAP"],[200:"200ms"],[400:"400ms (default)"],[500:"500ms"],[1000:"1s"],[1500:"1.5s"],[2000:"2s"],[5000:"5s"]], defaultValue: -1
      input name: "updateGroups", type: "bool", description: "", title: "Update state of groups immediately when bulb state changes (applicable only if not using V2 API/eventstream)",
         defaultValue: false
      input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
      input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
   }
}

void installed() {
   log.debug "installed()"
   initialize()
}

void updated() {
   log.debug "updated()"
   initialize()
}

void initialize() {
   log.debug "initialize()"
   if (logEnable) {
      log.debug "Debug logging will be automatically disabled in ${debugAutoDisableMinutes} minutes"
      runIn(debugAutoDisableMinutes*60, "debugOff")
   }
}

// Probably won't happen but...
void parse(String description) {
   log.warn("Running unimplemented parse for: '${description}'")
}

/**
 * Parses V1 Hue Bridge device ID number out of Hubitat DNI for use with Hue V1 API calls
 * Hubitat DNI is created in format "CCH/BridgeMACAbbrev/Light/HueDeviceID", so just
 * looks for number after last "/" character; or try state if DNI is V2 format (avoid if posssible,
 *  as Hue is likely to deprecate V1 ID data in future)
 */
String getHueDeviceIdV1() {
   String id = device.deviceNetworkId.split("/")[-1]
   if (id.length() > 32) { // max length of last part of V1 IDs per V2 API regex spec, though never seen anything non-numeric longer than 2 (or 3?) for non-scenes
      id = state.id_v1?.split("/")[-1]
      if (state.id_v1 == null) {
         log.warn "Attempting to retrieve V1 ID but not in DNI or state."
      }
   }
   return id
}

/**
 * Parses V2 Hue Bridge device ID out of Hubitat DNI for use with Hue V2 API calls
 * Hubitat DNI is created in format "CCH/BridgeMACAbbrev/Light/HueDeviceID", so just
 * looks for string after last "/" character
 */
String getHueDeviceIdV2() {
   return device.deviceNetworkId.split("/")[-1]
}

void on(Number transitionTime = null) {
   if (logEnable == true) log.debug "on()"
   Map bridgeCmd = ["on": true]
   if (transitionTime != null) {
      scaledRate = (transitionTime * 10) as Integer
      bridgeCmd << ["transitiontime": scaledRate]
   }
   sendBridgeCommandV1(bridgeCmd)
}

void off(Number transitionTime = null) {
   if (logEnable == true) log.debug "off()"
   Map bridgeCmd
   Integer scaledRate = transitionTime != null ? Math.round(transitionTime * 10).toInteger() : null
   if (scaledRate == null) {
      bridgeCmd = ["on": false]
   }
   else {
      bridgeCmd = ["on": false, "transitiontime": scaledRate]
   }
   sendBridgeCommandV1(bridgeCmd)
}

void refresh() {
   log.warn "Refresh CoCoHue Bridge device instead of individual device to update (all) bulbs/groups"
}

/**
 * Iterates over Hue light state commands/states in Hue format (e.g., ["on": true]) and does
 * a sendEvent for each relevant attribute; intended to be called when commands are sent
 * to Bridge or to parse/update light states based on data received from Bridge
 * @param bridgeMap Map of light states that are or would be sent to bridge OR state as received from
 *  Bridge
 * @param isFromBridge Set to true if this is data read from Hue Bridge rather than intended to be sent
 *  to Bridge; TODO: check if this is still needed now that pseudo-prestaging removed
 */
void createEventsFromMapV1(Map bridgeCommandMap, Boolean isFromBridge = false, Set<String> keysToIgnoreIfSSEEnabledAndNotFromBridge=listKeysToIgnoreIfSSEEnabledAndNotFromBridge) {
   if (!bridgeCommandMap) {
      if (logEnable == true) log.debug "createEventsFromMapV1 called but map command empty or null; exiting"
      return
   }
   Map bridgeMap = bridgeCommandMap
   if (logEnable == true) log.debug "Preparing to create events from map${isFromBridge ? ' from Bridge' : ''}: ${bridgeMap}"
   if (!isFromBridge && keysToIgnoreIfSSEEnabledAndNotFromBridge && parent.getEventStreamOpenStatus() == true) {
      bridgeMap.keySet().removeAll(keysToIgnoreIfSSEEnabledAndNotFromBridge)
      if (logEnable == true) log.debug "Map after ignored keys removed: ${bridgeMap}"
   }
   String eventName, eventUnit, descriptionText
   def eventValue // could be String or number
   Boolean isOn = bridgeMap["on"]
   bridgeMap.each {
      switch (it.key) {
         case "on":
            eventName = "switch"
            eventValue = it.value ? "on" : "off"
            eventUnit = null
            if (device.currentValue(eventName) != eventValue) doSendEvent(eventName, eventValue, eventUnit)
            break
         case "bri":
            eventName = "level"
            eventValue = scaleBriFromBridge(it.value)
            eventUnit = "%"
            if (device.currentValue(eventName) != eventValue) {
               doSendEvent(eventName, eventValue, eventUnit)
            }
            break
         case "ct":
            eventName = "colorTemperature"
            eventValue = it.value == 0 ? 0 : scaleCTFromBridge(it.value)
            eventUnit = "K"
            if (device.currentValue(eventName) != eventValue && eventValue != 0) {
               doSendEvent(eventName, eventValue, eventUnit)
            }
            setGenericTempName(eventValue)
            break
         case "reachable":
            eventName = "reachable"
            eventValue = it.value ? "true" : "false"
            eventUnit = null
            if (device.currentValue(eventName) != eventValue) {
               doSendEvent(eventName, eventValue, eventUnit)
            }
            break
         case "transitiontime":
         case "mode":
         case "alert":
            break
         default:
            break
            //log.warn "Unhandled key/value discarded: $it"
      }
   }
}

/**
 * (for V2 API)
 * Iterates over Hue light state states in Hue API v2 format (e.g., "on={on=true}") and does
 * a sendEvent for each relevant attribute; intended to be called when EventSocket data
 * received for device (as an alternative to polling)
 */
void createEventsFromMapV2(Map data) {
   if (logEnable == true) log.debug "createEventsFromMapV2($data)"
   String eventName, eventUnit, descriptionText
   def eventValue // could be String or number
   Boolean hasCT = data.color_temperature?.mirek != null
   data.each { String key, value ->
      switch (key) {
         case "on":
            eventName = "switch"
            eventValue = value.on ? "on" : "off"
            eventUnit = null
            if (device.currentValue(eventName) != eventValue) doSendEvent(eventName, eventValue, eventUnit)
            break
         case "dimming":
            eventName = "level"
            eventValue = scaleBriFromBridge(value.brightness, "2")
            eventUnit = "%"
            if (device.currentValue(eventName) != eventValue && eventValue > 0) {
               doSendEvent(eventName, eventValue, eventUnit)
            }
            break
         case "color_temperature":
            if (!hasCT) {
               if (logEnable == true) "ignoring color_temperature because mirek null"
               return
            }
            eventName = "colorTemperature"
            eventValue = scaleCTFromBridge(value.mirek)
            eventUnit = "K"
            if (device.currentValue(eventName) != eventValue) doSendEvent(eventName, eventValue, eventUnit)
            setGenericTempName(eventValue)
            break
         case "id_v1":
            if (state.id_v1 != value) state.id_v1 = value
            break
         default:
            if (logEnable == true) "not handling: $key: $value"
      }
   }
}

/**
 * Sends HTTP PUT to Bridge using the either command map provided
 * @param commandMap Groovy Map (will be converted to JSON) of Hue API commands to send, e.g., [on: true]
 * @param createHubEvents Will iterate over Bridge command map and do sendEvent for all
 *        affected device attributes (e.g., will send an "on" event for "switch" if ["on": true] in map)
 */
void sendBridgeCommandV1(Map commandMap, Boolean createHubEvents=true) {
   if (logEnable == true) log.debug "sendBridgeCommandV1($commandMap)"
   if (commandMap == null || commandMap == [:]) {
      if (logEnable == true) log.debug "Commands not sent to Bridge because command map null or empty"
      return
   }
   Map<String,String> data = parent.getBridgeData()
   Map params = [
      uri: data.fullHost,
      path: "/api/${data.username}/lights/${getHueDeviceIdV1()}/state",
      contentType: 'application/json',
      body: commandMap,
      timeout: 15
   ]
   asynchttpPut("parseSendCommandResponseV1", params, createHubEvents ? commandMap : null)
   if (logEnable == true) log.debug "-- Command sent to Bridge! --"
}

/** 
  * Parses response from Bridge (or not) after sendBridgeCommandV1. Updates device state if
  * appears to have been successful.
  * @param resp Async HTTP response object
  * @param data Map of commands sent to Bridge if specified to create events from map
  */
void parseSendCommandResponseV1(AsyncResponse resp, Map data) {
   if (logEnable == true) log.debug "Response from Bridge: ${resp.status}"
   if (checkIfValidResponse(resp) && data) {
      if (logEnable == true) log.debug "  Bridge response valid; creating events from data map"
      createEventsFromMapV1(data)
      if ((data.containsKey("on") || data.containsKey("bri")) && settings["updateGroups"]) {
         parent.updateGroupStatesFromBulb(data, getHueDeviceIdV1())
      }
   }
   else {
      if (logEnable == true) log.debug "  Not creating events from map because not specified to do or Bridge response invalid"
   }
}
// ~~~~~ start include (8) RMoRobert.CoCoHue_Common_Lib ~~~~~
// Version 1.0.3 // library marker RMoRobert.CoCoHue_Common_Lib, line 1
// For use with CoCoHue drivers (not app) // library marker RMoRobert.CoCoHue_Common_Lib, line 2

/** // library marker RMoRobert.CoCoHue_Common_Lib, line 4
 * 1.0.4 - Add common bridgeAsyncGetV2() method (goal to reduce individual driver code) // library marker RMoRobert.CoCoHue_Common_Lib, line 5
 * 1.0.3 - Add APIV1 and APIV2 "constants" // library marker RMoRobert.CoCoHue_Common_Lib, line 6
 * 1.0.2  - HTTP error handling tweaks // library marker RMoRobert.CoCoHue_Common_Lib, line 7
 */ // library marker RMoRobert.CoCoHue_Common_Lib, line 8

library ( // library marker RMoRobert.CoCoHue_Common_Lib, line 10
   base: "driver", // library marker RMoRobert.CoCoHue_Common_Lib, line 11
   author: "RMoRobert", // library marker RMoRobert.CoCoHue_Common_Lib, line 12
   category: "Convenience", // library marker RMoRobert.CoCoHue_Common_Lib, line 13
   description: "For internal CoCoHue use only. Not intended for external use. Contains common code shared by many CoCoHue drivers.", // library marker RMoRobert.CoCoHue_Common_Lib, line 14
   name: "CoCoHue_Common_Lib", // library marker RMoRobert.CoCoHue_Common_Lib, line 15
   namespace: "RMoRobert" // library marker RMoRobert.CoCoHue_Common_Lib, line 16
) // library marker RMoRobert.CoCoHue_Common_Lib, line 17

void debugOff() { // library marker RMoRobert.CoCoHue_Common_Lib, line 19
   log.warn "Disabling debug logging" // library marker RMoRobert.CoCoHue_Common_Lib, line 20
   device.updateSetting("logEnable", [value:"false", type:"bool"]) // library marker RMoRobert.CoCoHue_Common_Lib, line 21
} // library marker RMoRobert.CoCoHue_Common_Lib, line 22

/** Performs basic check on data returned from HTTP response to determine if should be // library marker RMoRobert.CoCoHue_Common_Lib, line 24
  * parsed as likely Hue Bridge data or not; returns true (if OK) or logs errors/warnings and // library marker RMoRobert.CoCoHue_Common_Lib, line 25
  * returns false if not // library marker RMoRobert.CoCoHue_Common_Lib, line 26
  * @param resp The async HTTP response object to examine // library marker RMoRobert.CoCoHue_Common_Lib, line 27
  */ // library marker RMoRobert.CoCoHue_Common_Lib, line 28
private Boolean checkIfValidResponse(hubitat.scheduling.AsyncResponse resp) { // library marker RMoRobert.CoCoHue_Common_Lib, line 29
   if (logEnable == true) log.debug "Checking if valid HTTP response/data from Bridge..." // library marker RMoRobert.CoCoHue_Common_Lib, line 30
   Boolean isOK = true // library marker RMoRobert.CoCoHue_Common_Lib, line 31
   if (resp.status < 400) { // library marker RMoRobert.CoCoHue_Common_Lib, line 32
      if (resp.json == null) { // library marker RMoRobert.CoCoHue_Common_Lib, line 33
         isOK = false // library marker RMoRobert.CoCoHue_Common_Lib, line 34
         if (resp.headers == null) log.error "Error: HTTP ${resp.status} when attempting to communicate with Bridge" // library marker RMoRobert.CoCoHue_Common_Lib, line 35
         else log.error "No JSON data found in response. ${resp.headers.'Content-Type'} (HTTP ${resp.status})" // library marker RMoRobert.CoCoHue_Common_Lib, line 36
         parent.sendBridgeDiscoveryCommandIfSSDPEnabled(true) // maybe IP changed, so attempt rediscovery  // library marker RMoRobert.CoCoHue_Common_Lib, line 37
         parent.setBridgeOnlineStatus(false) // library marker RMoRobert.CoCoHue_Common_Lib, line 38
      } // library marker RMoRobert.CoCoHue_Common_Lib, line 39
      else if (resp.json) { // library marker RMoRobert.CoCoHue_Common_Lib, line 40
         if ((resp.json instanceof List) && resp.json.getAt(0).error) { // library marker RMoRobert.CoCoHue_Common_Lib, line 41
            // Bridge (not HTTP) error (bad username, bad command formatting, etc.): // library marker RMoRobert.CoCoHue_Common_Lib, line 42
            isOK = false // library marker RMoRobert.CoCoHue_Common_Lib, line 43
            log.warn "Error from Hue Bridge: ${resp.json[0].error}" // library marker RMoRobert.CoCoHue_Common_Lib, line 44
            // Not setting Bridge to offline when light/scene/group devices end up here because could // library marker RMoRobert.CoCoHue_Common_Lib, line 45
            // be old/bad ID and don't want to consider Bridge offline just for that (but also won't set // library marker RMoRobert.CoCoHue_Common_Lib, line 46
            // to online because wasn't successful attempt) // library marker RMoRobert.CoCoHue_Common_Lib, line 47
         } // library marker RMoRobert.CoCoHue_Common_Lib, line 48
         // Otherwise: probably OK (not changing anything because isOK = true already) // library marker RMoRobert.CoCoHue_Common_Lib, line 49
      } // library marker RMoRobert.CoCoHue_Common_Lib, line 50
      else { // library marker RMoRobert.CoCoHue_Common_Lib, line 51
         isOK = false // library marker RMoRobert.CoCoHue_Common_Lib, line 52
         log.warn("HTTP status code ${resp.status} from Bridge") // library marker RMoRobert.CoCoHue_Common_Lib, line 53
         // TODO: Update for mDNS if/when switch: // library marker RMoRobert.CoCoHue_Common_Lib, line 54
         if (resp?.status >= 400) parent.sendBridgeDiscoveryCommandIfSSDPEnabled(true) // maybe IP changed, so attempt rediscovery  // library marker RMoRobert.CoCoHue_Common_Lib, line 55
         parent.setBridgeOnlineStatus(false) // library marker RMoRobert.CoCoHue_Common_Lib, line 56
      } // library marker RMoRobert.CoCoHue_Common_Lib, line 57
      if (isOK == true) parent.setBridgeOnlineStatus(true) // library marker RMoRobert.CoCoHue_Common_Lib, line 58
   } // library marker RMoRobert.CoCoHue_Common_Lib, line 59
   else { // library marker RMoRobert.CoCoHue_Common_Lib, line 60
      log.warn "Error communicating with Hue Bridge: HTTP ${resp?.status}" // library marker RMoRobert.CoCoHue_Common_Lib, line 61
      isOK = false // library marker RMoRobert.CoCoHue_Common_Lib, line 62
   } // library marker RMoRobert.CoCoHue_Common_Lib, line 63
   return isOK // library marker RMoRobert.CoCoHue_Common_Lib, line 64
} // library marker RMoRobert.CoCoHue_Common_Lib, line 65

void doSendEvent(String eventName, eventValue, String eventUnit=null, Boolean forceStateChange=false) { // library marker RMoRobert.CoCoHue_Common_Lib, line 67
   //if (logEnable == true) log.debug "doSendEvent($eventName, $eventValue, $eventUnit)" // library marker RMoRobert.CoCoHue_Common_Lib, line 68
   String descriptionText = "${device.displayName} ${eventName} is ${eventValue}${eventUnit ?: ''}" // library marker RMoRobert.CoCoHue_Common_Lib, line 69
   if (settings.txtEnable == true) log.info(descriptionText) // library marker RMoRobert.CoCoHue_Common_Lib, line 70
   if (eventUnit) { // library marker RMoRobert.CoCoHue_Common_Lib, line 71
      if (forceStateChange == true) sendEvent(name: eventName, value: eventValue, descriptionText: descriptionText, unit: eventUnit, isStateChange: true)  // library marker RMoRobert.CoCoHue_Common_Lib, line 72
      else sendEvent(name: eventName, value: eventValue, descriptionText: descriptionText, unit: eventUnit)  // library marker RMoRobert.CoCoHue_Common_Lib, line 73
   } else { // library marker RMoRobert.CoCoHue_Common_Lib, line 74
      if (forceStateChange == true) sendEvent(name: eventName, value: eventValue, descriptionText: descriptionText, isStateChange: true)  // library marker RMoRobert.CoCoHue_Common_Lib, line 75
      else sendEvent(name: eventName, value: eventValue, descriptionText: descriptionText)  // library marker RMoRobert.CoCoHue_Common_Lib, line 76
   } // library marker RMoRobert.CoCoHue_Common_Lib, line 77
} // library marker RMoRobert.CoCoHue_Common_Lib, line 78

// HTTP methods (might be better to split into separate library if not needed for some?) // library marker RMoRobert.CoCoHue_Common_Lib, line 80

/** Performs asynchttpGet() to Bridge using data retrieved from parent app or as passed in // library marker RMoRobert.CoCoHue_Common_Lib, line 82
  * @param callbackMethod Callback method // library marker RMoRobert.CoCoHue_Common_Lib, line 83
  * @param clipV2Path The Hue V2 API path (without '/clip/v2', automatically prepended), e.g. '/resource' or '/resource/light' // library marker RMoRobert.CoCoHue_Common_Lib, line 84
  * @param bridgeData Bridge data from parent getBridgeData() call, or will call this method on parent if null // library marker RMoRobert.CoCoHue_Common_Lib, line 85
  * @param data Extra data to pass as optional third (data) parameter to asynchtttpGet() method // library marker RMoRobert.CoCoHue_Common_Lib, line 86
  */ // library marker RMoRobert.CoCoHue_Common_Lib, line 87
void bridgeAsyncGetV2(String callbackMethod, String clipV2Path, Map<String,String> bridgeData = null, Map data = null) { // library marker RMoRobert.CoCoHue_Common_Lib, line 88
   if (bridgeData == null) { // library marker RMoRobert.CoCoHue_Common_Lib, line 89
      bridgeData = parent.getBridgeData() // library marker RMoRobert.CoCoHue_Common_Lib, line 90
   } // library marker RMoRobert.CoCoHue_Common_Lib, line 91
   Map params = [ // library marker RMoRobert.CoCoHue_Common_Lib, line 92
      uri: "https://${bridgeData.ip}", // library marker RMoRobert.CoCoHue_Common_Lib, line 93
      path: "/clip/v2${clipV2Path}", // library marker RMoRobert.CoCoHue_Common_Lib, line 94
      headers: ["hue-application-key": bridgeData.username], // library marker RMoRobert.CoCoHue_Common_Lib, line 95
      contentType: "application/json", // library marker RMoRobert.CoCoHue_Common_Lib, line 96
      timeout: 15, // library marker RMoRobert.CoCoHue_Common_Lib, line 97
      ignoreSSLIssues: true // library marker RMoRobert.CoCoHue_Common_Lib, line 98
   ] // library marker RMoRobert.CoCoHue_Common_Lib, line 99
   asynchttpGet(callbackMethod, params, data) // library marker RMoRobert.CoCoHue_Common_Lib, line 100
} // library marker RMoRobert.CoCoHue_Common_Lib, line 101


// ~~~~~ end include (8) RMoRobert.CoCoHue_Common_Lib ~~~~~

// ~~~~~ start include (73) RMoRobert.CoCoHue_Constants_Lib ~~~~~
// Version 1.0.0 // library marker RMoRobert.CoCoHue_Constants_Lib, line 1

library ( // library marker RMoRobert.CoCoHue_Constants_Lib, line 3
   author: "RMoRobert", // library marker RMoRobert.CoCoHue_Constants_Lib, line 4
   category: "Convenience", // library marker RMoRobert.CoCoHue_Constants_Lib, line 5
   description: "For internal CoCoHue use only. Not intended for external use. Contains field variables shared by many CoCoHue apps and drivers.", // library marker RMoRobert.CoCoHue_Constants_Lib, line 6
   name: "CoCoHue_Constants_Lib", // library marker RMoRobert.CoCoHue_Constants_Lib, line 7
   namespace: "RMoRobert"  // library marker RMoRobert.CoCoHue_Constants_Lib, line 8
) // library marker RMoRobert.CoCoHue_Constants_Lib, line 9

// -------------------------------------- // library marker RMoRobert.CoCoHue_Constants_Lib, line 11
// APP AND DRIVER NAMESPACE AND NAMES: // library marker RMoRobert.CoCoHue_Constants_Lib, line 12
// -------------------------------------- // library marker RMoRobert.CoCoHue_Constants_Lib, line 13

// -- CoCoHue -- // library marker RMoRobert.CoCoHue_Constants_Lib, line 15
@Field static final String NAMESPACE = "RMoRobert" // library marker RMoRobert.CoCoHue_Constants_Lib, line 16
@Field static final String APP_NAME = "CoCoHue - Hue Bridge Integration" // library marker RMoRobert.CoCoHue_Constants_Lib, line 17


// -- CoCoHue -- // library marker RMoRobert.CoCoHue_Constants_Lib, line 20
@Field static final String DRIVER_NAME_BRIDGE = "CoCoHue Bridge" // library marker RMoRobert.CoCoHue_Constants_Lib, line 21
@Field static final String DRIVER_NAME_BUTTON = "CoCoHue Button" // library marker RMoRobert.CoCoHue_Constants_Lib, line 22
@Field static final String DRIVER_NAME_CT_BULB = "CoCoHue CT Bulb" // library marker RMoRobert.CoCoHue_Constants_Lib, line 23
@Field static final String DRIVER_NAME_DIMMABLE_BULB = "CoCoHue Dimmable Bulb" // library marker RMoRobert.CoCoHue_Constants_Lib, line 24
@Field static final String DRIVER_NAME_GROUP = "CoCoHue Group" // library marker RMoRobert.CoCoHue_Constants_Lib, line 25
@Field static final String DRIVER_NAME_MOTION = "CoCoHue Motion Sensor" // library marker RMoRobert.CoCoHue_Constants_Lib, line 26
@Field static final String DRIVER_NAME_PLUG = "CoCoHue Plug" // library marker RMoRobert.CoCoHue_Constants_Lib, line 27
@Field static final String DRIVER_NAME_RGBW_BULB = "CoCoHue RGBW Bulb" // library marker RMoRobert.CoCoHue_Constants_Lib, line 28
@Field static final String DRIVER_NAME_SCENE = "CoCoHue Scene" // library marker RMoRobert.CoCoHue_Constants_Lib, line 29

// -------------------------------------- // library marker RMoRobert.CoCoHue_Constants_Lib, line 31
// DNI PREFIX for child devices: // library marker RMoRobert.CoCoHue_Constants_Lib, line 32
// -------------------------------------- // library marker RMoRobert.CoCoHue_Constants_Lib, line 33

@Field static final String DNI_PREFIX = "CCH"   // "CCH" for CoCoHue // library marker RMoRobert.CoCoHue_Constants_Lib, line 35

// -------------------------------------- // library marker RMoRobert.CoCoHue_Constants_Lib, line 37
// OTHER: // library marker RMoRobert.CoCoHue_Constants_Lib, line 38
// -------------------------------------- // library marker RMoRobert.CoCoHue_Constants_Lib, line 39

// Used in app and Bridge driver, may eventually find use in more: // library marker RMoRobert.CoCoHue_Constants_Lib, line 41

@Field static final String APIV1 = "V1" // library marker RMoRobert.CoCoHue_Constants_Lib, line 43
@Field static final String APIV2 = "V2" // library marker RMoRobert.CoCoHue_Constants_Lib, line 44

// ~~~~~ end include (73) RMoRobert.CoCoHue_Constants_Lib ~~~~~

// ~~~~~ start include (2) RMoRobert.CoCoHue_Bri_Lib ~~~~~
// Version 1.0.4 // library marker RMoRobert.CoCoHue_Bri_Lib, line 1

// 1.0.4  - accept String for setLevel() level also  // library marker RMoRobert.CoCoHue_Bri_Lib, line 3
// 1.0.3  - levelhandling tweaks // library marker RMoRobert.CoCoHue_Bri_Lib, line 4

library ( // library marker RMoRobert.CoCoHue_Bri_Lib, line 6
   base: "driver", // library marker RMoRobert.CoCoHue_Bri_Lib, line 7
   author: "RMoRobert", // library marker RMoRobert.CoCoHue_Bri_Lib, line 8
   category: "Convenience", // library marker RMoRobert.CoCoHue_Bri_Lib, line 9
   description: "For internal CoCoHue use only. Not intended for external use. Contains brightness/level-related code shared by many CoCoHue drivers.", // library marker RMoRobert.CoCoHue_Bri_Lib, line 10
   name: "CoCoHue_Bri_Lib", // library marker RMoRobert.CoCoHue_Bri_Lib, line 11
   namespace: "RMoRobert" // library marker RMoRobert.CoCoHue_Bri_Lib, line 12
) // library marker RMoRobert.CoCoHue_Bri_Lib, line 13

// "SwitchLevel" commands: // library marker RMoRobert.CoCoHue_Bri_Lib, line 15

void startLevelChange(String direction) { // library marker RMoRobert.CoCoHue_Bri_Lib, line 17
   if (logEnable == true) log.debug "startLevelChange($direction)..." // library marker RMoRobert.CoCoHue_Bri_Lib, line 18
   Map cmd = ["bri": (direction == "up" ? 254 : 1), // library marker RMoRobert.CoCoHue_Bri_Lib, line 19
            "transitiontime": ((settings["levelChangeRate"] == "fast" || !settings["levelChangeRate"]) ? // library marker RMoRobert.CoCoHue_Bri_Lib, line 20
                                 30 : (settings["levelChangeRate"] == "slow" ? 60 : 45))] // library marker RMoRobert.CoCoHue_Bri_Lib, line 21
   sendBridgeCommandV1(cmd, false)  // library marker RMoRobert.CoCoHue_Bri_Lib, line 22
} // library marker RMoRobert.CoCoHue_Bri_Lib, line 23

void stopLevelChange() { // library marker RMoRobert.CoCoHue_Bri_Lib, line 25
   if (logEnable == true) log.debug "stopLevelChange()..." // library marker RMoRobert.CoCoHue_Bri_Lib, line 26
   Map cmd = ["bri_inc": 0] // library marker RMoRobert.CoCoHue_Bri_Lib, line 27
   sendBridgeCommandV1(cmd, false)  // library marker RMoRobert.CoCoHue_Bri_Lib, line 28
} // library marker RMoRobert.CoCoHue_Bri_Lib, line 29

void setLevel(value) { // library marker RMoRobert.CoCoHue_Bri_Lib, line 31
   if (logEnable == true) log.debug "setLevel($value)" // library marker RMoRobert.CoCoHue_Bri_Lib, line 32
   setLevel(value, ((transitionTime != null ? transitionTime.toFloat() : defaultLevelTransitionTime.toFloat())) / 1000) // library marker RMoRobert.CoCoHue_Bri_Lib, line 33
} // library marker RMoRobert.CoCoHue_Bri_Lib, line 34

void setLevel(Number value, Number rate) { // library marker RMoRobert.CoCoHue_Bri_Lib, line 36
   if (logEnable == true) log.debug "setLevel($value, $rate)" // library marker RMoRobert.CoCoHue_Bri_Lib, line 37
   // For backwards compatibility; will be removed in future version: // library marker RMoRobert.CoCoHue_Bri_Lib, line 38
   if (levelStaging) { // library marker RMoRobert.CoCoHue_Bri_Lib, line 39
      log.warn "Level prestaging preference enabled and setLevel() called. This is deprecated and may be removed in the future. Please move to new, standard presetLevel() command." // library marker RMoRobert.CoCoHue_Bri_Lib, line 40
      if (device.currentValue("switch") != "on") { // library marker RMoRobert.CoCoHue_Bri_Lib, line 41
         presetLevel(value) // library marker RMoRobert.CoCoHue_Bri_Lib, line 42
         return // library marker RMoRobert.CoCoHue_Bri_Lib, line 43
      } // library marker RMoRobert.CoCoHue_Bri_Lib, line 44
   } // library marker RMoRobert.CoCoHue_Bri_Lib, line 45
   if (value < 0) value = 1 // library marker RMoRobert.CoCoHue_Bri_Lib, line 46
   else if (value > 100) value = 100 // library marker RMoRobert.CoCoHue_Bri_Lib, line 47
   else if (value == 0) { // library marker RMoRobert.CoCoHue_Bri_Lib, line 48
      off(rate) // library marker RMoRobert.CoCoHue_Bri_Lib, line 49
      return // library marker RMoRobert.CoCoHue_Bri_Lib, line 50
   } // library marker RMoRobert.CoCoHue_Bri_Lib, line 51
   Integer newLevel = scaleBriToBridge(value) // library marker RMoRobert.CoCoHue_Bri_Lib, line 52
   Integer scaledRate = (rate * 10).toInteger() // library marker RMoRobert.CoCoHue_Bri_Lib, line 53
   Map bridgeCmd = [ // library marker RMoRobert.CoCoHue_Bri_Lib, line 54
      "on": true, // library marker RMoRobert.CoCoHue_Bri_Lib, line 55
      "bri": newLevel, // library marker RMoRobert.CoCoHue_Bri_Lib, line 56
      "transitiontime": scaledRate // library marker RMoRobert.CoCoHue_Bri_Lib, line 57
   ] // library marker RMoRobert.CoCoHue_Bri_Lib, line 58
   sendBridgeCommandV1(bridgeCmd) // library marker RMoRobert.CoCoHue_Bri_Lib, line 59
} // library marker RMoRobert.CoCoHue_Bri_Lib, line 60

void setLevel(value, rate) { // library marker RMoRobert.CoCoHue_Bri_Lib, line 62
   if (logEnable == true) log.debug "setLevel(Object $value, Object $rate)" // library marker RMoRobert.CoCoHue_Bri_Lib, line 63
   Float floatLevel = Float.parseFloat(value.toString()) // library marker RMoRobert.CoCoHue_Bri_Lib, line 64
   Integer intLevel = Math.round(floatLevel) // library marker RMoRobert.CoCoHue_Bri_Lib, line 65
   Float floatRate = Float.parseFloat(rate.toString()) // library marker RMoRobert.CoCoHue_Bri_Lib, line 66
   setLevel(intLevel, floatRate) // library marker RMoRobert.CoCoHue_Bri_Lib, line 67
} // library marker RMoRobert.CoCoHue_Bri_Lib, line 68

void presetLevel(Number level) { // library marker RMoRobert.CoCoHue_Bri_Lib, line 70
   if (logEnable == true) log.debug "presetLevel($level)" // library marker RMoRobert.CoCoHue_Bri_Lib, line 71
   if (level < 0) level = 1 // library marker RMoRobert.CoCoHue_Bri_Lib, line 72
   else if (level > 100) level = 100 // library marker RMoRobert.CoCoHue_Bri_Lib, line 73
   Integer newLevel = scaleBriToBridge(level) // library marker RMoRobert.CoCoHue_Bri_Lib, line 74
   Integer scaledRate = ((transitionTime != null ? transitionTime.toBigDecimal() : 1000) / 1000).toInteger() // library marker RMoRobert.CoCoHue_Bri_Lib, line 75
   Boolean isOn = device.currentValue("switch") == "on" // library marker RMoRobert.CoCoHue_Bri_Lib, line 76
   doSendEvent("levelPreset", level) // library marker RMoRobert.CoCoHue_Bri_Lib, line 77
   if (isOn) { // library marker RMoRobert.CoCoHue_Bri_Lib, line 78
      setLevel(level) // library marker RMoRobert.CoCoHue_Bri_Lib, line 79
   } else { // library marker RMoRobert.CoCoHue_Bri_Lib, line 80
      state.presetLevel = true // library marker RMoRobert.CoCoHue_Bri_Lib, line 81
   } // library marker RMoRobert.CoCoHue_Bri_Lib, line 82
} // library marker RMoRobert.CoCoHue_Bri_Lib, line 83

/** // library marker RMoRobert.CoCoHue_Bri_Lib, line 85
 * Reads device preference for on() transition time, or provides default if not available; device // library marker RMoRobert.CoCoHue_Bri_Lib, line 86
 * can use input(name: onTransitionTime, ...) to provide this // library marker RMoRobert.CoCoHue_Bri_Lib, line 87
 */ // library marker RMoRobert.CoCoHue_Bri_Lib, line 88
Integer getScaledOnTransitionTime() { // library marker RMoRobert.CoCoHue_Bri_Lib, line 89
   Integer scaledRate = null // library marker RMoRobert.CoCoHue_Bri_Lib, line 90
   if (settings.onTransitionTime == null || settings.onTransitionTime == "-2" || settings.onTransitionTime == -2) { // library marker RMoRobert.CoCoHue_Bri_Lib, line 91
      // keep null; will result in not specifiying with command // library marker RMoRobert.CoCoHue_Bri_Lib, line 92
   } // library marker RMoRobert.CoCoHue_Bri_Lib, line 93
   else { // library marker RMoRobert.CoCoHue_Bri_Lib, line 94
      scaledRate = Math.round(settings.onTransitionTime.toFloat() / 100) // library marker RMoRobert.CoCoHue_Bri_Lib, line 95
   } // library marker RMoRobert.CoCoHue_Bri_Lib, line 96
   return scaledRate // library marker RMoRobert.CoCoHue_Bri_Lib, line 97
} // library marker RMoRobert.CoCoHue_Bri_Lib, line 98


/** // library marker RMoRobert.CoCoHue_Bri_Lib, line 101
 * Reads device preference for off() transition time, or provides default if not available; device // library marker RMoRobert.CoCoHue_Bri_Lib, line 102
 * can use input(name: onTransitionTime, ...) to provide this // library marker RMoRobert.CoCoHue_Bri_Lib, line 103
 */ // library marker RMoRobert.CoCoHue_Bri_Lib, line 104
Integer getScaledOffTransitionTime() { // library marker RMoRobert.CoCoHue_Bri_Lib, line 105
   Integer scaledRate = null // library marker RMoRobert.CoCoHue_Bri_Lib, line 106
   if (settings.offTransitionTime == null || settings.offTransitionTime == "-2" || settings.offTransitionTime == -2) { // library marker RMoRobert.CoCoHue_Bri_Lib, line 107
      // keep null; will result in not specifiying with command // library marker RMoRobert.CoCoHue_Bri_Lib, line 108
   } // library marker RMoRobert.CoCoHue_Bri_Lib, line 109
   else if (settings.offTransitionTime == "-1" || settings.offTransitionTime == -1) { // library marker RMoRobert.CoCoHue_Bri_Lib, line 110
      scaledRate = getScaledOnTransitionTime() // library marker RMoRobert.CoCoHue_Bri_Lib, line 111
   } // library marker RMoRobert.CoCoHue_Bri_Lib, line 112
   else { // library marker RMoRobert.CoCoHue_Bri_Lib, line 113
      scaledRate = Math.round(settings.offTransitionTime.toFloat() / 100) // library marker RMoRobert.CoCoHue_Bri_Lib, line 114
   } // library marker RMoRobert.CoCoHue_Bri_Lib, line 115
   return scaledRate // library marker RMoRobert.CoCoHue_Bri_Lib, line 116
} // library marker RMoRobert.CoCoHue_Bri_Lib, line 117

// Internal methods for scaling // library marker RMoRobert.CoCoHue_Bri_Lib, line 119


/** // library marker RMoRobert.CoCoHue_Bri_Lib, line 122
 * Scales Hubitat's 1-100 brightness levels to Hue Bridge's 1-254 (or 0-100) // library marker RMoRobert.CoCoHue_Bri_Lib, line 123
 * @param apiVersion: Use "1" (default) for classic, 1-254 API values; use "2" for v2/SSE 0.0-100.0 values (note: 0.0 is on) // library marker RMoRobert.CoCoHue_Bri_Lib, line 124
 */ // library marker RMoRobert.CoCoHue_Bri_Lib, line 125
Number scaleBriToBridge(Number hubitatLevel, String apiVersion="1") { // library marker RMoRobert.CoCoHue_Bri_Lib, line 126
   if (apiVersion != "2") { // library marker RMoRobert.CoCoHue_Bri_Lib, line 127
      Integer scaledLevel // library marker RMoRobert.CoCoHue_Bri_Lib, line 128
      scaledLevel = Math.round(hubitatLevel == 1 ? 1 : hubitatLevel.toBigDecimal() / 100 * 254) // library marker RMoRobert.CoCoHue_Bri_Lib, line 129
      return Math.round(scaledLevel) as Integer // library marker RMoRobert.CoCoHue_Bri_Lib, line 130
   } // library marker RMoRobert.CoCoHue_Bri_Lib, line 131
   else { // library marker RMoRobert.CoCoHue_Bri_Lib, line 132
      BigDecimal scaledLevel // library marker RMoRobert.CoCoHue_Bri_Lib, line 133
      // for now, a quick cheat to make 1% the Hue minimum (should scale other values proportionally in future) // library marker RMoRobert.CoCoHue_Bri_Lib, line 134
      scaledLevel = hubitatLevel == 1 ? 0.0 : hubitatLevel.toBigDecimal().setScale(2, java.math.RoundingMode.HALF_UP) // library marker RMoRobert.CoCoHue_Bri_Lib, line 135
      return scaledLevel // library marker RMoRobert.CoCoHue_Bri_Lib, line 136
   } // library marker RMoRobert.CoCoHue_Bri_Lib, line 137
} // library marker RMoRobert.CoCoHue_Bri_Lib, line 138

/** // library marker RMoRobert.CoCoHue_Bri_Lib, line 140
 * Scales Hue Bridge's 1-254 brightness levels to Hubitat's 1-100 (or 0-100) // library marker RMoRobert.CoCoHue_Bri_Lib, line 141
 * @param apiVersion: Use "1" (default) for classic, 1-254 API values; use "2" for v2/SSE 0.0-100.0 values (note: 0.0 is on) // library marker RMoRobert.CoCoHue_Bri_Lib, line 142
 */ // library marker RMoRobert.CoCoHue_Bri_Lib, line 143
Integer scaleBriFromBridge(Number bridgeLevel, String apiVersion="1") { // library marker RMoRobert.CoCoHue_Bri_Lib, line 144
   Integer scaledLevel // library marker RMoRobert.CoCoHue_Bri_Lib, line 145
   if (apiVersion != "2") { // library marker RMoRobert.CoCoHue_Bri_Lib, line 146
      scaledLevel = Math.round(bridgeLevel.toBigDecimal() / 254 * 100) // library marker RMoRobert.CoCoHue_Bri_Lib, line 147
      if (scaledLevel < 1) scaledLevel = 1 // library marker RMoRobert.CoCoHue_Bri_Lib, line 148
   } // library marker RMoRobert.CoCoHue_Bri_Lib, line 149
   else { // library marker RMoRobert.CoCoHue_Bri_Lib, line 150
      // for now, a quick cheat to make 1% the Hue minimum (should scale other values proportionally in future) // library marker RMoRobert.CoCoHue_Bri_Lib, line 151
      scaledLevel = Math.round(bridgeLevel <= 1.49 && bridgeLevel > 0.001 ? 1 : bridgeLevel) // library marker RMoRobert.CoCoHue_Bri_Lib, line 152
   } // library marker RMoRobert.CoCoHue_Bri_Lib, line 153
   return scaledLevel // library marker RMoRobert.CoCoHue_Bri_Lib, line 154
} // library marker RMoRobert.CoCoHue_Bri_Lib, line 155

// ~~~~~ end include (2) RMoRobert.CoCoHue_Bri_Lib ~~~~~

// ~~~~~ start include (3) RMoRobert.CoCoHue_CT_Lib ~~~~~
// Version 1.0.1 // library marker RMoRobert.CoCoHue_CT_Lib, line 1

library ( // library marker RMoRobert.CoCoHue_CT_Lib, line 3
   base: "driver", // library marker RMoRobert.CoCoHue_CT_Lib, line 4
   author: "RMoRobert", // library marker RMoRobert.CoCoHue_CT_Lib, line 5
   category: "Convenience", // library marker RMoRobert.CoCoHue_CT_Lib, line 6
   description: "For internal CoCoHue use only. Not intended for external use. Contains CT-related code shared by many CoCoHue drivers.", // library marker RMoRobert.CoCoHue_CT_Lib, line 7
    name: "CoCoHue_CT_Lib", // library marker RMoRobert.CoCoHue_CT_Lib, line 8
   namespace: "RMoRobert" // library marker RMoRobert.CoCoHue_CT_Lib, line 9
) // library marker RMoRobert.CoCoHue_CT_Lib, line 10

void setColorTemperature(Number colorTemperature, Number level = null, Number transitionTime = null) { // library marker RMoRobert.CoCoHue_CT_Lib, line 12
   if (logEnable == true) log.debug "setColorTemperature($colorTemperature, $level, $transitionTime)" // library marker RMoRobert.CoCoHue_CT_Lib, line 13
   state.lastKnownColorMode = "CT" // library marker RMoRobert.CoCoHue_CT_Lib, line 14
   // For backwards compatibility; will be removed in future version: // library marker RMoRobert.CoCoHue_CT_Lib, line 15
   if (colorStaging) { // library marker RMoRobert.CoCoHue_CT_Lib, line 16
      log.warn "Color prestaging preference enabled and setColorTemperature() called. This is deprecated and may be removed in the future. Please move to new presetColorTemperature() command." // library marker RMoRobert.CoCoHue_CT_Lib, line 17
      if (device.currentValue("switch") != "on") { // library marker RMoRobert.CoCoHue_CT_Lib, line 18
         presetColorTemperature(colorTemperature) // library marker RMoRobert.CoCoHue_CT_Lib, line 19
         return // library marker RMoRobert.CoCoHue_CT_Lib, line 20
      } // library marker RMoRobert.CoCoHue_CT_Lib, line 21
   } // library marker RMoRobert.CoCoHue_CT_Lib, line 22
   Integer newCT = scaleCTToBridge(colorTemperature) // library marker RMoRobert.CoCoHue_CT_Lib, line 23
   Integer scaledRate = defaultLevelTransitionTime/100 // library marker RMoRobert.CoCoHue_CT_Lib, line 24
   if (transitionTime != null) { // library marker RMoRobert.CoCoHue_CT_Lib, line 25
      scaledRate = (transitionTime * 10) as Integer // library marker RMoRobert.CoCoHue_CT_Lib, line 26
   } // library marker RMoRobert.CoCoHue_CT_Lib, line 27
   else if (settings["transitionTime"] != null) { // library marker RMoRobert.CoCoHue_CT_Lib, line 28
      scaledRate = ((settings["transitionTime"] as Integer) / 100) as Integer // library marker RMoRobert.CoCoHue_CT_Lib, line 29
   } // library marker RMoRobert.CoCoHue_CT_Lib, line 30
   Map bridgeCmd = ["on": true, "ct": newCT, "transitiontime": scaledRate] // library marker RMoRobert.CoCoHue_CT_Lib, line 31
   if (level) { // library marker RMoRobert.CoCoHue_CT_Lib, line 32
      bridgeCmd << ["bri": scaleBriToBridge(level)] // library marker RMoRobert.CoCoHue_CT_Lib, line 33
   } // library marker RMoRobert.CoCoHue_CT_Lib, line 34
   sendBridgeCommandV1(bridgeCmd) // library marker RMoRobert.CoCoHue_CT_Lib, line 35
} // library marker RMoRobert.CoCoHue_CT_Lib, line 36

// Not a standard command (yet?), but I hope it will get implemented as such soon in // library marker RMoRobert.CoCoHue_CT_Lib, line 38
// the same manner as this. Otherwise, subject to change if/when that happens.... // library marker RMoRobert.CoCoHue_CT_Lib, line 39
void presetColorTemperature(Number colorTemperature) { // library marker RMoRobert.CoCoHue_CT_Lib, line 40
   if (logEnable == true) log.debug "presetColorTemperature($colorTemperature)" // library marker RMoRobert.CoCoHue_CT_Lib, line 41
   Boolean isOn = device.currentValue("switch") == "on" // library marker RMoRobert.CoCoHue_CT_Lib, line 42
   doSendEvent("colorTemperaturePreset", colorTemperature) // library marker RMoRobert.CoCoHue_CT_Lib, line 43
   if (isOn) { // library marker RMoRobert.CoCoHue_CT_Lib, line 44
      setColorTemperature(colorTemperature) // library marker RMoRobert.CoCoHue_CT_Lib, line 45
   } else { // library marker RMoRobert.CoCoHue_CT_Lib, line 46
      state.remove("presetCT") // library marker RMoRobert.CoCoHue_CT_Lib, line 47
      state.presetColorTemperature = true // library marker RMoRobert.CoCoHue_CT_Lib, line 48
      state.presetHue = false // library marker RMoRobert.CoCoHue_CT_Lib, line 49
      state.presetSaturation = false // library marker RMoRobert.CoCoHue_CT_Lib, line 50
   } // library marker RMoRobert.CoCoHue_CT_Lib, line 51
} // library marker RMoRobert.CoCoHue_CT_Lib, line 52

/** // library marker RMoRobert.CoCoHue_CT_Lib, line 54
 * Scales CT from Kelvin (Hubitat units) to mireds (Hue units) // library marker RMoRobert.CoCoHue_CT_Lib, line 55
 */ // library marker RMoRobert.CoCoHue_CT_Lib, line 56
private Integer scaleCTToBridge(Number kelvinCT, Boolean checkIfInRange=true) { // library marker RMoRobert.CoCoHue_CT_Lib, line 57
   Integer mireds = Math.round(1000000/kelvinCT) as Integer // library marker RMoRobert.CoCoHue_CT_Lib, line 58
   if (checkIfInRange == true) { // library marker RMoRobert.CoCoHue_CT_Lib, line 59
      if (mireds < minMireds) mireds = minMireds // library marker RMoRobert.CoCoHue_CT_Lib, line 60
      else if (mireds > maxMireds) mireds = maxMireds // library marker RMoRobert.CoCoHue_CT_Lib, line 61
   } // library marker RMoRobert.CoCoHue_CT_Lib, line 62
   return mireds // library marker RMoRobert.CoCoHue_CT_Lib, line 63
} // library marker RMoRobert.CoCoHue_CT_Lib, line 64

/** // library marker RMoRobert.CoCoHue_CT_Lib, line 66
 * Scales CT from mireds (Hue units) to Kelvin (Hubitat units) // library marker RMoRobert.CoCoHue_CT_Lib, line 67
 */ // library marker RMoRobert.CoCoHue_CT_Lib, line 68
private Integer scaleCTFromBridge(Number mireds) { // library marker RMoRobert.CoCoHue_CT_Lib, line 69
   Integer kelvin = Math.round(1000000/mireds) as Integer // library marker RMoRobert.CoCoHue_CT_Lib, line 70
   return kelvin // library marker RMoRobert.CoCoHue_CT_Lib, line 71
} // library marker RMoRobert.CoCoHue_CT_Lib, line 72

/** // library marker RMoRobert.CoCoHue_CT_Lib, line 74
 * Reads device preference for CT transition time, or provides default if not available; device // library marker RMoRobert.CoCoHue_CT_Lib, line 75
 * can use input(name: ctTransitionTime, ...) to provide this // library marker RMoRobert.CoCoHue_CT_Lib, line 76
 */ // library marker RMoRobert.CoCoHue_CT_Lib, line 77
Integer getScaledCTTransitionTime() { // library marker RMoRobert.CoCoHue_CT_Lib, line 78
   Integer scaledRate = null // library marker RMoRobert.CoCoHue_CT_Lib, line 79
   if (settings.ctTransitionTime == null || settings.ctTransitionTime == "-2" || settings.ctTransitionTime == -2) { // library marker RMoRobert.CoCoHue_CT_Lib, line 80
      // keep null; will result in not specifiying with command // library marker RMoRobert.CoCoHue_CT_Lib, line 81
   } // library marker RMoRobert.CoCoHue_CT_Lib, line 82
   else if (settings.ctTransitionTime == "-1" || settings.ctTransitionTime == -1) { // library marker RMoRobert.CoCoHue_CT_Lib, line 83
      scaledRate = (settings.transitionTime != null) ? Math.round(settings.transitionTime.toFloat() / 100) : (defaultTransitionTime != null ? defaultTransitionTime : 250) // library marker RMoRobert.CoCoHue_CT_Lib, line 84
   } // library marker RMoRobert.CoCoHue_CT_Lib, line 85
   else { // library marker RMoRobert.CoCoHue_CT_Lib, line 86
      scaledRate = Math.round(settings.ctTransitionTime.toFloat() / 100) // library marker RMoRobert.CoCoHue_CT_Lib, line 87
   } // library marker RMoRobert.CoCoHue_CT_Lib, line 88
   return scaledRate // library marker RMoRobert.CoCoHue_CT_Lib, line 89
} // library marker RMoRobert.CoCoHue_CT_Lib, line 90

void setGenericTempName(temp) { // library marker RMoRobert.CoCoHue_CT_Lib, line 92
   if (!temp) return // library marker RMoRobert.CoCoHue_CT_Lib, line 93
   String genericName = convertTemperatureToGenericColorName(temp) // library marker RMoRobert.CoCoHue_CT_Lib, line 94
   if (device.currentValue("colorName") != genericName) doSendEvent("colorName", genericName) // library marker RMoRobert.CoCoHue_CT_Lib, line 95
} // library marker RMoRobert.CoCoHue_CT_Lib, line 96

// ~~~~~ end include (3) RMoRobert.CoCoHue_CT_Lib ~~~~~

// ~~~~~ start include (5) RMoRobert.CoCoHue_Flash_Lib ~~~~~
// Version 1.0.0 // library marker RMoRobert.CoCoHue_Flash_Lib, line 1

library ( // library marker RMoRobert.CoCoHue_Flash_Lib, line 3
   base: "driver", // library marker RMoRobert.CoCoHue_Flash_Lib, line 4
   author: "RMoRobert", // library marker RMoRobert.CoCoHue_Flash_Lib, line 5
   category: "Convenience", // library marker RMoRobert.CoCoHue_Flash_Lib, line 6
   description: "For internal CoCoHue use only. Not intended for external use. Contains flash-related code shared by many CoCoHue drivers.", // library marker RMoRobert.CoCoHue_Flash_Lib, line 7
   name: "CoCoHue_Flash_Lib", // library marker RMoRobert.CoCoHue_Flash_Lib, line 8
   namespace: "RMoRobert" // library marker RMoRobert.CoCoHue_Flash_Lib, line 9
) // library marker RMoRobert.CoCoHue_Flash_Lib, line 10

void flash() { // library marker RMoRobert.CoCoHue_Flash_Lib, line 12
   if (logEnable == true) log.debug "flash()" // library marker RMoRobert.CoCoHue_Flash_Lib, line 13
   if (settings.txtEnable == true) log.info("${device.displayName} started 15-cycle flash") // library marker RMoRobert.CoCoHue_Flash_Lib, line 14
   Map<String,String> cmd = ["alert": "lselect"] // library marker RMoRobert.CoCoHue_Flash_Lib, line 15
   sendBridgeCommandV1(cmd, false)  // library marker RMoRobert.CoCoHue_Flash_Lib, line 16
} // library marker RMoRobert.CoCoHue_Flash_Lib, line 17

void flashOnce() { // library marker RMoRobert.CoCoHue_Flash_Lib, line 19
   if (logEnable == true) log.debug "flashOnce()" // library marker RMoRobert.CoCoHue_Flash_Lib, line 20
   if (settings.txtEnable == true) log.info("${device.displayName} started 1-cycle flash") // library marker RMoRobert.CoCoHue_Flash_Lib, line 21
   Map<String,String> cmd = ["alert": "select"] // library marker RMoRobert.CoCoHue_Flash_Lib, line 22
   sendBridgeCommandV1(cmd, false)  // library marker RMoRobert.CoCoHue_Flash_Lib, line 23
} // library marker RMoRobert.CoCoHue_Flash_Lib, line 24

void flashOff() { // library marker RMoRobert.CoCoHue_Flash_Lib, line 26
   if (logEnable == true) log.debug "flashOff()" // library marker RMoRobert.CoCoHue_Flash_Lib, line 27
   if (settings.txtEnable == true) log.info("${device.displayName} was sent command to stop flash") // library marker RMoRobert.CoCoHue_Flash_Lib, line 28
   Map<String,String> cmd = ["alert": "none"] // library marker RMoRobert.CoCoHue_Flash_Lib, line 29
   sendBridgeCommandV1(cmd, false)  // library marker RMoRobert.CoCoHue_Flash_Lib, line 30
} // library marker RMoRobert.CoCoHue_Flash_Lib, line 31

// ~~~~~ end include (5) RMoRobert.CoCoHue_Flash_Lib ~~~~~
