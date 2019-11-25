/**
 * ==========================  CoCoHue (Bridge Instance Child App) ==========================
 *
 *  DESCRIPTION:
 *  Community-developed Hue Bridge integration app for Hubitat, including support for lights,
 *  groups, and scenes.
 
 *  TO INSTALL:
 *  See documentation on Hubitat Community forum.
 *
 *  Copyright 2019 Robert Morris
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
 *  Last modified: 2019-11-25
 * 
 *  Changelog:
 * 
 *  1.0 - Initial Public Release
 *
 */ 

 // TODO: App doesn't fully install if user navigates away before clicking Done, even if already
 // added bulbs, etc.

definition(
    name: "CoCoHue (Bridge Instance Child App)",
    namespace: "RMoRobert",
    author: "Robert Morris",
    description: "Integrate Hue Bridge lights, groups, and scenes into Hubitat (use parent app to create instances)",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    iconX3Url: "",
    parent: "RMoRobert:CoCoHue - Hue Bridge Integration",
)

preferences {
    page(name: "pageFirstPage", content: "pageFirstPage")
    page(name: "pageAddBridge", content: "pageAddBridge")
    page(name: "pageLinkBridge", content: "pageLinkBridge")
    page(name: "pageManageBridge", content: "pageManageBridge")
    page(name: "pageSelectLights", content: "pageSelectLights")
    page(name: "pageSelectGroups", content: "pageSelectGroups")
    page(name: "pageSelectScenes", content: "pageSelectScenes")
}

/** Namespace to search/use for child device driver creation */
def getChildNamespace() {
    return "RMoRobert"
}

def installed() {
    log.info("Installed with settings: ${settings}")
    initialize()
}

def uninstalled() {
    log.info("Uninstalling")
}

def updated() {
    log.info("Updated with settings: ${settings}")
    initialize()
}

def initialize() {
    log.debug("Initializing...")
    unschedule()
    unsubscribe()    
    int disableTime = 1800
    if (enableDebug) {
        log.debug "Debug logging will be automatically disabled in ${disableTime} seconds"
        runIn(disableTime, debugOff)
    }
    def pollInt = settings["pollInterval"]?.toInteger()
    // If change polling options in UI, may need to modify some of these cases:
    switch (pollInt ?: 0) {
        case 0:
            logDebug("Polling disabled; not scheduling")
            break
        case 1..59:
            logDebug("Scheduling polling every ${pollInt} seconds")
            schedule("${Math.round(Math.random() * pollInt)}/${pollInt} * * ? * * *", "refreshBridge")
            break
        case 60..259:
            logDebug("Scheduling polling every 1 minute")
            runEvery1Minute("refreshBridge")
            break
        case 300..1800:
            logDebug("Schedulig polling every 5 minutes")
            runEvery5Minutes("refreshBridge")
            break
        default:
            logDebug("Scheduling polling every hour")
            runEvery1Hour("refreshBridge")                
    }
}

def debugOff() {
    log.warn("Disabling debug logging")
    app.updateSetting("enableDebug", [value:"false", type:"bool"])
}

def pageFirstPage() {
    return (state.bridgeLinked ? pageManageBridge() : pageAddBridge())
}

def pageAddBridge() {
    dynamicPage(name: "pageAddBridge", uninstall: true, install: false, nextPage: pageLinkBridge) {
        state.authRefreshInterval = 4
        state.authTryCount = 0
        section("Add Hue Bridge") {
            input(name: "bridgeIP", type: "string", title: "Hue Bridge IP address:", required: false, defaultValue: null, submitOnChange: true)            
            if (settings["bridgeIP"] && state.bridgeLinked) {
                input(name: "boolForceCreateBridge", type: "bool", title: "Force recreation of Bride child device (WARNING: will un-link any " +
                      "existing Bridge child device from this child app if one still exists)", submitOnChange: true)
            }
            if (settings["bridgeIP"] && !state.bridgeLinked || settings["boolForceCreateBridge"]) {
                paragraph("Press the button on your Hue Bridge, then click/tap the \"Next\" button to continue.")
            }
        }           
    }
}

