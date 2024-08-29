/**
 * =============================  CoCoHue Bridge (Driver) ===============================
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
 *  Last modified: 2024-08-27
 *
 *  Changelog:
 *  v5.0    - Use API v2 by default, remove deprecated features
 *  v4.2.1  - Add scene on/off state reporting with v2 API
 *  v4.2    - Improved eventstream reconnection logic
 *  v4.1.4  - Improved error handling, fix missing battery for motion sensors
 *  v4.1.3  - Improved eventstream data handling (when multiple devices included in same payload, thanks to @Modem-Tones)
 *  v4.1.2  - Additional button enhancements (relative_rotary -- Hue Tap Dial, etc.)
 *  v4.1    - Add button device support (with v2 API only)
 *  v4.0.2  - Fix to avoid unexpected "off" transition time
 *  v4.0.1  - Fix for "on" state of "All Hue Lights" group (if used)
 *  v4.0.1  - Minor sensor cache updates
 *  v4.0    - EventStream support for real-time updates
 *  v3.5.1  - Refactor some code into libraries (code still precompiled before upload; should not have any visible changes)
 *  v3.5    - Minor code cleanup
 *  v3.1    - Improved error handling and debug logging
 *  v3.0    - Added support for sensors (Hue Motion sensors with motion/temp/lux) and Hue Labs effects (looks for resoucelinks with 1 sensor link)
 *          - Revamped refresh/sync to fetch all Bridge data instead of indiviudal /lights, /groups, etc. APIs (goal: reduce to one HTTP call and response total)
 *  v2.1    - Minor code cleanup and more static typing
 *  v2.0    - Added Actuator capability; Bridge and HTTP error handling improvements; added specific HTTP timeout
 *  v1.5    - Additional methods to support scenes and improved group behavior
 *  v1.0    - Initial Release
 */ 

#include RMoRobert.CoCoHue_Common_Lib

import groovy.json.JsonSlurper
import hubitat.scheduling.AsyncResponse
import com.hubitat.app.DeviceWrapper
import groovy.transform.Field

// Number of seconds to wait after Bridge EventStream (SSE) is disconnected before consider it so on Hubitat
// Seems to be helpful at the moment because get spurious disconnects when SSE is working fine, shortly followed
// by a reconnect (~6 sec for me, so 7 should cover most)
@Field static final Integer eventStreamDisconnectGracePeriod = 8
// For readTimeout value in eventstream connection:
@Field static final Integer eventStreamReadTimeout = 3600

@Field static final Integer debugAutoDisableMinutes = 30

// These are as reported by V1 API and are also set to the same text based on device capabilities advertised in V2 API:
@Field static final Map<String,String> bulbTypes = [
   "extended color light":     "CoCoHue RGBW Bulb",
   "color light":              "CoCoHue RGBW Bulb",  // eventually should make this one RGB
   "color temperature light":  "CoCoHue CT Bulb",
   "dimmable light":           "CoCoHue Dimmable Bulb",
   "on/off light":             "CoCoHue On/Off Plug",
   "on/off plug-in unit":      "CoCoHue On/Off Plug",
   "DEFAULT":                  "CoCoHue RGBW Bulb"
]

