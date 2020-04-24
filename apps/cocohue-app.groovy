/**
 * ===========================  CoCoHue - Hue Bridge Integration =========================
 *
 *  DESCRIPTION:
 *  Community-developed Hue Bridge integration app for Hubitat, including support for lights,
 *  groups, and scenes.
 
 *  TO INSTALL:
 *  See documentation on Hubitat Community forum.
 *
 *  Copyright 2019-2020 Robert Morris
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
 *  Last modified: 2020-04-23
 *  Version: 2.0.0-beta.1
 * 
 *  Changelog:
 *
     // TODO: Reduce amount of child->parent calls, push changes in parent to child devices when needed (IP address, etc.)?

 *  v2.0   - New non-parent/child structure and name change; Bridge discovery; added documentaiton links
 *           Additiononal device features
 *  v1.9   - Added CT and dimmable bulb types
 *  v1.7   - Addition of new child device types, updating groups from member bulbs
 *  v1.6   - Added options for bulb and group deivce naming
 *  v1.5   - Added scene integration
 *  v1.1   - Added more polling intervals
 *  v1.0   - Initial Public Release
 */ 

import groovy.transform.Field

@Field static String childNamespace = "RMoRobert" // namespace of child device drivers

@Field static Map driverMap = [ "extended color light":     "CoCoHue RGBW Bulb",
                                "color light":              "CoCoHue RGBW Bulb",            
                                "color temperature light":  "CoCoHue CT Bulb",
                                "dimmable light":           "CoCoHue Dimmable Bulb",
                                "on/off light":             "CoCoHue On/Off Plug",
                                "on/off plug-in unit":      "CoCoHue On/Off Plug",
                                "DEFAULT":                  "CoCoHue RGBW Bulb"
                              ]

definition (
    name: "CoCoHue - Hue Bridge Integration",
    namespace: "RMoRobert",
    author: "Robert Morris",
    description: "Community-created Philips Hue integration for Hue Bridge lights, groups, and scenes",
    category: "Convenience",
    documentationLink: "https://community.hubitat.com/t/release-cocohue-hue-bridge-integration-including-scenes/27978",
    iconUrl: "",
    iconX2Url: "",
    iconX3Url: "",
    //Uncomment the following line if upgrading from existing CoCoHue 1.x installation:
    //parent: "RMoRobert:CoCoHue (Parent App)",
)

preferences {
    page(name: "pageFirstPage", content: "pageFirstPage")
    page(name: "pageIncomplete", content: "pageIncomplete")
    page(name: "pageAddBridge", content: "pageAddBridge")
    page(name: "pageLinkBridge", content: "pageLinkBridge")
    page(name: "pageBridgeLinked", content: "pageBridgeLinked")
    page(name: "pageManageBridge", content: "pageManageBridge")
    page(name: "pageSelectLights", content: "pageSelectLights")
    page(name: "pageSelectGroups", content: "pageSelectGroups")
    page(name: "pageSelectScenes", content: "pageSelectScenes")
}

def installed() {
    log.info("Installed with settings: ${settings}")
    initialize()
}

def uninstalled() {
    log.info("Uninstalling")
    if (!(settings['deleteDevicesOnUninstall'] == false)) {
        log.debug("Deleting child devices of this CoCoHue instance...")
        getChildDevices().each { child ->
            logDebug("Deleting $child")
            // Why does this not seem to actually work for me...
            deleteChildDevice(child.getDeviceNetworkId)
        }
    }
}

def updated() {
    log.info("Updated with settings: ${settings}")
    initialize()
}

def initialize() {
    log.debug("Initializing...")
    unschedule()
    state.remove('discoveredBridges')
    if (settings["useSSDP"] == true || settings["useSSDP"] == null) {
        log.debug("Subscribing to ssdp...")
        subscribe(location, "ssdpTerm.urn:schemas-upnp-org:device:basic:1", ssdpHandler)
    }
    else {
        unsubscribe() // remove or modify if ever subscribe to more than SSDP
    }

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
    if (app.getInstallationState() == "INCOMPLETE") {
        return pageIncomplete()
    } else {
        if (state.bridgeLinked) {
            return pageManageBridge()
        }
        else {
            state.authRefreshInterval = 10
            state.discoTryCount = 0
            state.authTryCount = 0
            return pageAddBridge()
        }
    }
}