def pageLinkBridge() {
    def authMaxTries = 20
    if (settings["boolForceCreateBridge"]) {
        state.remove("bridge")
        app.updateSetting("boolForceCreateBridge", false)
    }
    
    dynamicPage(name: "pageLinkBridge", refreshInterval: state.authRefreshInterval, uninstall: true, install: false, nextPage: "pageManageBridge") {  
        section("Linking Hue Bridge") {
            if (!(state["bridgeAuthorized"])) {
                log.debug("Attempting Hue Bridge authorization; attempt number ${state.authTryCount+1}")
                sendUsernameRequest()
                state.authTryCount = state.authTryCount + 1
                paragraph("Waiting for Bridge to authorize. This page will automatically refresh.")
                if (state.authTryCount > 5 && state.authTryCount < authMaxTries) {
                    def strParagraph = "Still waiting for authorization. Please make sure you pressed " +
                        "the button on the Hue Bridge."
                    if (state.authTryCount > 10) {
                        strParagraph + "Also, verify that your Bridge IP address is correct: ${settings["bridgeIP"]}"
                    }
                    paragraph(strParagraph)
                }
                if (state.authTryCount >= authMaxTries) {
                    paragraph("<b>Authorization timed out. Please go back to the previous page, check your settings, " +
                              "and try again.</b>")
                }                
            }
            else {
                if (!state.bridgeLinked) {
                    logDebug("Hue Bridge authorized. Requesting info from Bridge and creating Bridge device...")
                    sendBridgeInfoRequest()
                } else {
                    logDebug("Bridge device already exits; skipping creation")
                }
                paragraph("Your Hue Bridge has been linked! Press \"Next\" to return to the app for discovery.")
            }
        }
    }
}

def pageManageBridge() {
    if (settings["newBulbs"]) {
        createNewSelectedBulbDevices()
    }
    if (settings["newGroups"]) {
        createNewSelectedGroupDevices()
    }    
    dynamicPage(name: "pageManageBridge", uninstall: true, install: true) {  
        section("Manage Hue Bridge Devices"/*, hideable: true, hidden: false*/) {
            href(name: "hrefSelectLights", title: "Select Lights",
                description: "", page: "pageSelectLights")
            href(name: "hrefSelectGroups", title: "Select Groups",
                description: "", page: "pageSelectGroups")
            href(name: "hrefSelectScenes", title: "Select Scenes",
                description: "", page: "pageSelectScenes")
        }
        section("Bridge Device Options", hideable: true, hidden: true) {
            input(name: "pollInterval", type: "enum", title: "Poll bridge every...",
               options: [0:"Disabled", 10:"10 seconds", 30:"30 seconds", 60:"1 minute (recommended)", 300:"5 minutes", 3600:"1 hour"], defaultValue:60)
            href(name: "hrefAddBridge", title: "Edit Bridge IP or re-authorize",
                 description: "", page: "pageAddBridge")
            input(name: "enableDebug", type: "bool", title: "Enable debug logging", defaultValue: true)
        }
    }
}

def pageSelectLights() {   
    dynamicPage(name: "pageSelectLights", refreshInterval: refreshInt, uninstall: true, nextPage: pageManageBridge) {
        state.addedBulbs = [:]  // To be populated with lights user has added, matched by Hue ID
        def bridge = getChildDevice("CCH/${state.bridgeID}")
        if (!bridge) {
            log.error "No Bridge device found"
            return
        }
        bridge.getAllBulbs()
        def refreshInt = 10
        def enumNewBulbs = [:]
        def bulbCache = bridge.getAllBulbsCache()
        if (bulbCache) {
            refreshInt = 0
            bulbCache.each {
                def bulbChild = getChildDevice("CCH/${state.bridgeID}/Light/${it.key}")
                if (bulbChild) {
                    state.addedBulbs.put(it.key, bulbChild.name)
                } else {
                    enumNewBulbs.put(it.key, it.value.name)
                }
            }
            enumNewBulbs = enumNewBulbs.sort { it.value }  // doesn't work to display this way, but maybe can figure out something else?
        }
        if (!bulbCache) {            
            refreshInt = 10
            section("Discovering bulbs/lights. Please wait...") {            
                paragraph("Press \"Refresh\" if you see this message for an extended period of time")
                input(name: "btnBulbRefresh", type: "button", title: "Refresh", submitOnChange: true)
            }
        }
        else {
            section("Manage Lights") {
                input(name: "newBulbs", type: "enum", title: "Select Hue lights to add:",
                      multiple: true, options: enumNewBulbs)
            }
            section("Previously added lights") {
                if (state.addedBulbs) {
                    state.addedBulbs.each {
                        paragraph(it.value)
                    }
                }
                else {
                    paragraph("No bulbs added")
                }
            }
            section("Rediscover Bulbs") {
                paragraph("If you added new lights to the Hue Bridge and do not see them above, click/tap the button " +
                          "below to retrieve new information from the Bridge.")
                input(name: "btnBulbRefresh", type: "button", title: "Refresh Bulb List", submitOnChange: true)
            }
        }
    }    
}

