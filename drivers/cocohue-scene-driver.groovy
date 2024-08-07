/*
 * =============================  CoCoHue Scene (Driver) ===============================
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
 *
 *  Last modified: 2024-07-28
 * 
 *  Changelog:
 *  v4.2    - Add support for parsing on/off events from v2 API state; library improvements; prep for mre v2 API use
 *  v4.1.5  - Fix typos
 *  v4.1.4  - Improved error handling, fix missing battery for motion sensors
 *  v4.0    - Refactoring to match other CoCoHue drivers
 *  v3.5.1  - Refactor some code into libraries (code still precompiled before upload; should not have any visible changes);
 *            Remove capability "Light" from scene driver (better chance of Alexa seeing as switch and not light)
 *  v3.5    - Minor code cleanup, removal of custom "push" command now that is standard capability command
 *  v3.1    - Improved error handling and debug logging
 *  v3.0    - Improved HTTP error handling 
 *  v2.1    - Reduced info logging when not state change; code cleanup and more static typing
 *  v2.0    - Improved HTTP error handling; attribute events now generated only after hearing back from Bridge;
 *            Bridge online/offline status improvements; bug fix for off() with light- or group-device-less scenes
 *            Added options for scene "switch" attribute (on/off) behavior
 *            Added options for optional Bridge refresh on scene on/off or push (activation) commands 
 *  v1.9    - Added off() functionality
 *  v1.7    - Added configure() per Capability requirement
 *  v1.5b   - Initial public release
 */ 

#include RMoRobert.CoCoHue_Common_Lib

import hubitat.scheduling.AsyncResponse
import groovy.transform.Field

@Field static final Integer debugAutoDisableMinutes = 30

metadata {
   definition(name: "CoCoHue Scene", namespace: "RMoRobert", author: "Robert Morris", importUrl: "https://raw.githubusercontent.com/HubitatCommunity/CoCoHue/master/drivers/cocohue-scene-driver.groovy") {
      capability "Actuator"
      capability "Refresh"
      capability "Switch"
      capability "PushableButton"
      capability "Configuration"
   }

   preferences {
      input name: "onPropagation", type: "enum", title: "Scene \"on\"/\"off\" behavior: when this scene is activated...",
         options: [["none": "Do not manipulate other scene device states"],
                   ["groupScenesOff": "Mark other scenes for this group as off (if GroupScene)"],
                   ["allScenesOff": "Mark all other CoCoHue scenes as off"],
                   ["autoOff": "Automatically mark as off in 5 seconds"]],
         defaultValue: "groupScenesOff"
      input name: "onRefresh", type: "enum", title: "Bridge refresh on activation/deactivation: when this scene is activated or deactivated by a Hubitat command...",
         options: [["none": "Do not refresh Bridge"],
                   ["1000": "Refresh Bridge device in 1s"],
                   ["5000": "Refresh Bridge device in 5s"]],
         defaultValue: "none"
      input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
      input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
   }
}

void installed() {
   log.debug "installed()"
   setDefaultAttributeValues()
   initialize()
}

void updated() {
   log.debug "updated()"
   initialize()
}

void initialize() {
   log.debug "initialize()"
   sendEvent(name: "numberOfButtons", value: 1)
   if (logEnable) {
      log.debug "Debug logging will be automatically disabled in ${debugAutoDisableMinutes} minutes"
      runIn(debugAutoDisableMinutes*60, "debugOff")
   }
   refresh() // Get scene data
}

void configure() {
   log.debug "configure()"
   setDefaultAttributeValues()
}

// Probably won't happen but...
void parse(String description) {
   log.warn("Running unimplemented parse for: '${description}'")
}

/**
 * Parses Hue Bridge scene ID number out of Hubitat DNI for use with Hue API calls
 * Hubitat DNI is created in format "CCH/BridgeMACAbbrev/Scenes/HueSceneID", so just
 * looks for number after third "/" character
 */
