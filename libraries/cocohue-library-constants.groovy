// Version 1.0.0

library (
   author: "RMoRobert",
   category: "Convenience",
   description: "For internal CoCoHue use only. Not intended for external use. Contains field variables shared by many CoCoHue apps and drivers.",
   name: "CoCoHue_Constants_Lib",
   namespace: "RMoRobert"  /* "RMoRobert" for CoCoHue, "hubitat" for builtin */
)

// --------------------------------------
// APP AND DRIVER NAMESPACE AND NAMES:
// --------------------------------------

// -- CoCoHue --
@Field static final String NAMESPACE = "RMoRobert"
@Field static final String APP_NAME = "CoCoHue - Hue Bridge Integration"

// -- Built-in  --
//@Field static final String NAMESPACE = "hubitat"
//@Field static final String APP_NAME = "Hue Bridge Integration"


// -- CoCoHue --
@Field static final String DRIVER_NAME_BRIDGE = "CoCoHue Bridge"
@Field static final String DRIVER_NAME_BUTTON = "CoCoHue Button"
@Field static final String DRIVER_NAME_CT_BULB = "CoCoHue CT Bulb"
@Field static final String DRIVER_NAME_DIMMABLE_BULB = "CoCoHue Dimmable Bulb"
@Field static final String DRIVER_NAME_GROUP = "CoCoHue Group"
@Field static final String DRIVER_NAME_MOTION = "CoCoHue Motion Sensor"
@Field static final String DRIVER_NAME_PLUG = "CoCoHue Plug"
@Field static final String DRIVER_NAME_RGBW_BULB = "CoCoHue RGBW Bulb"
@Field static final String DRIVER_NAME_SCENE = "CoCoHue Scene"

// -- Built-in --
// @Field static final String DRIVER_NAME_BRIDGE = "Hue Bridge"
// @Field static final String DRIVER_NAME_BUTTON = "Hue Bridge Button"
// @Field static final String DRIVER_NAME_CT_BULB = "Hue Bridge CT Bulb"
// @Field static final String DRIVER_NAME_DIMMABLE_BULB = "Hue Bridge Dimmable Bulb"
// @Field static final String DRIVER_NAME_GROUP = "Hue Bridge Group"
// @Field static final String DRIVER_NAME_MOTION = "Hue Bridge Motion Sensor"
// @Field static final String DRIVER_NAME_PLUG = "Hue Bridge Plug"
// @Field static final String DRIVER_NAME_RGBW_BULB = "Hue Bridge RGBW Bulb"
// @Field static final String DRIVER_NAME_SCENE = "Hue Bridge Scene"

// --------------------------------------
// DNI PREFIX for child devices:
// --------------------------------------

@Field static final String DNI_PREFIX = "CCH"   // "CCH" for CoCoHue
//@Field static final String DNI_PREFIX = "Hue"   // "Hue" for built-in

// --------------------------------------
// OTHER:
// --------------------------------------

// Used in app and Bridge driver, may eventually find use in more:
// These are ARE the same in both built-in and custom apps:

@Field static final String APIV1 = "V1"
@Field static final String APIV2 = "V2"