def pageSelectGroups() {
    dynamicPage(name: "pageSelectGroups", refreshInterval: refreshInt, uninstall: true, nextPage: pageManageBridge) {
        state.addedGroups = [:]  // To be populated with groups user has added, matched by Hue ID
        def bridge = getChildDevice("CCH/${state.bridgeID}")
        if (!bridge) {
            log.error "No Bridge device found"
            return
        }
        bridge.getAllGroups()
        def refreshInt = 10
        def enumNewGroups = [:]
        def groupCache = bridge.getAllGroupsCache()
        if (groupCache) {
            refreshInt = 0
            groupCache.each {
                def groupChild = getChildDevice("CCH/${state.bridgeID}/Group/${it.key}")
                if (groupChild) {
                    state.addedGroups.put(it.key, groupChild.name)
                } else {
                    enumNewGroups.put(it.key, it.value.name)
                }
            }
        }
        if (!groupCache) {            
            refreshInt = 10
            section("Discovering groups. Please wait...") {            
                paragraph("Press \"Refresh\" if you see this message for an extended period of time")
                input(name: "btnGroupRefresh", type: "button", title: "Refresh", submitOnChange: true)
            }
        }
        else {
            section("Manage Groups") {
                input(name: "newGroups", type: "enum", title: "Select Hue groups to add:",
                      multiple: true, options: enumNewGroups)
            }
            section("Previously added groups") {
                if (state.addedGroups) {
                    state.addedGroups.each {
                        paragraph(it.value)
                    }
                }
                else {
                    paragraph("No groups added")
                }
            }
            section("Rediscover Groups") {
                paragraph("If you added new groups to the Hue Bridge and do not see them above, click/tap the button " +
                          "below to retrieve new information from the Bridge.")
                input(name: "btnGroupRefresh", type: "button", title: "Refresh Group List", submitOnChange: true)
            }
        }
    }    
}

def pageSelectScenes() {
    dynamicPage(name: "pageSelectScenes", uninstall: true, nextPage: pageManageBridge) {  
        section("Select Scenes") {
            paragraph("Coming soon!")
        }
    }    
}

/** Creates new Hubitat devices for new user-selected bulbs on lights-selection
 * page (intended to be called after navigating away/using "Done" from that page)
 */
def createNewSelectedBulbDevices() {
    // TODO: Change most of these when new drivers made
    def driverMap = ["extended color light": "CoCoHue RGBW Bulb",
                     "color light": "CoCoHue RGBW Bulb",            
                     "color temperature light": "CoCoHue RGBW Bulb",
                     "dimmable light": "CoCoHue RGBW Bulb",
                     "DEFAULT": "CoCoHue RGBW Bulb"]
    def bridge = getChildDevice("CCH/${state.bridgeID}")
    if (!bridge) log.error("Unable to find bridge device")
    def bulbCache = bridge?.getAllBulbsCache()
    settings["newBulbs"].each {
        def b = bulbCache.get(it)
        if (b) {
            try {
                logDebug("Creating new device for Hue light ${it} (${b.name})")
                def devDriver = driverMap[b.type.toLowerCase()] ?: driverMap["DEFAULT"]
                def devDNI = "CCH/${state.bridgeID}/Light/${it}"
                def devProps = [name: b.name]
                addChildDevice(getChildNamespace(), devDriver, devDNI, null, devProps)

            } catch (Exception ex) {
                log.error("Unable to create new device for $it: $ex")
            }
        } else {
            log.error("Unable to create new device for bulb $it: ID not found on Hue Bridge")
        }
    }    
    bridge.clearBulbsCache()
    bridge.getAllBulbs()
    app.removeSetting("newBulbs")
}

/** Creates new Hubitat devices for new user-selected groups on groups-selection
 * page (intended to be called after navigating away/using "Done" from that page)
 */
def createNewSelectedGroupDevices() {
    // TODO: Change most of these when new drivers made
    def driverName = "CoCoHue Group"
    def bridge = getChildDevice("CCH/${state.bridgeID}")
    if (!bridge) log.error("Unable to find bridge device")
    def groupCache = bridge?.getAllGroupsCache()
    settings["newGroups"].each {
        def g = groupCache.get(it)
        if (g) {
            try {
                logDebug("Creating new device for Hue group ${it} (${g.name})")
                def devDNI = "CCH/${state.bridgeID}/Group/${it}"
                def devProps = [name: g.name]
                addChildDevice(getChildNamespace(), driverName, devDNI, null, devProps)

            } catch (Exception ex) {
                log.error("Unable to create new group device for $it: $ex")
            }
        } else {
            log.error("Unable to create new device for group $it: ID not found on Hue Bridge")
        }
    }    
    bridge.clearGroupsCache()
    bridge.getAllGroups()
    app.removeSetting("newGroups")
}