def pageIncomplete() {
    dynamicPage(name: "pageIncomplete", uninstall: true, install: true) {
        section() {
            paragraph("Please press \"Done\" to install CoCoHue.<br>Then, re-open to set up your Hue Bridge.")
        }
    }
}

def pageAddBridge() {
    logDebug("pageAddBridge()...")
    Integer discoMaxTries = 60
    state.discoTryCount += 1
    if (settings["useSSDP"] == true || settings["useSSDP"] == null && state.discoTryCount < 5) {
        logDebug("Subscribing to and sending SSDP discovery...")
        subscribe(location, "ssdpTerm.urn:schemas-upnp-org:device:basic:1", ssdpHandler)
        sendHubCommand(new hubitat.device.HubAction("lan discovery ssdpTerm.urn:schemas-upnp-org:device:basic:1", hubitat.device.Protocol.LAN))
    }
    dynamicPage(name: "pageAddBridge", uninstall: true, install: false, refreshInterval: state.authRefreshInterval, nextPage: pageLinkBridge) {
        section("Add Hue Bridge") {
            input(name: "useSSDP", type: "bool", title: "Discover Hue Bridges automatically", defaultValue: true, submitOnChange: true)
            if (settings["useSSDP"] == true || settings["useSSDP"] == null) {
                if (!(state.discoveredBridges)) {
                    paragraph("Please wait while Hue Bridges are discovered...")
                }
                else {
                    input(name: "selectedDiscoveredBridge", type: "enum", title: "Discovered bridges:", options: state.discoveredBridges,
                          multiple: false)
                    paragraph("Select Bridge above to add, then <strong>press the button on your Bridge</strong> and click/tap \"Next\" to continue.")
                }
                if (state.discoTryCount > discoMaxTries) {
                    state.remove('authRefreshInterval')
                    paragraph("No bridges have been found. Please go back and try again, or consider using manual setup.")
                }
            } else { 
                unsubscribe()  // remove or modify if ever subscribe to more than SSDP (above)
                input(name: "bridgeIP", type: "string", title: "Hue Bridge IP address:", required: false, defaultValue: null, submitOnChange: true)            
                if (settings['bridgeIP'] && state.bridgeLinked) {
                    input(name: "boolForceCreateBridge", type: "bool", title: "Force recreation of Bride child device (WARNING: will un-link any " +
                        "existing Bridge child device from this child app if one still exists)", submitOnChange: true)
                }
                if (settings['bridgeIP'] && !state.bridgeLinked || settings['boolForceCreateBridge']) {
                    paragraph("<strong>Press the button on your Hue Bridge,</strong> then click/tap \"Next\" to continue.")
                }
            }
        }
    }
}

