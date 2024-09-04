// Version 1.0.0

library (
   author: "RMoRobert",
   category: "Convenience",
   description: "For internal CoCoHue use only. Not intended for external use. Contains field variables shared by many CoCoHue apps and drivers.",
   name: "CoCoHue_Constants_Lib",
   namespace: "RMoRobert"
)

// --------------------------------------
// APP AND DRIVER NAMESPACE AND NAMES:
// --------------------------------------
@Field static final String NAMESPACE                  = "__NAMESPACE__"
@Field static final String DRIVER_NAME_BRIDGE         = "__DRIVER_NAME_BRIDGE__"
@Field static final String DRIVER_NAME_BUTTON         = "__DRIVER_NAME_BUTTON__"
@Field static final String DRIVER_NAME_CT_BULB        = "__DRIVER_NAME_CT_BULB__"
@Field static final String DRIVER_NAME_DIMMABLE_BULB  = "__DRIVER_NAME_DIMMABLE_BULB__"
@Field static final String DRIVER_NAME_GROUP          = "__DRIVER_NAME_GROUP__"
@Field static final String DRIVER_NAME_MOTION         = "__DRIVER_NAME_MOTION__"
@Field static final String DRIVER_NAME_PLUG           = "__DRIVER_NAME_PLUG__"
@Field static final String DRIVER_NAME_RGBW_BULB      = "__DRIVER_NAME_RGBW_BULB__"
@Field static final String DRIVER_NAME_RGB_BULB       = "__DRIVER_NAME_RGB_BULB__"
@Field static final String DRIVER_NAME_SCENE          = "__DRIVER_NAME_SCENE__"

// --------------------------------------
// DNI PREFIX for child devices:
// --------------------------------------
@Field static final String DNI_PREFIX = "__DNI_PREFIX__"

// --------------------------------------
// OTHER:
// --------------------------------------
// Used in app and Bridge driver, may eventually find use in more:
@Field static final String APIV1 = "V1"
@Field static final String APIV2 = "V2"