String getHueDeviceNumber() {
   return device.deviceNetworkId.split("/")[3]
}

void on() {
   Map<String,String> data = parent.getBridgeData()
   Map cmd = ["scene": getHueDeviceNumber()]
   Map params = [
      uri: data.fullHost,
      path: "/api/${data.username}/groups/0/action",
      contentType: 'application/json',
      body: cmd,
      timeout: 15
      ]
   asynchttpPut("parseSendCommandResponse", params, [attribute: 'switch', value: 'on'])
   if (settings["onRefresh"] == "1000" || settings["onRefresh"] == "5000") {
      parent.runInMillis(settings["onRefresh"] as Integer, "refreshBridge")
   }
   if (logEnable) log.debug "Command sent to Bridge: $cmd"
}

void off() {
   if (logEnable) log.debug "off()"
   if (state.type == "GroupScene") {
      if (logEnable) log.debug "Scene is GroupScene; turning off group $state.group"
      List<String> dniParts = device.deviceNetworkId.split("/")
      String dni = "${dniParts[0]}/${dniParts[1]}/Group/${state.group}"
      com.hubitat.app.DeviceWrapper dev = parent.getChildDevice(dni)
      if (dev) {
         if (logEnable) log.debug "Hubitat device for group ${state.group} found; turning off"
         dev.off()
         doSendEvent("switch", "off", null) // optimistic here; group device will catch if problem
      }
      else {
         if (logEnable) log.debug "Device not found; sending command directly to turn off Hue group"
         Map<String,String> data = parent.getBridgeData()
         Map cmd = ["on": false]
         Map params = [
               uri: data.fullHost,
               path: "/api/${data.username}/groups/${state.group}/action",
               contentType: 'application/json',
               body: cmd,
               timeout: 15
         ]
         asynchttpPut("parseSendCommandResponse", params, [attribute: 'switch', value: 'off'])
         if (logEnable) log.debug "Command sent to Bridge: $cmd"
      }
   } else if (state.type == "LightScene") {
      doSendEvent("switch", "off", null) // optimistic here (would be difficult to determine and aggregate individual light responses and should be rare anyway)
      if (logEnable) log.debug "Scene is LightScene; turning off lights $state.lights"
      state.lights.each {
         List<String> dniParts = device.deviceNetworkId.split("/")
         String dni = "${dniParts[0]}/${dniParts[1]}/Light/${it}"
         com.hubitat.app.DeviceWrapper dev = parent.getChildDevice(dni)
         if (dev) {
            if (logEnable) log.debug "Hubitat device for light ${it} found; turning off"
            dev.off()
         }
         else {
            if (logEnable) log.debug "Device not found; sending command directly to turn off Hue light"
            Map<String,String> data = parent.getBridgeData()
            Map cmd = ["on": false]
            Map params = [
               uri: data.fullHost,
               path: "/api/${data.username}/lights/${it}/state",
               contentType: 'application/json',
               body: cmd,
               timeout: 15
            ]
            asynchttpPut("parseSendCommandResponse", params)
            if (logEnable) log.debug "Command sent to Bridge: $cmd"
         }
      }
      if (settings["onRefresh"] == "1000" || settings["onRefresh"] == "5000") {
         parent.runInMillis(settings["onRefresh"] as Integer, "refreshBridge")
      }
   }
   else {
      log.warn "No off() action available for scene $device.displayName"
   }
}

/**
 * Iterates over Hue scene state state data in Hue API v2 (SSE) format and does
 * a sendEvent for each relevant attribute; intended to be called when EventSocket data
 * received for device (as an alternative to polling)
 */