/** Sends request for username creation to Bridge API. Intended to be called after user
 *  presses link button on Bridge
 */
private sendUsernameRequest() {
    def userDesc = location.name ? "Hubitat CoCoHue#${location.name}" : "Hubitat CoCoHue"
    def host = settings["bridgeIP"] + ":80"
    sendHubCommand(new hubitat.device.HubAction([
        method: "POST",
        path: "/api",
        headers: [HOST: host],
        body: [devicetype: userDesc]
        ], null, [callback: "parseUsernameResponse"])
    )
}

/** Callback for sendUsernameRequest. Saves username in app state if Bridge is
 * successfully authorized, or logs error if unable to do so.
 */
def parseUsernameResponse(hubitat.device.HubResponse resp) {
    def body = resp.json
    logDebug("Attempting to request Hue Bridge username; result = ${body}")
    
    if (body.success != null) {
        if (body.success[0] != null) {
            if (body.success[0].username) {
                state["username"] = body.success[0].username
                state["bridgeAuthorized"] = true
            }
        }
    }
    else {
        if (body.error != null) {
            log.warn("Error from Bridge: ${body.error}")
        }
        else {
            log.error("Unknown error adding Hue Bridge")
        }
    }
}

/** Requests Bridge info (description.xml) to verify that device is a
 *  Hue Bridge and to retrive (when parsed in parseBridgeInfoRequest)
 *  information necessary to create the Bridge device
 */
private sendBridgeInfoRequest() {
    logDebug("Sending request for Bridge information")
    def host = settings["bridgeIP"] + ":80"
    sendHubCommand(new hubitat.device.HubAction([
        method: "GET",
        path: "/description.xml",
        headers: [  HOST: host  ],
        body: []], null, [callback: "parseBridgeInfoResponse"])
    )
}

/** Parses response from GET of description.xml on the Bridge;
 *  verifies that device is a Hue Bridge (modelName contains "Philips Hue Bridge")
 * and obtains MAC address for use in creating Bridge DNI and device name
 */
private parseBridgeInfoResponse(hubitat.device.HubResponse resp) {
    logDebug("Parsing response from Bridge information request")
    def body = resp.xml
    if (body?.device?.modelName?.text().contains("Philips hue bridge")) {
        state.serial
        def serial = body?.device?.serialNumber?.text()
        if (serial) {
            logDebug("Hue Bridge serial parsed as ${serial}; creating device")
            state.bridgeID = serial.reverse().take(6).reverse().toUpperCase() // last 6 of MAC
            def bridgeDevice = addChildDevice("RMoRobert", "CoCoHue Bridge", "CCH/${state.bridgeID}", null,
                                              [label: "CoCoHue Bridge (${state.bridgeID})", name: "CoCoHue Bridge"])
            state.bridgeLinked = true
        } else {
            log.error("Unexpected response received from Hue Bridge")
        } 
    } else {
        log.error("No Hue Bridge found at IP address")
    }
}

/** Returns map containing Bridge username, IP, and full HTTP post/port, intended to be
 *  called by child devices so they can send commands to the Hue Bridge API using info
 */
def getBridgeData() {
    logDebug("Running getBridgeData()...")
    if (!state["username"] || !settings["bridgeIP"]) log.error "Missing username or IP address from Bridge"
    def map = [username: state["username"], host: settings["bridgeIP"] + ":80", fullHost: "http://${settings['bridgeIP']}:80"]
    return map
}

/** Calls refresh() method on Bridge child, intended to be called at user-specified
 *  polling interval
 */
private refreshBridge() {
    def bridge = getChildDevice("CCH/${state.bridgeID}")
    if (!bridge) {
            log.error "No Bridge device found; could not refresh/poll"
            return
    }
    logDebug("Polling Bridge...")
    bridge.refresh()
}

def appButtonHandler(btn) {
    switch(btn) {
        case "btnBulbRefresh":
        case "btnGroupRefresh":
            // Just want to resubmit page, so nothing
            break
        default:
            log.warn "Unhandled app button press: $btn"
    }
}

def logDebug(str) {
    if (enableDebug) log.debug(str)
}