def pageLinkBridge() {
    logDebug("Beginning brdige link process...")
    String ipAddress = (settings['useSSDP'] != false) ? settings['selectedDiscoveredBridge'] : settings['bridgeIP']
    state.ipAddress = ipAddress
    logDebug("  IP address = ${state.ipAddress}")
    Integer authMaxTries = 20
    if (settings['boolForceCreateBridge']) {
        app.updateSetting('boolForceCreateBridge', false)
        state.remove('bridgeAuthorized')
    }
    if (!(settings['useSSDP'] == false)) {
        if (!(settings['selectedDiscoveredBridge'])) {
            dynamicPage(name: "pageLinkBridge", uninstall: true, install: false, nextPage: "pageAddBridge") {
                paragraph('No Bridge selected. Click "Next" to return to the bridge selection page, and try again.')

            }
        }
    }    
    dynamicPage(name: "pageLinkBridge", refreshInterval: state.authRefreshInterval, uninstall: true, install: false, nextPage: "pageBridgeLinked") {  
        section("Linking Hue Bridge") {
            if (!(state["bridgeAuthorized"])) {
                log.debug("Attempting Hue Bridge authorization; attempt number ${state.authTryCount+1}")
                sendUsernameRequest()
                state.authTryCount += 1
                paragraph("Waiting for Bridge to authorize. This page will automatically refresh.")
                if (state.authTryCount > 5 && state.authTryCount < authMaxTries) {
                    def strParagraph = "Still waiting for authorization. Please make sure you pressed " +
                        "the button on the Hue Bridge."
                    if (state.authTryCount > 10) {
                        strParagraph + "Also, verify that your Bridge IP address is correct: ${state.ipAddress}"
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
                    log.debug("Hue Bridge authorized. Requesting info from Bridge and creating Bridge device...")
                    sendBridgeInfoRequest()
                } else {
                    logDebug("Bridge device already exits; skipping creation")
                }
                paragraph("<b>Your Hue Bridge has been linked!</b> Press \"Next\" to continue.")
            }
        }
    }
}

def pageBridgeLinked() {
    dynamicPage(name: "pageBridgeLinked", uninstall: true, install: false, nextPage: pageFirstPage) {
        state.authRefreshInterval = 4
        state.authTryCount = 0
        if (state["bridgeAuthorized"] && state["bridgeLinked"]) {
            state.remove('discoveredBridges')
            section("Bridge Linked") {
                paragraph("""\
                          Your Hue Bridge has been successfully linked to Hubitat. Press "Next" to
                          begin adding lights, groups, or scenes.
                          """.stripIndent())
            }
        }
        else {
            section("Bridge Not Linked") {
                paragraph("There was a problem authorizing or linking your Hue Bridge. Please start over and try again.")
            }
        }
    }
}         

def pageManageBridge() {
    if (settings["newBulbs"]) {
        logDebug("New bulbs selected. Creating...")
        createNewSelectedBulbDevices()
    }
    if (settings["newGroups"]) {
        logDebug("New groups selected. Creating...")
        createNewSelectedGroupDevices()
    }
    if (settings["newScenes"]) {
        logDebug("New scenes selected. Creating...")
        createNewSelectedSceneDevices()
    }
    dynamicPage(name: "pageManageBridge", uninstall: true, install: true) {  
        section("Manage Hue Bridge Devices") {
            href(name: "hrefSelectLights", title: "Select Lights",
                description: "", page: "pageSelectLights")
            href(name: "hrefSelectGroups", title: "Select Groups",
                description: "", page: "pageSelectGroups")
            href(name: "hrefSelectScenes", title: "Select Scenes",
                description: "", page: "pageSelectScenes")
        }
        section("Advanced Options", hideable: true, hidden: true) {
            href(name: "hrefAddBridge", title: "Edit Bridge IP or re-discover",
                 description: "", page: "pageAddBridge")
            input(name: "showAllScenes", type: "bool", title: "Allow adding scenes not associated with rooms/zones (not recommended; devices will not support \"off\" command)")
            input(name: "deleteDevicesOnUninstall", type: "bool", title: "Delete devices created by app (Bridge, light, group, and scene) if uninstalled", defaultValue: true)
        }        
        section("Other Options", hideable: true, hidden: false) {
            input(name: "pollInterval", type: "enum", title: "Poll bridge every...",
               options: [0:"Disabled", 10:"10 seconds", 15:"15 seconds", 20:"20 seconds", 30:"30 seconds", 60:"1 minute (recommended)", 300:"5 minutes", 3600:"1 hour"], defaultValue:60)
            input(name: "boolCustomLabel", type: "bool", title: "Customize the name of this CoCoHue app instance", defaultValue: false, submitOnChange: true)
            if (settings['boolCustomLabel']) label(title: "Custom name for this app", required: false)
            input(name: "enableDebug", type: "bool", title: "Enable debug logging", defaultValue: true)
        }
    }
}

def pageSelectLights() {   
    dynamicPage(name: "pageSelectLights", refreshInterval: refreshInt, uninstall: true, install: false, nextPage: pageManageBridge) {
        state.addedBulbs = [:]  // To be populated with lights user has added, matched by Hue ID
        def bridge = getChildDevice("CCH/${state.bridgeID}")
        if (!bridge) {
            log.error "No Bridge device found"
            return
        }
        bridge.getAllBulbs()
        def refreshInt = 10
        def arrNewBulbs = []
        def bulbCache = bridge.getAllBulbsCache()
        if (bulbCache) {
            refreshInt = 0
            bulbCache.each {
                def bulbChild = getChildDevice("CCH/${state.bridgeID}/Light/${it.key}")
                if (bulbChild) {
                    state.addedBulbs.put(it.key, bulbChild.name)
                } else {
                    def newBulb = [:]
                    newBulb << [(it.key): (it.value.name)]
                    arrNewBulbs << newBulb
                }
            }
            arrNewBulbs = arrNewBulbs.sort { a, b ->
                // Sort by bulb name (default would be hue ID)
                a.entrySet().iterator().next()?.value <=> b.entrySet().iterator().next()?.value
            }
            state.addedBulbs = state.addedBulbs.sort { it.value }
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
                      multiple: true, options: arrNewBulbs)
                input(name: "boolAppendBulb", type: "bool", title: "Append \"(Hue Light)\" to Hubitat device name")
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
    dynamicPage(name: "pageSelectGroups", refreshInterval: refreshInt, uninstall: true, install: false, nextPage: pageManageBridge) {
        state.addedGroups = [:]  // To be populated with groups user has added, matched by Hue ID
        def bridge = getChildDevice("CCH/${state.bridgeID}")
        if (!bridge) {
            log.error "No Bridge device found"
            return
        }
        bridge.getAllGroups()
        def refreshInt = 10
        def arrNewGroups = []
        def groupCache = bridge.getAllGroupsCache()

        if (groupCache) {
            refreshInt = 0
            groupCache.each {
                def groupChild = getChildDevice("CCH/${state.bridgeID}/Group/${it.key}")
                if (groupChild) {
                    state.addedGroups.put(it.key, groupChild.name)
                } else {
                    def newGroup = [:]
                    newGroup << [(it.key): (it.value.name)]
                    arrNewGroups << newGroup
                }
            }
            arrNewGroups = arrNewGroups.sort {a, b ->
                // Sort by group name (default would be Hue ID)
                a.entrySet().iterator().next()?.value <=> b.entrySet().iterator().next()?.value
                }
            state.addedGroups = state.addedGroups.sort { it.value }
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
                      multiple: true, options: arrNewGroups)
                input(name: "boolAppendGroup", type: "bool", title: "Append \"(Hue Group)\" to Hubitat device name")
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
    dynamicPage(name: "pageSelectScenes", uninstall: true, install: false, nextPage: pageManageBridge) {  
        state.addedScenes = [:]  // To be populated with scenes user has added, matched by Hue ID
        def bridge = getChildDevice("CCH/${state.bridgeID}")
        if (!bridge) {
            log.error "No Bridge device found"
            return
        }
        bridge.getAllScenes()
        def refreshInt = 10
        def arrNewScenes = []
        def sceneCache = bridge.getAllScenesCache()

        def groupCache = bridge.getAllGroupsCache()
        def grps = [:]
        groupCache?.each { grps << [(it.key) : (it.value.name)] }

        if (sceneCache) {
            refreshInt = 0
            state.sceneFullNames = [:]
            sceneCache.each { sc ->
                def sceneChild = getChildDevice("CCH/${state.bridgeID}/Scene/${sc.key}")
                if (sceneChild) {
                    state.addedScenes.put(sc.key, sceneChild.name)
                } else {
                    def newScene = [:]
                    def sceneName = sc.value.name
                    if (sc.value.group) {
                        grps.each { g ->
                            def k = g.key
                            if (k && k == sc.value.group) {
                                def v = g.value
                                // "Group Name - Scene Name" naming convention:
                                if (v) sceneName = "$v - $sceneName"
                                }
                            }
                        }
                        if (sc.value?.group || settings["showAllScenes"]) {
                            state.sceneFullNames.put(sc.key, sceneName)
                            newScene << [(sc.key): (sceneName)]                        
                            arrNewScenes << newScene
                        }
                    }
                }
            arrNewScenes = arrNewScenes.sort {a, b ->
                // Sort by group name (default would be Hue ID)
                a.entrySet().iterator().next()?.value <=> b.entrySet().iterator().next()?.value
            }
            state.addedScenes = state.addedScenes.sort { it.value }
        }

        if (!sceneCache) {            
            refreshInt = 10
            section("Discovering scenes. Please wait...") {            
                paragraph("Press \"Refresh\" if you see this message for an extended period of time")
                input(name: "btnSceneRefresh", type: "button", title: "Refresh", submitOnChange: true)
            }
        }
        else {
            section("Manage Scenes") {
                input(name: "newScenes", type: "enum", title: "Select Hue scenes to add:",
                      multiple: true, options: arrNewScenes)
            }
            section("Previously added scenes") {
                if (state.addedScenes) {
                    state.addedScenes.each {
                        paragraph(it.value)
                    }
                }
                else {
                    paragraph("No scenes added")
                }
            }
            section("Rediscover Scenes") {
                paragraph("If you added new scenes to the Hue Bridge and do not see them above, if room/zone names are " +
                          "missing from scenes (if assigned to one), or if you changed the \"Include scenes...\" setting above, " +
                          "click/tap the button below to retrieve new information from the Bridge.")
                input(name: "btnSceneRefresh", type: "button", title: "Refresh Scene List", submitOnChange: true)
            }
        }
    }     
}

/** Creates new Hubitat devices for new user-selected bulbs on lights-selection
 * page (intended to be called after navigating away/using "Done" from that page)
 */
void createNewSelectedBulbDevices() {
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
                def devProps = [name: (settings["boolAppendBulb"] ? b.name + " (Hue Bulb)" : b.name)]
                addChildDevice(childNamespace, devDriver, devDNI, null, devProps)

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
void createNewSelectedGroupDevices() {
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
                def devProps = [name: (settings["boolAppendGroup"] ? g.name + " (Hue Group)" : g.name)]
                addChildDevice(childNamespace, driverName, devDNI, null, devProps)

            } catch (Exception ex) {
                log.error("Unable to create new group device for $it: $ex")
            }
        } else {
            log.error("Unable to create new device for group $it: ID not found on Hue Bridge")
        }
    }    
    bridge.clearGroupsCache()
    bridge.getAllGroups()
    bridge.refresh()
    app.removeSetting("newGroups")
}


/** Creates new Hubitat devices for new user-selected scenes on scene-selection
 * page (intended to be called after navigating away/using "Done" from that page)
 */
def createNewSelectedSceneDevices() {
    def driverName = "CoCoHue Scene"
    def bridge = getChildDevice("CCH/${state.bridgeID}")
    if (!bridge) log.error("Unable to find bridge device")
    def sceneCache = bridge?.getAllScenesCache()
    settings["newScenes"].each {
        def sc = sceneCache.get(it)
        if (sc) {
            try {
                logDebug("Creating new device for Hue group ${it}" +
                         " (state.sceneFullNames?.get(it) ?: sc.name)")
                def devDNI = "CCH/${state.bridgeID}/Scene/${it}"
                def devProps = [name: (state.sceneFullNames?.get(it) ?: sc.name)]
                def dev = addChildDevice(childNamespace, driverName, devDNI, null, devProps)
            } catch (Exception ex) {
                log.error("Unable to create new scene device for $it: $ex")
            }
        } else {
            log.error("Unable to create new scene for scene $it: ID not found on Hue Bridge")
        }
    }  
    bridge.clearScenesCache()
    //bridge.getAllScenes()
    app.removeSetting("newScenes")
    state.remove("sceneFullNames")
}

/** Sends request for username creation to Bridge API. Intended to be called after user
 *  presses link button on Bridge
 */
void sendUsernameRequest() {
    logDebug("sendUsernameRequest()... (IP = ${state.ipAddress})")
    def userDesc = location.name ? "Hubitat CoCoHue#${location.name}" : "Hubitat CoCoHue"
    def host = "${state.ipAddress}:80"
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
void parseUsernameResponse(hubitat.device.HubResponse resp) {
    def body = resp.json
    logDebug("Attempting to request Hue Bridge username; result = ${body}")
    
    if (body.success != null) {
        if (body.success[0] != null) {
            if (body.success[0].username) {
                state.username = body.success[0].username
                state.bridgeAuthorized = true
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

/** Requests Bridge info (/description.xml by default) to verify that device is a
 *  Hue Bridge and to retrive (when parsed in parseBridgeInfoRequest)
 *  information necessary to create the Bridge device
 */
void sendBridgeInfoRequest(String ssdpPath="/description.xml", Integer portNumber = 80) {
    log.debug("Sending request for Bridge information")
    def host = "${state.ipAddress}:${portNumber}"
    sendHubCommand(new hubitat.device.HubAction([
        method: "GET",
        path: ssdpPath,
        headers: [  HOST: host  ],
        body: []], null, [callback: "parseBridgeInfoResponse"])
    )
}

/** Parses response from GET of description.xml on the Bridge;
 *  verifies that device is a Hue Bridge (modelName contains "Philips Hue Bridge")
 * and obtains MAC address for use in creating Bridge DNI and device name
 */
private parseBridgeInfoResponse(hubitat.device.HubResponse resp) {
    log.debug("Parsing response from Bridge information request")
    def body = resp.xml
    if (body?.device?.modelName?.text().contains("Philips hue bridge")) {
        state.serial
        def serial = body?.device?.serialNumber?.text()
        if (serial) {
            log.debug("Hue Bridge serial parsed as ${serial}; creating device")
            state.bridgeID = serial.reverse().take(6).reverse().toUpperCase() // last 6 of MAC
            def bridgeDevice
            try {
                bridgeDevice = addChildDevice(childNamespace, "CoCoHue Bridge", "CCH/${state.bridgeID}", null,
                               [label: "CoCoHue Bridge (${state.bridgeID})", name: "CoCoHue Bridge"])
                state.bridgeLinked = true
                if (!(settings['boolCustomLabel'])) {
                    app.updateLabel("CoCoHue - Hue Bridge Integration (${state.bridgeID})")
                }
            } catch (Exception e) {
                log.error("Error creating Bridge device: $e")
            }
            if (!state.bridgeLinked) log.error("Unable to create Bridge device. Make sure driver installed and no Bridge device for this MAC already exists.")
        } else {
            log.error("Unexpected response received from Hue Bridge")
        } 
    } else {
        log.error("No Hue Bridge found at IP address")
    }
}

/** Handles response from SSDP (sent to discover Bridge) */
void ssdpHandler(evt) {
    log.debug "In discoverBridgeHandler! $evt.name"
    def parsedMap = parseLanMessage(evt?.description)
    if (parsedMap) {
        def ip = convertHexToIP(parsedMap?.networkAddress)
        if (ip) {
            if (!state.discoveredBridges) state.discoveredBridges = []
            if (!(state.discoveredBridges.any { it.containsKey(ip) } )) {
                state.discoveredBridges.add([(ip): "Hue Bridge ${parsedMap.mac}"])
            }
        }
    } else {
        log.warn("In ssdpHandler but unable to parse LAN message from event: $evt?.description")
    }
    //log.warn parsedMap
}

private String convertHexToIP(hex) {
	[hubitat.helper.HexUtils.hexStringToInt(hex[0..1]),
     hubitat.helper.HexUtils.hexStringToInt(hex[2..3]),
     hubitat.helper.HexUtils.hexStringToInt(hex[4..5]),
     hubitat.helper.HexUtils.hexStringToInt(hex[6..7])].join(".")
}

/** Returns map containing Bridge username, IP, and full HTTP post/port, intended to be
 *  called by child devices so they can send commands to the Hue Bridge API using info
 */
Map getBridgeData() {
    logDebug("Running getBridgeData()...")
    if (!state.ipAddress && settings['bridgeIP'] && !(settings['useSSDP'])) state.ipAddress = settings['bridgeIP'] // seamless upgrade from v1.x
    if (!state["username"] || !state.ipAddress) log.error "Missing username or IP address from Bridge"
    def map = [username: state.username, host: "${state.ipAddress}:80", fullHost: "http://${state.ipAddress}:80"]
    return map
}

/** Calls refresh() method on Bridge child, intended to be called at user-specified
 *  polling interval
 */
private void refreshBridge() {
    def bridge = getChildDevice("CCH/${state.bridgeID}")
    if (!bridge) {
            log.error "No Bridge device found; could not refresh/poll"
            return
    }
    logDebug("Polling Bridge...")
    bridge.refresh()
}

/**
 *  Intended to be called by group child device when state is manipulated in a way that would affect
 *  all member bulbs. Updates member bulb states (so doesn't need to wait for next poll to update)
 *  @param states Map of states in Hue Bridge format (e.g., ["on": true])
 *  @param ids Hue IDs of member bulbs to update
 */
 void updateMemberBulbStatesFromGroup(Map states, List ids) {
    logDebug("Updating member bulb $ids states after group device change...")
    ids?.each {
        def device = getChildDevice("CCH/${state.bridgeID}/Light/${it}")
        device?.createEventsFromMap(states, false)
    }
 }

 /**
 *  Intended to be called by bulb child device when state is manipulated in a way that would affect
 *  group and user has enabled this option. Updates group device states if this bulb ID is found as a
 *  member of that group (so doesn't need to wait for next poll to update)
 *  @param states Map of states in Hue Bridge format (e.g., ["on": true])
 *  @param id Hue bulb ID to search all groups for (will update group if bulb found in group)
 */
 void updateGroupStatesFromBulb(Map states, id) {
    logDebug("Searching for group devices containing bulb $id to update group state after bulb state change...")
    //TODO: There is a better, Groovier way to do this search...
    def matchingGroups = []
    getChildDevices()?.each {
        if (it.getDeviceNetworkId()?.startsWith("CCH/${state.bridgeID}/Group/")) {
            if (it.getMemberBulbIDs()?.contains(id)) {
                logDebug("Bulb $id found in group. Updating states.")
                matchingGroups.add(it)
            }
        }
    }
    matchingGroups.each {
        // Hue app reports "on" if any members on but takes last color/level/etc. from most recent
        // change, so emulate that behavior here
        def onState = getIsAnyGroupMemberBulbOn(it)
        it.createEventsFromMap(states << ["on": onState], false)
    }
 }

 /**
 * Finds Hubitat devices for member bulbs of group and returns true if any (that are found) are on; returns false
 * if all off or no member bulb devices found
 * @param Instance of CoCoHue Group device on which to check member bulb states
 */
Boolean getIsAnyGroupMemberBulbOn(groupDevice) {
    logDebug ("Determining whether any group member bulbs on for group $groupID")
    def retVal = false
    def memberDevices = []
    if (groupDevice) {
        groupDevice.getMemberBulbIDs().each {
            if (!retVal) { // no point in continuing to check if already found one on
                def memberLight = getChildDevice("CCH/${state.bridgeID}/Light/${it}")
                if (memberLight?.currentValue("switch") == "on") retVal = true
            }
        }
    } else {
        logDebug "No group device found for group ID $groupID"
    }
    logDebug("Determined if any group member bulb on: $retVal")
    return retVal
 }

def appButtonHandler(btn) {
    switch(btn) {
        case "btnBulbRefresh":
        case "btnGroupRefresh":
        case "btnSceneRefresh":
            // Just want to resubmit page, so nothing
            break
        default:
            log.warn "Unhandled app button press: $btn"
    }
}

private void logDebug(str) {
    if (!(settings['enableDebug'] == false)) log.debug(str)
}