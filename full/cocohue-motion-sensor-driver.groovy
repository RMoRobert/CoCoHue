/*
 * =============================  CoCoHue Motion Sensor (Driver) ===============================
 *
 *  Copyright 2020-2024 Robert Morris
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
 *
 *  Last modified: 2024-08-31
 *
 *  Changelog:
 *  v5.0    - Use API v2 by default, remove deprecated features
 *  v4.2    - Library updates, prep for more v2 API
 *  v4.1.4  - Improved error handling, fix missing battery for motion sensors
 *  v4.0    - Add SSE support for push
 *  v3.5.1  - Refactor some code into libraries (code still precompiled before upload; should not have any visible changes)
 *  v3.5    - Minor code cleanup
 *  v3.1.6  - Fixed runtime error when using temperature offset; ensure battery and lux reported as integers, temperature as BigDecimal
 *  v3.1.2  - Added optional offset for temperature sensor
 *  v3.1    - Improved error handling and debug logging
 *  v3.0    - Initial release
 */
 



import groovy.transform.Field

@Field static final Integer debugAutoDisableMinutes = 30

metadata {
   definition(name: DRIVER_NAME_MOTION, namespace: NAMESPACE, author: "Robert Morris", importUrl: "https://raw.githubusercontent.com/HubitatCommunity/CoCoHue/master/drivers/cocohue-motion-sensor-driver.groovy") {
      capability "Sensor"
      capability "Refresh"
      capability "MotionSensor"
      capability "IlluminanceMeasurement"
      capability "TemperatureMeasurement"
      capability "Battery"
   }

   preferences {
      input name: "tempAdjust", type: "number", title: "Adjust temperature reading by this amount", description: "Example: 0.4 or -1.5 (optional)"
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

void refresh() {
   log.warn "Refresh CoCoHue Bridge device instead of individual device to update (all) bulbs/groups/sensors"
}

// Probably won't happen but...
void parse(String description) {
   log.warn("Running unimplemented parse for: '${description}'")
}

// Not used since never sends commands?
// /**
//  * Parses V2 Hue Bridge device ID out of Hubitat DNI for use with Hue V2 API calls
//  * Hubitat DNI is created in format "CCH/AppID/Sensor/HueDeviceIDv2", so just
//  * looks for string after last "/" character
//  */
// String getHueDeviceIdV2() {
//    return device.deviceNetworkId.split("/")[-1]
// }

/**
 * Iterates over Hue sensor state commands/states in Hue format (e.g., ["lightlevel": 25000]) and does
 * a sendEvent for each relevant attribute; for sensors, intended to be called
 * to parse/update sensor state on Hubitat based on data received from Bridge
 * @param bridgeCmd Map of sensor states from Bridge (for lights, this could be either a command to or response from)
 */
void createEventsFromMapV1(Map bridgeCmd) {
   if (!bridgeCmd) {
      if (logEnable) log.debug "createEventsFromMapV1 called but map empty; exiting"
      return
   }
   if (logEnable) log.debug "Preparing to create events from map: ${bridgeCmd}"
   String eventName, eventUnit, descriptionText
   def eventValue // could be numeric (lux, temp) or boolean (motion)
   bridgeCmd.each {
      switch (it.key) {
         case "presence":
            eventName = "motion"
            eventValue = it.value ? "active" : "inactive"
            eventUnit = null
            if (device.currentValue(eventName) != eventValue) doSendEvent(eventName, eventValue, eventUnit)
            break
         case "lightlevel":
            eventName = "illuminance"
            eventValue = Math.round(10 ** (((it.value as Integer)-1)/10000))
            eventUnit = "lux"
            if (device.currentValue(eventName) != eventValue) doSendEvent(eventName, eventValue as Integer, eventUnit)
            break
         case "temperature":
            eventName = "temperature"
            if (location.temperatureScale == "C") eventValue = ((it.value as BigDecimal)/100.0).setScale(1, java.math.RoundingMode.HALF_UP)
            else eventValue = celsiusToFahrenheit((it.value as BigDecimal)/100.0).setScale(1, java.math.RoundingMode.HALF_UP)
            if (settings["tempAdjust"]) eventValue = (eventValue as BigDecimal) + (settings["tempAdjust"] as BigDecimal)
            eventUnit = "°${location.temperatureScale}"
            if (device.currentValue(eventName) != eventValue) doSendEvent(eventName, eventValue as BigDecimal, eventUnit)
            break
         case "battery":
            eventName = "battery"
            eventValue = (it.value != null) ? (it.value as Integer) : 0
            eventUnit = "%"
            if (device.currentValue(eventName) != eventValue) doSendEvent(eventName, eventValue as Integer, eventUnit)
            break
         default:
            break
            //log.warn "Unhandled key/value discarded: $it"
      }
   }
}

/**
 * (for "new"/v2/EventSocket [SSE] API; not documented and subject to change)
 * Iterates over Hue light state states in Hue API v2 format (e.g., "on={on=true}") and does
 * a sendEvent for each relevant attribute; intended to be called when EventSocket data
 * received for device (as an alternative to polling)
 */
void createEventsFromMapV2(Map data) {
   if (logEnable == true) log.debug "createEventsFromMapV2($data)"
   String eventName, eventUnit, descriptionText
   def eventValue // could be String or number
   data.each { String key, value ->
      switch (key) {
         case "motion":
            eventName = "motion"
            eventValue = value.motion ? "active" : "inactive"
            eventUnit = null
            if (device.currentValue(eventName) != eventValue) doSendEvent(eventName, eventValue, eventUnit)
            break
         case "light":
            eventName = "illuminance"
            eventValue = Math.round(10 ** (((value.light_level as Integer)-1)/10000))
            eventUnit = "lux"
            if (device.currentValue(eventName) != eventValue) doSendEvent(eventName, eventValue as Integer, eventUnit)
            break
         case "temperature":
            eventName = "temperature"
            if (location.temperatureScale == "C") eventValue = ((value.temperature as BigDecimal)).setScale(1, java.math.RoundingMode.HALF_UP)
            else eventValue = celsiusToFahrenheit((value.temperature  as BigDecimal).setScale(1, java.math.RoundingMode.HALF_UP))
            if (settings["tempAdjust"]) eventValue = (eventValue as BigDecimal) + (settings["tempAdjust"] as BigDecimal)
            eventUnit = "°${location.temperatureScale}"
            if (device.currentValue(eventName) != eventValue) doSendEvent(eventName, eventValue as BigDecimal, eventUnit)
            break
         case "power_state":
            eventName = "battery"
            eventValue = value.battery_level
            eventUnit = "%"
            if (eventValue != null && device.currentValue(eventName) != eventValue) doSendEvent(eventName, eventValue as Integer, eventUnit)
            break
         case "id_v1":
            if (state.id_v1 != value) state.id_v1 = value
            break
         default:
            if (logEnable == true) log.debug "not handling: key $key = value $value"
      }
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