metadata {
   definition(name: "CoCoHue Bridge", namespace: "RMoRobert", author: "Robert Morris", importUrl: "https://raw.githubusercontent.com/HubitatCommunity/CoCoHue/master/drivers/cocohue-bridge-driver.groovy") {
      capability "Actuator"
      capability "Refresh"
      capability "Initialize"
      
      command "connectEventStream"
      command "disconnectEventStream"
      command "refreshV1" // can be used to force V1 API refresh even if using V2 otherwise

      attribute "status", "STRING"
      attribute "eventStreamStatus", "STRING"
   }
   
   preferences() {
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
   if (parent.getEventStremEnabledSetting()) connectEventStream()
}

void connectEventStream() {
   if (logEnable) log.debug "connectEventStream()"
   if (parent.getEventStremEnabledSetting() != true) {
      log.warn "CoCoHue app is configured not to use EventStream. To reliably use this interface, enable this option in the app."
   }
   Map<String,String> data = parent.getBridgeData()
   if (logEnable) {
      log.debug "Connecting to event stream at 'https://${data.ip}/eventstream/clip/v2' with Hue API key '${data.username}'"
   }
   interfaces.eventStream.close()
   interfaces.eventStream.connect(
      "https://${data.ip}/eventstream/clip/v2", [
      headers: ["Accept": "text/event-stream", "hue-application-key": data.username],
      rawData: true,
      pingInterval: 10,
      readTimeout: eventStreamReadTimeout,
      ignoreSSLIssues: true
   ])
}

void reconnectEventStream(Boolean notIfAlreadyConnected = true) {
   if (logEnable) log.debug "reconnectEventStream(notIfAlreadyConnected=$notIfAlreadyConnected)"
   if (device.currentValue("eventStreamStatus") == "connected" && notIfAlreadyConnected) {
      if (logEnable) log.debug "already connected; skipping reconnection"
   }   
   else if (parent.getEventStremEnabledSetting() != true) {
      if (logEnable) log.debug "skipping reconnection because (parent) app configured not to use EventStream"
   }
   else {
      connectEventStream()
   }
}

void disconnectEventStream() {
   interfaces.eventStream.close()
}

void eventStreamStatus(String message) {
   if (logEnable) log.debug "eventStreamStatus: $message"
   if (message.startsWith("START:")) {
      setEventStreamStatusToConnected()
   }
   else if (message.startsWith("STOP:")) {
      runIn(eventStreamDisconnectGracePeriod, "setEventStreamStatusToDisconnected")
   }
   else {
      if (logEnable) log.debug "Unhandled eventStreamStatus message: $message"
   }
}

private void setEventStreamStatusToConnected() {
   parent.setEventStreamOpenStatus(true) // notify app
   unschedule("setEventStreamStatusToDisconnected")
   if (device.currentValue("eventStreamStatus") == "disconnected") doSendEvent("eventStreamStatus", "connected")
   state.connectionRetryTime = 3
}

private void setEventStreamStatusToDisconnected() {
   parent.setEventStreamOpenStatus(false) // notify app
   doSendEvent("eventStreamStatus", "disconnected")
   if (state.connectionRetryTime) {
      state.connectionRetryTime *= 2
      if (state.connectionRetryTime > 900) {
         state.connectionRetryTime = 900 // cap retry time at 15 minutes
      }
   }
   else {
      state.connectionRetryTime = 5
   }
   if (logEnable) log.debug "Reconnecting SSE in ${state.connectionRetryTime}"
   runIn(state.connectionRetryTime, "reconnectEventStream")
}

// For EventStream:
void parse(String description) {
   if (logEnable) log.debug "parse: $description"
   List<String> messages = description.split("\n\n")
   setEventStreamStatusToConnected() // should help avoid spurious disconnect messages?
   messages.each { String message -> 
      List<String> lines = description.split("\n")
      StringBuilder sbData = new StringBuilder()
      lines.each { String line ->
         if (line.startsWith("data: ")) {
            sbData << line.substring(6)
         }
         else {
            if (logEnable) log.debug "ignoring line: $line"
         }
      }
      if (sbData) {
         List dataList = new JsonSlurper().parseText(sbData.toString())
         dataList.each { dataEntryMap ->
            //log.trace "--> DATA = ${dataEntryMap}"
            if (dataEntryMap.type == "update") {
               dataEntryMap.data?.each { updateEntryMap ->
                  log.trace "--> map = ${updateEntryMap}"
                  String idV1
                  if (updateEntryMap.id_v1 != null) idV1 = updateEntryMap.id_v1.split("/")[-1]
                  String idV2 = updateEntryMap.id
                  String idV1Num
                  DeviceWrapper dev
                  log.warn "type = ${updateEntryMap.type}"
                  log.warn " &nbsp; owner.rtype = ${updateEntryMap.owner?.rtype}"
                  if (idV2 != null) {
                     switch (updateEntryMap.type) {
                        case "light":
                           dev = parent.getChildDevice("${device.deviceNetworkId}/Light/${idV2}")
                           if (dev == null) dev = parent.getChildDevice("${device.deviceNetworkId}/Light/${idV1}")
                           break
                        case "grouped_light":  // does this one actually happpen?
                        case "room":
                        case "zone":
                        case "bridge_home":
                           dev = parent.getChildDevice("${device.deviceNetworkId}/Group/${idV2}")
                           if (dev == null)  dev = parent.getChildDevice("${device.deviceNetworkId}/Group/${idV1}")
                           break
                        case "scene":
                           dev = parent.getChildDevice("${device.deviceNetworkId}/Scene/${idV2}")
                           if (dev == null)  dev = parent.getChildDevice("${device.deviceNetworkId}/Scene/${idV1}")
                           break
                        case "motion":
                        case "temperature":
                        case "light_level": //todo: test or check is correct?
                           String ownerId = updateEntryMap.owner?.rid
                           dev = parent.getChildDevice("${device.deviceNetworkId}/Sensor/${ownerId}")
                           if (dev == null && idV1 != null) {
                              // or for now also check V1 sensor ID
                              dev = parent.getChildDevices().find { DeviceWrapper d ->
                                 idV1 in d.deviceNetworkId.tokenize('/')[-1].tokenize('|') &&
                                 d.deviceNetworkId.startsWith("${device.deviceNetworkId}/Sensor/")  // shouldn't be necessary but gave me a Light ID once in testing for a sensor, so?!
                              }
                           }
                           break
                        case "button":
                        case "relative_rotary": // todo: test!
                           String ownerId = updateEntryMap.owner?.rid
                           dev = parent.getChildDevice("${device.deviceNetworkId}/Button/${ownerId}")
                           break
                        case "device_power": // could be motion sensor or button
                           dev = parent.getChildDevice("${device.deviceNetworkId}/Sensor/${ownerId}")
                           if (dev == null) parent.getChildDevice("${device.deviceNetworkId}/Button/${ownerId}")
                           break
                        default:
                           if (logEnable) log.debug "skipping Hue v1 ID: $idV1"
                     }
                     // If child device found, send map to it for parsing:
                     if (dev != null) dev.createEventsFromMapV2(updateEntryMap)
                  }
               }
            }
            else {
               if (logEnable) log.debug "skip: $dataEntryMap"
            }
         }
      }
      else {
         if (logEnable) log.trace "no data parsed from message: $message"
      }
   }
}

void refresh() {
   if (logEnable) log.debug "refresh()"
   Map<String,String> data = parent.getBridgeData()
   try {
      if (data.apiVersion == APIV1 || data.apiVersion == null) {
         refreshV1()
      }
      else {
         bridgeAsyncGetV2("parseStatesV2", "/resource", data)
      }
   }
   catch (Exception ex) {
      log.error "Error in refresh(): $ex"
   }
}

void refreshV1() {
   if (logEnable) log.debug "refreshV1()"
   Map<String,String> data = parent.getBridgeData()
   try {
      Map params = [
         uri: data.fullHost,
         path: "/api/${data.username}/",
         contentType: 'application/json',
         timeout: 15
      ]
      asynchttpGet("parseStatesV1", params)
   }
   catch (Exception ex) {
      log.error "Error in refreshV1(): $ex"
   }
}

void scheduleRefresh() {
   if (logEnable) log.debug "scheduleRefresh()"
   
}

/** Returns V1-format bulb/light type (e.g., "extended color light") based on information
  * found in V2 API light service data (e.g., presence or absence of color_temperature service, color, etc.)
*/
String determineLightType(Map data) {
   if (data.color && data.color_temperature) return "extended color light"
   else if (data.color_temperature) return "color temperature light"
   else if (data.color) return "color light"
   else if (data.dimming) return "dimmable light"
   else if (data.on) return "on/off light"
   else return "UNKNOWN"
}

/** Callback method that handles full Bridge refresh for V2 API. Eventually delegated to
 *  individual methods below.
 */
void parseStatesV2(AsyncResponse resp, Map data) { 
   if (logEnable) log.debug "parseStatesV2: States from Bridge received. Now parsing..."
   if (checkIfValidResponse(resp)) {
      //TODO: Check that all are updated for v2 (in progress!)
      // Lights:
      List<Map> lightsData = resp.json.data.findAll { it.type == "light" }
      // Groups (and Rooms and Zones):
      List<Map> roomsData = resp.json.data.findAll { it.type == "room" }  // probably needed? check if needed here...
      List<Map> zonesData = resp.json.data.findAll { it.type == "zone" }  // probably needed? check if needed here...
      List<Map> groupsData = resp.json.data.findAll { it.type == "grouped_light" }
      // Scenes:
      List<Map> scenesData = resp.json.data.findAll { it.type == "scene" }
      // Motion sensors (motion, temperature, lux, battery):
      List<Map> motionData = resp.json.data.findAll { it.type == "motion" }
      //log.trace "motion = $motionData"
      List<Map> temperatureData = resp.json.data.findAll { it.type == "temperature" }
      //log.trace  "ill = $illuminanceData"
      List<Map> illuminanceData = resp.json.data.findAll { it.type == "light_level" }
      //log.trace "ll = $illuminanceData"
      List<Map> batteryData = resp.json.data.findAll { it.type == "device_power" }
      //log.trace "batt = $batteryData"
      // TODO: batteryData could also be useful for buttons/remotes?
      // Probably does not make sense to parse other button events now (only in real time)
      // Check if anything else?
      parseLightStatesV2(lightsData)
      parseGroupStatesV2(groupsData)
      parseSceneStatesV2(scenesData)
      // TODO: see if can combine this data into one instead of calling 4x total:
      parseMotionSensorStatesV2(motionData)
      parseMotionSensorStatesV2(temperatureData)
      parseMotionSensorStatesV2(illuminanceData)
      parseMotionSensorStatesV2(batteryData)
   }
}

/** Callback method that handles full Bridge refresh for V1 API. Eventually delegated to
 *  individual methods below. For Hue V1 API.
 */
void parseStatesV1(AsyncResponse resp, Map data) { 
   if (logEnable) log.debug "parseStatesV1() - States from Bridge received. Now parsing..."
   if (checkIfValidResponse(resp)) {
      parseLightStatesV1(resp.json.lights)
      parseGroupStatesV1(resp.json.groups)
      parseMotionSensorStatesV1(resp.json.sensors)
   }
}

void parseLightStatesV2(List lightsJson) {
   if (logEnable) log.debug "parseLightStatesV2()"
   // Uncomment this line if asked to for debugging (or you're curious):
   //log.debug "lightsJson = $lightsJson"
   try {
      lightsJson.each { Map data ->
         String id = data.id 
         String id_v1 = data.id_v1 - "/lights/"
         DeviceWrapper dev = parent.getChildDevice("${device.deviceNetworkId}/Light/${id}")
         if (dev == null) {
            dev = parent.getChildDevice("${device.deviceNetworkId}/Light/${id_v1}")
            if (dev != null) log.warn "Device ${dev.displayName} with Hue V1 ID $id_v1 and V2 ID $id never converted to V2 DNI format"
         }
         if (dev != null) {
            dev.createEventsFromMapV2(data)
         }
      }
      if (device.currentValue("status") != "Online") doSendEvent("status", "Online")
   }
   catch (Exception ex) {
      log.error "Error parsing light states: ${ex}"
   }
}

void parseLightStatesV1(Map lightsJson) {
   if (logEnable) log.debug "parseLightStatesV1()"
   // Uncomment this line if asked to for debugging (or you're curious):
   //log.debug "lightsJson = $lightsJson"
   try {
      lightsJson.each { id, val ->
         DeviceWrapper device = parent.getChildDevice("${device.deviceNetworkId}/Light/${id}")
         if (device) {
            device.createEventsFromMapV1(val.state, true)
         }
      }
      if (device.currentValue("status") != "Online") doSendEvent("status", "Online")
   }
   catch (Exception ex) {
      log.error "Error parsing light states: ${ex}"
   }
}

void parseGroupStatesV2(List groupsJson) {
   if (logEnable) log.debug "parseGroupStatesV2()"
   // Uncomment this line if asked to for debugging (or you're curious):
   //log.debug "groupsJson = $groupsJson"
   try {
      groupsJson.each { Map data ->
         String id = data.id 
         String id_v1 = data.id_v1 - "/groups/"
         DeviceWrapper dev = parent.getChildDevice("${device.deviceNetworkId}/Group/${id}")
         if (dev == null) {
            dev = parent.getChildDevice("${device.deviceNetworkId}/Group/${id_v1}")
            if (dev != null) log.warn "Device ${dev.displayName} with Hue V1 ID $id_v1 and V2 ID $id never converted to V2 DNI format"
         }
         if (dev != null) {
            dev.createEventsFromMapV2(data)
         }
      }
      if (device.currentValue("status") != "Online") doSendEvent("status", "Online")
   }
   catch (Exception ex) {
      log.error "Error parsing group states: ${ex}"
   }
}

void parseGroupStatesV1(Map groupsJson) {
   if (logEnable) log.debug "parseGroupStatesV1()"
   // Uncomment this line if asked to for debugging (or you're curious):
   //log.debug "groupsJson = $groupsJson"
   try {
      groupsJson.each { id, val ->
         DeviceWrapper dev = parent.getChildDevice("${device.deviceNetworkId}/Group/${id}")
         if (dev) {
            dev.createEventsFromMapV1(val.action, true)
            dev.createEventsFromMapV1(val.state, true)
            dev.setMemberBulbIDs(val.lights)
         }
      }
      Boolean anyOn = groupsJson.any { it.value?.state?.any_on == true }
      DeviceWrapper allLightsDev = parent.getChildDevice("${device.deviceNetworkId}/Group/0")
      if (allLightsDev != null) {
         allLightsDev.createEventsFromMapV1(['any_on': anyOn], true)
      }
      
   }
   catch (Exception ex) {
      log.error "Error parsing group states: ${ex}"
   }
}

void parseSceneStatesV2(List scenesJson) {
   if (logEnable) log.debug "parseSceneStatesV2()"
   // Uncomment this line if asked to for debugging (or you're curious):
   //log.debug "scenesJson = $scenesJson"
   try {
      scenesJson.each { Map data ->
         String id = data.id 
         String id_v1 = data.id_v1 - "/scene/"
         DeviceWrapper dev = parent.getChildDevice("${device.deviceNetworkId}/Scene/${id}")
         if (dev == null) {
            dev = parent.getChildDevice("${device.deviceNetworkId}/Scene/${id_v1}")
            if (dev != null) log.warn "Device ${dev.displayName} with Hue V1 ID $id_v1 and V2 ID $id never converted to V2 DNI format"
         }
         if (dev != null) {
            dev.createEventsFromMapV2(data)
         }
      }
      if (device.currentValue("status") != "Online") doSendEvent("status", "Online")
   }
   catch (Exception ex) {
      log.error "Error parsing group states: ${ex}"
   }
}

void parseMotionSensorStatesV2(List sensorJson) {
   if (logEnable) log.debug "parseMotionSensorStatesV2()"
   // Uncomment this line if asked to for debugging (or you're curious):
   log.debug "sensorJson = $sensorJson"
   try {
      sensorJson.each { Map data ->
         String id = data.owner.rid // use owner ID for sensor to keep same physical devices together more easily 
         String id_v1 = data.id_v1 - "/sensors/"
         DeviceWrapper dev = parent.getChildDevice("${device.deviceNetworkId}/Sensor/${id}")
         if (dev == null) {
               dev = parent.getChildDevices().find { DeviceWrapper d ->
                  d.deviceNetworkId.startsWith("${device.deviceNetworkId}/Sensor/") &&
                  id_v1 in d.deviceNetworkId.tokenize('/')[-1].tokenize('|')
               }
            if (dev != null) log.warn "Device ${dev.displayName} with Hue V1 ID $id_v1 and V2 ID $id never converted to V2 DNI format"
         }
         if (dev != null) {
            dev.createEventsFromMapV2(data)
         }
      }
      if (device.currentValue("status") != "Online") doSendEvent("status", "Online")
   }
   catch (Exception ex) {
      log.error "Error parsing group states: ${ex}"
   }
}

private void parseMotionSensorStatesV1(Map sensorsJson) {
   if (logEnable) log.debug "Parsing sensor states from Bridge..."
   // Uncomment this line if asked to for debugging (or you're curious):
   // log.trace "sensorsJson = ${groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(sensorsJson))}"
   try {
      sensorsJson.each { key, val ->
         if (val.type == "ZLLPresence" || val.type == "ZLLLightLevel" || val.type == "ZLLTemperature" ||
          val.type == "ZHAPresence" || val.type == "ZHALightLevel" || val.type == "ZHATemperature") {
            DeviceWrapper sensorDev = parent.getChildDevices().findAll { DeviceWrapper it ->
               it.deviceNetworkId.startsWith("${device.deviceNetworkId}/Sensor/") &&
               (key as String) in it.deviceNetworkId.tokenize('/')[-1].tokenize('|')
            }[0]
            if (sensorDev != null) {
               sensorDev.createEventsFromMapV1(val.state)
               // All entries have config.battery, so just picking one to parse here to avoid redundancy:
               if (val.type == "ZLLPresence" || val.type == "ZHAPresence") sensorDev.createEventsFromMapV1(["battery": val.config.battery])
            }
         }
      }
   }
   catch (Exception ex) {
      log.error "Error parsing sensor states: ${ex}"
   }
}


// ------------ BULBS ------------

/** Requests list of all bulbs/lights from Hue Bridge; updates
 *  allBulbs in state when finished. Intended to be called
 *  during bulb discovery in app.
 */
void getAllBulbsV1() {
   if (logEnable) log.debug "Getting bulb list from Bridge..."
   //clearBulbsCache()
   Map<String,String> data = parent.getBridgeData()
   Map params = [
      uri: data.fullHost,
      path: "/api/${data.username}/lights",
      contentType: "application/json",
      timeout: 15
      ]
   asynchttpGet("parseGetAllBulbsResponseV1", params)
}

void parseGetAllBulbsResponseV1(resp, data) {
   if (logEnable) log.debug "parseGetAllBulbsResponseV1()"
   if (checkIfValidResponse(resp)) {
      try {
         Map bulbs = [:]
         resp.json.each { key, val ->
            bulbs[key] = [name: val.name, type: val.type]
         }
         state.allBulbs = bulbs
         if (logEnable) log.debug "  All bulbs received from Bridge: $bulbs"
      }
      catch (Exception ex) {
         log.error "Error parsing all bulbs response: $ex"
      }
   }
}

void getAllBulbsV2() {
   if (logEnable) log.debug "Getting bulb list from Bridge..."
   //clearBulbsCache()
   bridgeAsyncGetV2("parseGetAllBulbsResponseV2", "/resource/light")
}

void parseGetAllBulbsResponseV2(resp, Map data=null) {
   if (logEnable) log.debug "parseGetAllBulbsResponseV2()"
   if (checkIfValidResponse(resp)) {
      try {
         Map bulbs = [:]
         resp.json.data.each { Map bulbData ->
            bulbs[bulbData.id] = [name: bulbData.metadata.name, type: determineLightType(bulbData)]
         }
         state.allBulbs = bulbs
         if (logEnable) log.debug "  All bulbs received from Bridge: $bulbs"
      }
      catch (Exception ex) {
         log.error "Error parsing all bulbs response: $ex"
      }
   }
}


/** Intended to be called from parent app to retrive previously
 *  requested list of bulbs
 */
Map getAllBulbsCache() {
   return state.allBulbs 
}

/** Clears cache of bulb IDs/names/types; useful for parent app to call if trying to ensure
 * not working with old data
 */
void clearBulbsCache() {
   if (logEnable) log.debug "Running clearBulbsCache..."
   state.remove('allBulbs')
}

// ------------ GROUPS ------------

/** Requests list of all bulbs/lights from Hue Bridge; updates
 *  allBulbs in state when finished. Intended to be called
 *  during bulb discovery in app.
 */
void getAllGroupsV1() {
   if (logEnable) log.debug "Getting group list from Bridge..."
   //clearGroupsCache()
   Map<String,String> data = parent.getBridgeData()
   Map params = [
      uri: data.fullHost,
      path: "/api/${data.username}/groups",
      contentType: "application/json",
      timeout: 15
   ]
   asynchttpGet("parseGetAllGroupsResponseV1", params)
}

void parseGetAllGroupsResponseV1(resp, data) {
   if (logEnable) log.debug "Parsing in parseGetAllGroupsResponseV1"
   if (checkIfValidResponse(resp)) {
      try {
         Map groups = [:]
         resp.json.each { key, val ->
            groups[key] = [name: val.name, type: val.type]
         }
         groups[0] = [name: "All Hue Lights", type:  "LightGroup"] // add "all Hue lights" group, ID 0
         state.allGroups = groups
         if (logEnable) log.debug "  All groups received from Bridge: $groups"
      }
      catch (Exception ex) {
         log.error "Error parsing all groups response: $ex"
      }
   }
}

void getAllGroupsV2() {
   // sends two calls, one to /rooms and one to /zones
   // uses/grouped_light moslty to get "All Hue Lights" equivalent, as
   // the /room and /zone endpoints should cover all practical cases
   // and also get the name and other needed info for display
   // To use results in parent app: look at state.allRooms and state.allZones (not state.allGroups like v1)
   if (logEnable) log.debug "getAllGroupsV2()"
   //clearBulbsCache()
   Map<String,String> data = parent.getBridgeData()
   Map paramsGroupedLights = [
      uri: "https://${data.ip}",
      path: "/clip/v2/resource/grouped_light",
      headers: ["hue-application-key": data.username],
      contentType: "application/json",
      timeout: 15,
      ignoreSSLIssues: true
   ]
   Map paramsRooms = [
      uri: "https://${data.ip}",
      path: "/clip/v2/resource/room",
      headers: ["hue-application-key": data.username],
      contentType: "application/json",
      timeout: 15,
      ignoreSSLIssues: true
   ]
   Map paramsZones = [
      uri: "https://${data.ip}",
      path: "/clip/v2/resource/zone",
      headers: ["hue-application-key": data.username],
      contentType: "application/json",
      timeout: 15,
      ignoreSSLIssues: true
   ]
   asynchttpGet("parseGetAllGroupsOrRoomsOrZonesResponseV2", paramsGroupedLights, [type: "grouped_light"])
   asynchttpGet("parseGetAllGroupsOrRoomsOrZonesResponseV2", paramsRooms, [type: "room"])
   asynchttpGet("parseGetAllGroupsOrRoomsOrZonesResponseV2", paramsZones, [type: "zone"])
}

void parseGetAllGroupsOrRoomsOrZonesResponseV2(resp, Map<String,String> data) {
   if (logEnable) log.debug "parseGetAllRoomsResponseV2(), type = ${data.type}"
   if (checkIfValidResponse(resp)) {
      try {
         if (data.type == "room" || data.type == "zone") {
            Map roomsOrZones = [:]
            resp.json.data.each { Map roomOrZoneData ->
               String groupedLightId = roomOrZoneData.services.find({ svc -> svc.type == grouped_light })?.rid
               if (groupedLightId != null) {
                  roomsOrZones[roomOrZoneData.id] = [name: roomOrZoneData.metadata.name, groupedLightId: groupedLightId]
               }
               else {
                  if (logEnable) log.debug "No grouped_light service found for room ID ${roomOrZoneData.id}"
               }
            }
            if (data.type == "room") state.allRooms = roomsOrZones
            else state.allZones = roomsOrZones
         }
         else if (data.type == "grouped_light") {
            // really only use to find equivalent of group 0 in V1 API or "All Hue Lights"
            Map allHueLightsGroup = resp.json.data?.find { it.owner?.rtype == "bridge_home" }
            if (allHueLightsGroup != null) {
               state.allGroups = [:]
               state.allGroups[allHueLightsGroup.id] = [name: "All Hue Lights", type: "n/a"]
            }
         }
         else {
            log.warn "Unexpected type; should be \"room\", \"zone\", or \"grouped_light\": ${data.type}"
         }
         if (logEnable) log.debug "  All ${data.type}s received from Bridge: $roomsOrZones"
      }
      catch (Exception ex) {
         log.error "Error parsing all rooms or zones response: $ex"
      }
   }
}

/** Intended to be called from parent app to retrive previously
 *  requested list of groups
 */
Map getAllGroupsCache() {
   return state.allGroups
}

/** Clears cache of group IDs/names; useful for parent app to call if trying to ensure
 * not working with old data
 */
void clearGroupsCache() {
    if (logEnable) log.debug "Running clearGroupsCache..."
    state.remove("allGroups")
}

/** Intended to be called from parent app to retrive previously
 *  requested list of rooms
 */
Map getAllRoomsCache() {
   return state.allRooms
}

/** Clears cache of room IDs/names (and group light data inside); useful for parent app to call if trying to ensure
 * not working with old data
 */
void clearRoomsCache() {
    if (logEnable) log.debug "Running clearGroupsCache..."
    state.remove("allRooms")
}

/** Intended to be called from parent app to retrive previously
 *  requested list of zones
 */
Map getAllZonesCache() {
   return state.allZones
}

/** Clears cache of zone IDs/names (and group light data inside); useful for parent app to call if trying to ensure
 * not working with old data
 */
void clearZonesCache() {
    if (logEnable) log.debug "Running clearZonesCache..."
    state.remove("allZones")
}

// ------------ SCENES ------------

/** Requests list of all scenes from Hue Bridge; updates
 *  allScenes in state when finished. Intended to be called
 *  during bulb discovery in app.
 */
void getAllScenesV1() {
   if (logEnable) log.debug "getAllScenesV1()"
   getAllGroupsV1() // so can get room names, etc.
   //clearScenesCache()
   Map<String,String> data = parent.getBridgeData()
   Map params = [
      uri: data.fullHost,
      path: "/api/${data.username}/scenes",
      contentType: "application/json",
      timeout: 15
   ]
   asynchttpGet("parseGetAllScenesResponseV1", params)
}

void parseGetAllScenesResponseV1(resp, Map data=null) {
   if (logEnable) log.debug "parseGetAllScenesResponseV1()"
   if (checkIfValidResponse(resp)) {
      try {
         Map scenes = [:]
         resp.json.each { key, val ->
            scenes[key] = ["name": val.name]
            if (val.group) scenes[key] << ["group": val.group]
         }
         state.allScenes = scenes
         if (logEnable) log.debug "  All scenes received from Bridge: $scenes"
      }
      catch (Exception ex) {
         log.error "Error parsing all scenes response: ${ex}"   
      }
   }
}

void getAllScenesV2() {
   if (logEnable) log.debug "getAllScenesV2()"
   getAllGroupsV2() // so can get room names, etc.
   //clearScenesCache()
   bridgeAsyncGetV2("parseGetAllScenesResponseV2", "/resource/scene")
}

void parseGetAllScenesResponseV2(resp, Map data=null) {
   if (checkIfValidResponse(resp)) {
      try {
         Map scenes = [:]
         resp.json.data.each { Map sceneData ->
            scenes[sceneData.id] = [name: sceneData.metadata.name, group: sceneData.group?.rid]
         }
         state.allScenes = scenes
         if (logEnable) log.debug "  All scenes received from Bridge: $scenes"
      }
      catch (Exception ex) {
         log.error "Error parsing all scenes response: $ex"
      }
   }
}

/** Intended to be called from parent app to retrive previously
 *  requested list of scenes
 */
Map getAllScenesCache() {
   return state.allScenes
}

/** Clears cache of scene IDs/names; useful for parent app to call if trying to ensure
 * not working with old data
 */
void clearScenesCache() {
   if (logEnable) log.debug "Running clearScenesCache..."
   state.remove("allScenes")
}

// ------------ SENSORS (Motion/etc.) ------------
// No V1 for these

void getAllSensorsV2() {
   if (logEnable) log.debug "getAllSensorsV2()"
   // Seem to be able to get everything needed for discovery from /devices? Would need this for refresh/polling...
   // Map motionParams = [
   //    uri: "https://${data.ip}",
   //    path: "/clip/v2/resource/motion",
   //    headers: ["hue-application-key": data.username],
   //    contentType: "application/json",
   //    timeout: 15,
   //    ignoreSSLIssues: true
   // ]
   bridgeAsyncGetV2("parseGetAllSensorsResponseV2", "/resource/device")
}

void parseGetAllSensorsResponseV2(resp, Map data=null) {
   if (logEnable) log.debug "parseGetAllSensorsResponseV2()"
   if (checkIfValidResponse(resp)) {
      try {
         List<Map> motionDevs = resp.json?.data?.findAll { Map devData ->
            devData.services.any { svc -> svc.rtype == "motion"}
         }
         Map sensors = [:]
         motionDevs.each { Map devData ->
            sensors[devData.id] = devData.metadata.name
         }
         state.allSensors = sensors
         if (logEnable) log.debug "  All sensors received from Bridge: $sensors"
      }
      catch (Exception ex) {
         log.error "Error parsing all sensors response: $ex"
      }
   }
}

/** Intended to be called from parent app to retrive previously
 *  requested list of sensors
 */
Map<String,Map> getAllSensorsCache() {
   return state.allSensors
}

/** Clears cache of sensor IDs/names; useful for parent app to call if trying to ensure
 * not working with old data
 */
void clearSensorsCache() {
   if (logEnable) log.debug "Running clearSensorsCache..."
   state.remove("allSensors")
}

// ------------ BUTTONS ------------
// no V1 for these

/** Requests list of all button devices from Hue Bridge; updates
 *  allButtons in state when finished. Intended to be called
 *  during buttoon discovery in app.
 */
void getAllButtonsV1() {
   // TODO: Make this all V2 and remove from V1; only works with V2 anyway...
   if (logEnable) log.debug "getAllButtonsV1()"
   //clearButtonsCache()
   Map<String,String> data = parent.getBridgeData()
   Map params = [
      uri: "https://${data.ip}",
      path: "/clip/v2/resource/device",
      headers: ["hue-application-key": data.username],
      contentType: "application/json",
      timeout: 15,
      ignoreSSLIssues: true
   ]
   asynchttpGet("parseGetAllButtonsResponseV2", params)
}

void getAllButtonsV2() {
   if (logEnable) log.debug "getAllButtonsV2()"
   //clearButtonsCache()
   Map<String,String> data = parent.getBridgeData()
   Map params = [
      uri: "https://${data.ip}",
      path: "/clip/v2/resource/device",
      headers: ["hue-application-key": data.username],
      contentType: "application/json",
      timeout: 15,
      ignoreSSLIssues: true
   ]
   asynchttpGet("parseGetAllButtonsResponseV2", params)
}

void parseGetAllButtonsResponseV2(resp, data) {
   if (logEnable) log.debug "parseGetAllButtonsResponseV2()"
   if (checkIfValidResponse(resp)) {
      try {
         Map buttons = [:]
         // Get specific /button devices first....
         Map<String,String> bridgeData = parent.getBridgeData()
         // TODO: Consider making this async, but should be pretty safe considerng we just heard from Bridge...
         Map params = [
            uri: "https://${bridgeData.ip}",
            path: "/clip/v2/resource/button",
            headers: ["hue-application-key": bridgeData.username],
            contentType: "application/json",
            timeout: 10,
            ignoreSSLIssues: true
         ]
         httpGet(params,
            { response ->
                  response.data.data.each {
                     if (buttons[it.owner.rid] == null) buttons[it.owner.rid] = [buttons: [:]]
                     buttons[it.owner.rid].buttons << [(it.id): it.metadata.control_id]
                  }
            }
         )
         // Check for relative_rotary, too (Hue Tap Dial, Lutron Aurora)
         params = [
            uri: "https://${bridgeData.ip}",
            path: "/clip/v2/resource/relative_rotary",
            headers: ["hue-application-key": bridgeData.username],
            contentType: "application/json",
            timeout: 10,
            ignoreSSLIssues: true
         ]
         httpGet(params,
            { response ->
                  response.data.data.each {
                     if (buttons[it.owner.rid] != null) {
                        if (buttons[it.owner.rid].relative_rotary == null) {
                           buttons[it.owner.rid] << [relative_rotary: []]
                        }
                        buttons[it.owner.rid].relative_rotary << it.id
                     }
                     else {
                        // probably won't happen, but skip if no associated button
                     }
                  }
            }
         )
         // But also have to get name from /devices data...
         if (resp?.json?.data) {
            List devicesJson = resp.json.data
            buttons.keySet().each { String id ->
               Map dev = devicesJson.find { dev -> dev.id == id }
               buttons[id].name = dev.metadata.name
               buttons[id].manufacturer_name = dev.product_data.manufacturer_name
               buttons[id].model_id = dev.product_data.model_id
            }
         }
         else {
            log.warn "No data in returned JSON: $data"
         }
         state.allButtons = buttons
         //state.allRelativeRotaries = relativeRotaries
         if (logEnable) log.debug "  All buttons received from Bridge: $buttons"
      }
      catch (Exception ex) {
         log.error "Error parsing all buttons response: $ex"
      }
   }
}

/** Intended to be called from parent app to retrive previously
 *  requested list of bulbs
 */
Map getAllButtonsCache() {
   return state.allButtons 
}

/** Clears cache of bulb IDs/names/types; useful for parent app to call if trying to ensure
 * not working with old data
 */
void clearButtonsCache() {
   if (logEnable) log.debug "Running clearButtonsCache..."
   state.remove('allButtons')
}