void createEventsFromSSE(Map data) {
   if (logEnable == true) log.debug "createEventsFromSSE($data)"
   String eventName, eventUnit, descriptionText
   def eventValue // could be String or number
   Boolean hasCT = data.color_temperature?.mirek != null
   data.each { String key, value ->
      //log.trace "$key = $value"
      switch (key) {
         case "status":
            eventName = "switch"
            eventValue = (value.active == "inactive" || value.active == null) ? "off" : "on"
            eventUnit = null
            if (device.currentValue(eventName) != eventValue) doSendEvent(eventName, eventValue, eventUnit)
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
  * Parses response from Bridge (or not) after sendBridgeCommand. Updates device state if
  * appears to have been successful.
  * @param resp Async HTTP response object
  * @param data Map with keys 'attribute' and 'value' containing event data to send if successful (e.g., [attribute: 'switch', value: 'off'])
  */
void parseSendCommandResponse(AsyncResponse resp, Map data) {
   if (logEnable) log.debug "Response from Bridge: ${resp.status}; data from app = $data"
   if (checkIfValidResponse(resp) && data?.attribute != null && data?.value != null) {
      if (logEnable) log.debug "  Bridge response valid; running creating events"
      if (device.currentValue(data.attribute) != data.value) doSendEvent(data.attribute, data.value)   
      if (data.attribute == "switch" && data.value == "on") {
         if (settings["onPropagation"] == "groupScenesOff") {
            parent.updateSceneStateToOffForGroup(state.group ?: "0", device.deviceNetworkId)
         }
         else if (settings["onPropagation"] == "allScenesOff") {
            parent.updateSceneStateToOffForGroup("0", device.deviceNetworkId)
         }
         else if (settings["onPropagation"] == "autoOff") {
            runIn(5, autoOffHandler)
         }
         else {
            if (logEnable) log.debug "No scene onPropagation configured; leaving other scene states as-is"
         }
      }
   }
   else {
      if (logEnable) log.debug "  Not creating events from map because not specified to do or Bridge response invalid"
   }
}

void push(Number btnNum) {
   if (logEnable) log.debug "push($btnNum)"
   on()
   doSendEvent("pushed", 1, null, true)
}

/** Gets data about scene from Bridge; does not update bulb/group status */
void refresh() {
   if (logEnable) log.debug "refresh()"
   Map<String,String> data = parent.getBridgeData()
   Map sceneParams = [
      uri: data.fullHost,
      path: "/api/${data.username}/scenes/${getHueDeviceNumber()}",
      contentType: 'application/json',
      timeout: 15
      ]
   asynchttpGet("parseSceneAttributeResponse", sceneParams)  
}

/**
 * Parses data returned when getting scene data from Bridge
 */
void parseSceneAttributeResponse(resp, data) {
   if (logEnable) log.debug "parseSceneAttributeResponse response from Bridge: $resp.status"
   Map sceneAttributes
   try {
      sceneAttributes = resp.json
   } catch (ex) {
      log.error("Could not parse scene data: ${resp.errorMessage ?: ex}")
      return
   }
   if (sceneAttributes["type"] == "GroupScene") {
      state.type = "GroupScene"
      state.group = sceneAttributes["group"]
      state.remove("lights")
   }
   else if (sceneAttributes["type"] == "LightScene") {
      state.type = "LightScene"
      state.lights = sceneAttributes["lights"]
      state.remove("group")
   }
   else {
      log.warn "Unknown scene type; off() commands will not work"
      state.remove("group")
      state.remove("lights")
      state.remove("type")
   }
}

/**
 * Sets all group attribute values to something, intended to be called when device initially created to avoid
 * missing attribute values (may cause problems with GH integration, etc. otherwise). Default values are
 * approximately warm white and off.
 */
private void setDefaultAttributeValues() {
   if (logEnable) log.debug "Setting scene device states to sensibile default values..."
   event = sendEvent(name: "switch", value: "off", isStateChange: false)
   event = sendEvent(name: "pushed", value: 1, isStateChange: false)
}

void autoOffHandler() {
   doSendEvent("switch", "off") 
}

/**
 * Returns Hue group ID (as String, since it is likely to be used in DNI check or API call).
 * May return null (if is not GroupScene)
 */
String getGroupID() {
   return state.group
}