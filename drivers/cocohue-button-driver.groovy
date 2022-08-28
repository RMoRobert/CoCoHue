/*
 * =============================  CoCoHue Button (Driver) ===============================
 *
 *  Copyright 22022 Robert Morris
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
 *  Last modified: 2022-08-28
 * 
 *  Changelog:
 *  v4.1     - Initial release (with CoCoHue app/bridge 4.1)
 */

#include RMoRobert.CoCoHue_Common_Lib

import hubitat.scheduling.AsyncResponse
import groovy.transform.Field

@Field static final Integer debugAutoDisableMinutes = 30

metadata {
   definition(name: "CoCoHue Button", namespace: "RMoRobert", author: "Robert Morris", importUrl: "https://raw.githubusercontent.com/HubitatCommunity/CoCoHue/master/drivers/cocohue-button-driver.groovy") {
      capability "Actuator"
      //capability "Refresh"
      capability "PushableButton"
      capability "HoldableButton"
      capability "ReleasableButton"
      capability "Configuration"
   }

   preferences {
      input name: "enableDebug", type: "bool", title: "Enable debug logging", defaultValue: true
      input name: "enableDesc", type: "bool", title: "Enable descriptionText logging", defaultValue: true
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
   if (enableDebug) {
      log.debug "Debug logging will be automatically disabled in ${debugAutoDisableMinutes} minutes"
      runIn(debugAutoDisableMinutes*60, "debugOff")
   }
}

void configure() {
   log.debug "configure()"
   // nothing? remove capability if not needed...
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

/** 
  * Parses response from Bridge (or not) after sendBridgeCommand. Updates device state if
  * appears to have been successful.
  * @param resp Async HTTP response object
  * @param data Map with keys 'attribute' and 'value' containing event data to send if successful (e.g., [attribute: 'switch', value: 'off'])
  */
void parseSendCommandResponse(AsyncResponse resp, Map data) {
   if (enableDebug) log.debug "Response from Bridge: ${resp.status}; data from app = $data"
   // TODO: Rethink for buttons...
   if (checkIfValidResponse(resp) && data?.attribute != null && data?.value != null) {
      if (enableDebug) log.debug "  Bridge response valid; running creating events"
      if (device.currentValue(data.attribute) != data.value) doSendEvent(data.attribute, data.value)   
   }
   else {
      if (enableDebug) log.debug "  Not creating events from map because not specified to do or Bridge response invalid"
   }
}

void push(Number btnNum) {
   if (enableDebug) log.debug "push($btnNum)"
   doSendEvent("pushed", btnNum, null, true)
}

void hold(Number btnNum) {
   if (enableDebug) log.debug "hold($btnNum)"
   doSendEvent("held", btnNum, null, true)
}

void release(Number btnNum) {
   if (enableDebug) log.debug "release($btnNum)"
   doSendEvent("released", btnNum, null, true)
}

/**
 * Parses through device data/state in Hue API v2 format (e.g., "on={on=true}") and does
 * a sendEvent for each relevant attribute; intended to be called when EventSocket data
 * received for device
 */
void createEventsFromSSE(Map data) {
   if (enableDebug == true) log.debug "createEventsFromSSE($data)"
   String eventName
   Integer eventValue
   if (data.type == "button") {
      switch (data.button.last_event) {
         case "repeat":
            // prevent sending repeated "held" events
            if (state.lastHueEvent != "repeat") eventName = "held"
            else eventName = null
            break
         case "long_release":
            eventName = "released"
            break
         default:
            eventName = "pushed"
      }
      state.lastHueEvent = data.button.last_event
      eventValue = state.buttons.find({ it.key == data.id})?.value ?: 1
      if (eventName != null) doSendEvent(eventName, eventValue, null, true)
   }
   else {
      if (enableDebug) log.debug "ignoring; data.type = ${data.type}"
   }
}

/**
 * Sets state.button to IDs a Map in format [subButtonId: buttonNumber], used to determine
 * which button number to use for events when it is believed to be one this device "owns"
*/
void setButtons(Map<String,Integer> buttons) {
   if (enableDebug) log.debug "setButtons($buttons)"
   state.buttons = buttons
   doSendEvent("numberOfButtons", buttons.keySet().size())
}
