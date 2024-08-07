/*
 * =============================  CoCoHue Generic Status Device (Driver) ===============================
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
 *  Last modified: 2024-06-30
 * 
 *  Changelog:
 *  v4.1.6  - Note Hue Labs deprecation
 *  v4.1.5  - Fix typos
 *  v4.1.4  - Improved error handling, fix missing battery for motion sensors
 *  v4.0    - Add SSE support for push
 *  v3.5.1  - Refactor some code into libraries (code still precompiled before upload; should not have any visible changes)
 *  v3.5    - Minor code cleanup, removal of custom "push" command now that is standard capability command
 *  v3.1    - Improved error handling and debug logging
 *  v3.0    - Initial release
 */

// This driver is used for Hue Labs activator devices

#include RMoRobert.CoCoHue_Common_Lib

import hubitat.scheduling.AsyncResponse
import groovy.transform.Field

@Field static final Integer debugAutoDisableMinutes = 30

metadata {
   definition(name: "CoCoHue Generic Status Device", namespace: "RMoRobert", author: "Robert Morris", importUrl: "https://raw.githubusercontent.com/HubitatCommunity/CoCoHue/master/drivers/cocohue-generic-status-driver.groovy") {
      capability "Actuator"
      capability "Refresh"
      capability "Switch"
      capability "PushableButton"
   }
       
   preferences {
      input name: "onRefresh", type: "enum", title: "Bridge refresh on activation/deactivation: when this device is activated or deactivated by a Hubitat command...",
         options: [["none": "Do not refresh Bridge"],
                   ["1000": "Refresh Bridge device in 1s"],
                   ["5000": "Refresh Bridge device in 5s"]],
         defaultValue: "none"
      input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
      input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
      input name: "suppressDeprecationWarning", type: "bool", title: "Suppress warning in logs about Hue Labs deprecation (see: https://www.philips-hue.com/en-us/support/article/huelabs/000003)"
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
}

void refresh() {
   log.warn "Refresh CoCoHue Bridge device instead of individual device to update (all) bulbs/groups"
}

// Probably won't happen but...
void parse(String description) {
   log.warn("Running unimplemented parse for: '${description}'")
}

/**
 * Parses Hue Bridge device ID number out of Hubitat DNI for use with Hue API calls
 * Hubitat DNI is created in format "CCH/BridgeMACAbbrev/SensorRL/HueDeviceID", so just
 * looks for number after third "/" character
 */
String getHueDeviceNumber() {
   return device.deviceNetworkId.split("/")[3]
}

void on() {
   if (logEnable) log.debug "on()"
   sendBridgeCommand(["status": 1])
   if (settings["onRefresh"] == "1000" || settings["onRefresh"] == "5000") {
      parent.runInMillis(new Integer(settings["onRefresh"]), "refreshBridge")
   }
   if (suppressDeprecationWarning != true) {
      log.warn "Hue Labs is deprecated, and we suggest following Philips' advice to move to new features. See CoCoHue app for more details."
   }
}

void off() {
   if (logEnable) log.debug "off()"
   sendBridgeCommand(["status": 0])
   if (settings["onRefresh"] == "1000" || settings["onRefresh"] == "5000") {
      parent.runInMillis(new Integer(settings["onRefresh"]), "refreshBridge")
   }
   if (suppressDeprecationWarning != true) {
      log.warn "Hue Labs is deprecated, and we suggest following Philips' advice to move to new features. See CoCoHue app for more details."
   }
}

void push(btnNum) {
   if (logEnable) log.debug "push($btnNum)"
   on()
   doSendEvent("pushed", 1, null, true)
}

/**
 * Iterates over Hue device commands/states in Hue format (e.g., ["on": true]) and does
 * a sendEvent for each relevant attribute; intended to be called either when commands are sent
 * to Bridge (or if pre-staged attribute is changed and "real" command not yet able to be sent, but
 * this isn't supported for sensors, so this driver's methods are a bit different)
 * @param stateMap Map of JSON device state as received from Bridge
 */
void createEventsFromMap(Map stateMap) {
   if (!stateMap) {
      if (logEnable) log.debug "createEventsFromMap called but state map empty; exiting"
      return
   }
   if (logEnable) log.debug "Preparing to create events from map: ${stateMap}"
   String eventName, eventUnit, descriptionText
   String eventValue // should only be string here; could be String or number with lights/groups
   stateMap.each {
      switch (it.key) {
         case "status":
            eventName = "switch"
            eventValue = ((it.value as Integer) != 0) ? "on" : "off"
            eventUnit = null
            if (device.currentValue(eventName) != eventValue) doSendEvent(eventName, eventValue, eventUnit)
            break
         default:
            break
            //log.warn "Unhandled key/value discarded: $it"
      }
   }
}

/**
 * Sends HTTP PUT to Bridge using the command map (auto-converted to JSON)
 * @param bridgeCmds Map of Bridge command to send, e.g., ["state": 1]
 * @param createHubEvents Will iterate over Bridge command map and do sendEvent for all
 *        affected device attributes (e.g., will send an "on" event for sensor's "switch" if contains "state": 1)
 */
void sendBridgeCommand(Map bridgeCmds = [:], Boolean createHubEvents=true) {
   if (logEnable) log.debug "Sending command to Bridge: ${bridgeCmds}"
   if (!bridgeCmds) {
      log.debug("Commands not sent to Bridge because command map empty")
      return
   }
   Map<String,String> data = parent.getBridgeData()
   Map params = [
      uri: data.fullHost,
      path: "/api/${data.username}/sensors/${getHueDeviceNumber()}/state",
      contentType: 'application/json',
      body: bridgeCmds,
      timeout: 15
      ]
   asynchttpPut("parseSendCommandResponse", params, createHubEvents ? bridgeCmds : null)
   if (logEnable) log.debug "-- Command sent to Bridge! --"
}

/** 
  * Parses response from Bridge (or not) after sendBridgeCommand. Updates device state if
  * appears to have been successful.
  * @param resp Async HTTP response object
  * @param data Map of commands sent to Bridge if specified to create events from map
  */
void parseSendCommandResponse(AsyncResponse resp, Map data) {
   if (logEnable) log.debug "Response from Bridge: ${resp.status}"
   if (checkIfValidResponse(resp) && data) {
      if (logEnable) log.debug "  Bridge response valid; creating events from data map"
      createEventsFromMap(data)
   }
   else {
      if (logEnable) log.debug "  Not creating events from map because not specified to do or Bridge response invalid"
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